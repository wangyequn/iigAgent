<template>
  <div class="amp-aibot" :class="{ open: panelOpen }">
    <div v-show="panelOpen" class="amp-aibot-panel" :style="panelStyle">
      <!-- header -->
      <div class="amp-aibot-header" @mousedown="onDragStart">
        <div class="amp-aibot-title">
          <span class="dot" />
          <div>
            <div class="t1">业务小助</div>
            <div class="t2">{{ sessionId }} · {{ loading ? '生成中…' : '就绪' }}</div>
          </div>
        </div>
        <div class="amp-aibot-acts">
          <a-icon v-if="loading" type="pause-circle" title="停止生成" class="act-stop" @click="stopGenerate" />
          <a-icon type="history" title="会话" @click="showHistory = !showHistory" />
          <a-icon type="download" title="导出" @click="exportChat" />
          <a-icon type="plus" title="新会话" @click="newSession" />
          <a-icon type="clear" title="清屏" @click="clearChat" />
          <a-icon :type="maximized ? 'shrink' : 'arrows-alt'" :title="maximized ? '还原' : '最大化'" @click="toggleMax" />
          <a-icon type="close" title="关闭" @click="panelOpen = false" />
        </div>
      </div>

      <div class="amp-aibot-body">
        <!-- history drawer -->
        <div v-if="showHistory" class="amp-aibot-history">
          <div class="h-title">历史会话</div>
          <div
            v-for="s in sessions"
            :key="s.id"
            class="h-item"
            :class="{ active: s.id === sessionId }"
            @click="switchSession(s.id)"
          >
            <div class="h-name">{{ s.title || s.id }}</div>
            <div class="h-time">{{ formatTime(s.updatedAt) }}</div>
            <a-icon type="delete" class="h-del" @click.stop="removeSession(s.id)" />
          </div>
          <div v-if="!sessions.length" class="h-empty">暂无历史</div>
        </div>

        <!-- messages -->
        <div ref="list" class="amp-aibot-list">
          <!-- welcome -->
          <div v-if="!messages.length" class="amp-welcome">
            <div class="w-title">你好，我是业务小助</div>
            <div class="w-sub">可以帮你查用户、组织、角色、字典、公告等（透传你的登录身份）</div>
            <div class="w-chips">
              <span v-for="q in quickPrompts" :key="q" class="chip" @click="send(q)">{{ q }}</span>
            </div>
          </div>

          <div v-for="m in messages" :key="m.id" class="amp-msg" :class="m.role">
            <div class="amp-bubble">
              <div v-if="m.thinking" class="amp-think">
                <div class="think-hd" @click="m.thinkOpen = !m.thinkOpen">
                  <a-icon :type="m.thinkOpen ? 'down' : 'right'" />
                  思考过程
                </div>
                <div v-if="m.thinkOpen" class="think-body">{{ m.thinking }}</div>
              </div>
              <div v-if="m.tools && m.tools.length" class="amp-tools">
                <div class="tool-hd" @click="m.toolsOpen = !m.toolsOpen">
                  <a-icon :type="m.toolsOpen ? 'down' : 'right'" />
                  工具调用（{{ m.tools.length }}）
                </div>
                <div v-if="m.toolsOpen" class="tool-list">
                  <div v-for="(t, i) in m.tools" :key="i" class="tool-row">
                    <span class="tool-name">{{ t.name || t.toolCallName || 'tool' }}</span>
                    <span class="tool-state">{{ t.state || '' }}</span>
                    <div v-if="t.raw" class="tool-raw">{{ t.raw }}</div>
                  </div>
                </div>
              </div>
              <div class="amp-content" v-html="renderMd(m.content)"></div>
              <div v-if="m.error" class="amp-err-actions">
                <a-button size="small" type="link" @click="retry">重试</a-button>
                <a-button size="small" type="link" @click="newSession">新会话</a-button>
              </div>
            </div>
          </div>

          <div v-if="loading" class="amp-msg assistant">
            <div class="amp-bubble loading">
              <span class="blink">正在生成…</span>
            </div>
          </div>
        </div>
      </div>

      <!-- chips -->
      <div class="amp-aibot-chips">
        <span v-for="q in quickPrompts" :key="q" class="chip" @click="send(q)">{{ q }}</span>
      </div>

      <!-- input -->
      <div class="amp-aibot-input">
        <a-textarea
          v-model="draft"
          :rows="2"
          :disabled="loading"
          placeholder="问我业务问题…（Enter 发送，Shift+Enter 换行）"
          @keydown.enter.exact.prevent="onSend"
        />
        <div class="amp-aibot-actions">
          <span class="hint">
            {{ pageHint }}
          </span>
          <a-button v-if="loading" size="small" type="danger" @click="stopGenerate">停止</a-button>
          <a-button v-else type="primary" size="small" @click="onSend">发送</a-button>
        </div>
        <div class="amp-aibot-resize" title="拖拽调整大小" @mousedown.prevent="onResizeStart"></div>
      </div>
    </div>

    <div class="amp-aibot-fab" @click="togglePanel" title="业务小助">
      <a-icon type="robot" />
    </div>
  </div>
</template>

<script>
/**
 * 登录平台业务小助（前端全量增强版）
 * 会话历史 / 多会话 / 快捷指令 / Markdown / 思考与工具折叠 / 错误重试 / 导出 / 页面上下文
 * 不改 AI 中台；仅调用 messages:stream
 */
import Vue from 'vue'
import { marked } from 'marked'
import { ACCESS_TOKEN } from '@/store/mutation-types'

const DEFAULT_AI_BASE = process.env.VUE_APP_AI_BASE || 'http://127.0.0.1:18081'
const HIST_KEY = 'amp.aibot.sessions.v1'
const CUR_KEY = 'amp.aibot.current.v1'
const UI_KEY = 'amp.aibot.ui.v1'

marked.setOptions({ breaks: true, gfm: true })

function loadJson(key, def) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null') || def
  } catch (e) {
    return def
  }
}
function saveJson(key, val) {
  try {
    localStorage.setItem(key, JSON.stringify(val))
  } catch (e) {
    /* quota */
  }
}
function uid() {
  return Math.random().toString(36).slice(2, 10)
}

export default {
  name: 'AiBot',
  props: {
    aiBase: { type: String, default: '' },
    agentId: { type: String, default: 'biz-assist' }
  },
  data: function () {
    return {
      panelOpen: false,
      showHistory: false,
      draft: '',
      loading: false,
      messages: [],
      sessions: [],
      sessionId: '',
      lastUserText: '',
      quickPrompts: [
        '查我的信息',
        '查我的角色',
        '查我的菜单权限',
        '看最近公告',
        '查字典：性别',
        '查我的部门树'
      ],
      drag: { active: false, x: 0, y: 0, left: 0, top: 0 },
      pos: { left: null, top: null },
      maximized: false,
      size: { w: 440, h: 580 },
      abortCtrl: null,
      stopped: false
    }
  },
  computed: {
    effectiveAiBase: function () {
      return (this.aiBase || DEFAULT_AI_BASE).replace(/\/$/, '')
    },
    accessToken: function () {
      try {
        return (
          Vue.ls.get(ACCESS_TOKEN) ||
          localStorage.getItem('Platform_Access-Token') ||
          localStorage.getItem('ACCESS_TOKEN') ||
          ''
        )
      } catch (e) {
        return localStorage.getItem('Platform_Access-Token') || ''
      }
    },
    effectiveUserId: function () {
      try {
        var u = Vue.ls.get('userId')
        if (!u && this.$store && this.$store.state && this.$store.state.user && this.$store.state.user.userInfo) {
          u = this.$store.state.user.userInfo.username
        }
        return u || 'user_' + this.sessionId
      } catch (e) {
        return 'user_' + this.sessionId
      }
    },
    pageHint: function () {
      var p = (this.$route && this.$route.path) || ''
      if (!p) return 'Enter 发送 · Shift+Enter 换行'
      return '当前页面：' + p + '（提问可带上下文）'
    },
    panelStyle: function () {
      var base = {
        width: this.maximized ? 'min(96vw, 1100px)' : this.size.w + 'px',
        height: this.maximized ? 'min(88vh, 800px)' : this.size.h + 'px'
      }
      if (this.maximized) {
        base.left = '50%'
        base.top = '50%'
        base.right = 'auto'
        base.bottom = 'auto'
        base.transform = 'translate(-50%, -50%)'
        return base
      }
      base.transform = 'none'
      if (this.pos.left != null) {
        base.left = this.pos.left + 'px'
        base.top = this.pos.top + 'px'
        base.right = 'auto'
        base.bottom = 'auto'
      }
      return base
    }
  },
  watch: {
    messages: {
      deep: true,
      handler: function () {
        this.persist()
        this.scrollBottom()
      }
    },
    '$route.path': function () {
      /* keep hint reactive */
    }
  },
  created: function () {
    this.sessions = loadJson(HIST_KEY, [])
    var cur = localStorage.getItem(CUR_KEY)
    if (cur && this.sessions.some(function (s) { return s.id === cur })) {
      this.sessionId = cur
    } else {
      this.sessionId = 'sess_' + uid()
    }
    this.loadSession(this.sessionId)
    var ui = loadJson(UI_KEY, {})
    if (ui && ui.left != null) {
      this.pos = { left: ui.left, top: ui.top }
    }
  },
  methods: {
    renderMd: function (text) {
      if (!text) return ''
      try {
        return marked.parse(String(text))
      } catch (e) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      }
    },
    formatTime: function (ts) {
      if (!ts) return ''
      var d = new Date(ts)
      return d.getMonth() + 1 + '/' + d.getDate() + ' ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0')
    },
    scrollBottom: function () {
      this.$nextTick(function () {
        var el = this.$refs.list
        if (el) el.scrollTop = el.scrollHeight
      })
    },
    persist: function () {
      var list = this.sessions.slice()
      var idx = list.findIndex(function (s) { return s.id === this.sessionId }.bind(this))
      var title = ''
      var first = this.messages.find(function (m) { return m.role === 'user' })
      if (first) title = String(first.content).slice(0, 24)
      var rec = {
        id: this.sessionId,
        title: title || this.sessionId,
        updatedAt: Date.now(),
        messages: this.messages
      }
      if (idx >= 0) list[idx] = rec
      else list.unshift(rec)
      if (list.length > 20) list = list.slice(0, 20)
      this.sessions = list
      saveJson(HIST_KEY, list)
      localStorage.setItem(CUR_KEY, this.sessionId)
    },
    loadSession: function (id) {
      var s = this.sessions.find(function (x) { return x.id === id })
      this.sessionId = id
      this.messages = s && s.messages ? JSON.parse(JSON.stringify(s.messages)) : []
      localStorage.setItem(CUR_KEY, id)
    },
    switchSession: function (id) {
      this.loadSession(id)
      this.showHistory = false
    },
    removeSession: function (id) {
      this.sessions = this.sessions.filter(function (s) { return s.id !== id })
      saveJson(HIST_KEY, this.sessions)
      if (id === this.sessionId) this.newSession()
    },
    newSession: function () {
      this.sessionId = 'sess_' + uid()
      this.messages = []
      this.showHistory = false
      localStorage.setItem(CUR_KEY, this.sessionId)
      this.persist()
    },
    clearChat: function () {
      this.messages = []
      this.persist()
    },
    exportChat: function () {
      var lines = ['# 业务小助会话 ' + this.sessionId, '']
      this.messages.forEach(function (m) {
        lines.push('## ' + (m.role === 'user' ? '用户' : '助手'))
        lines.push(m.content || '')
        if (m.thinking) {
          lines.push('')
          lines.push('> 思考：' + m.thinking.slice(0, 500))
        }
        lines.push('')
      })
      var blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
      var a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = 'aibot-' + this.sessionId + '.md'
      a.click()
    },
    togglePanel: function () {
      this.panelOpen = !this.panelOpen
    },
    onDragStart: function (e) {
      if (e.target && e.target.closest && e.target.closest('.amp-aibot-acts')) return
      this.drag.active = true
      this.drag.x = e.clientX
      this.drag.y = e.clientY
      var panel = e.currentTarget.parentElement
      this.drag.left = panel.getBoundingClientRect().left
      this.drag.top = panel.getBoundingClientRect().top
      var self = this
      function move(ev) {
        if (!self.drag.active) return
        self.pos.left = self.drag.left + (ev.clientX - self.drag.x)
        self.pos.top = self.drag.top + (ev.clientY - self.drag.y)
      }
      function up() {
        self.drag.active = false
        document.removeEventListener('mousemove', move)
        document.removeEventListener('mouseup', up)
        saveJson(UI_KEY, { left: self.pos.left, top: self.pos.top })
      }
      document.addEventListener('mousemove', move)
      document.addEventListener('mouseup', up)
    },
    stopGenerate: function () {
      this.stopped = true
      if (this.abortCtrl) {
        try { this.abortCtrl.abort() } catch (e) {}
      }
      this.abortCtrl = null
      this.loading = false
      var last = null
      for (var i = this.messages.length - 1; i >= 0; i--) {
        if (this.messages[i].role === 'assistant') { last = this.messages[i]; break }
      }
      if (last && !last.content) last.content = '（已停止）'
      else if (last) last.content += '\n\n（已停止）'
      this.persist()
    },
    toggleMax: function () {
      this.maximized = !this.maximized
    },
    onResizeStart: function (e) {
      var self = this
      var el = this.$el.querySelector('.amp-aibot-panel')
      var rect = el.getBoundingClientRect()
      var startX = e.clientX
      var startY = e.clientY
      var w0 = rect.width
      var h0 = rect.height
      function move(ev) {
        if (self.maximized) return
        self.size.w = Math.min(Math.max(360, w0 + (ev.clientX - startX)), Math.min(960, window.innerWidth - 40))
        self.size.h = Math.min(Math.max(420, h0 + (ev.clientY - startY)), Math.min(820, window.innerHeight - 40))
      }
      function up() {
        document.removeEventListener('mousemove', move)
        document.removeEventListener('mouseup', up)
      }
      document.addEventListener('mousemove', move)
      document.addEventListener('mouseup', up)
    },
    onSend: function () {
      this.send(this.draft)
    },
    retry: function () {
      if (this.lastUserText) this.send(this.lastUserText)
    },
    send: function (text) {
      var content = (text || '').trim()
      if (!content || this.loading) return
      var self = this
      this.lastUserText = content
      if (!this.accessToken) {
        this.messages.push({
          id: uid(),
          role: 'assistant',
          content: '未检测到登录票，请先登录。',
          error: true
        })
        return
      }
      this.messages.push({ id: uid(), role: 'user', content: content })
      this.draft = ''
      var acc = {
        id: uid(),
        role: 'assistant',
        content: '',
        thinking: '',
        tools: [],
        thinkOpen: false,
        toolsOpen: false,
        error: false
      }
      this.messages.push(acc)
      this.loading = true
      this.scrollBottom()

      // 页面上下文前缀（轻量）
      var routePath = (this.$route && this.$route.path) || ''
      var payload = content
      if (routePath && routePath !== '/' && content.indexOf('当前页面') < 0) {
        payload = content + '\n\n（当前页面：' + routePath + '）'
      }

      if (this.abortCtrl) {
        try { this.abortCtrl.abort() } catch (e) {}
      }
      this.stopped = false
      if (typeof AbortController !== 'undefined') {
        this.abortCtrl = new AbortController()
      }
      fetch(this.effectiveAiBase + '/api/v1/agents/' + this.agentId + '/messages:stream', {
        signal: this.abortCtrl ? this.abortCtrl.signal : undefined,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Access-Token': this.accessToken
        },
        body: JSON.stringify({
          userId: this.effectiveUserId,
          sessionId: this.sessionId,
          message: payload
        })
      })
        .then(function (res) {
          if (!res.ok) {
            return res.text().then(function (err) {
              acc.error = true
              acc.content = '请求失败（' + res.status + '）：' + (err || res.statusText)
            })
          }
          if (!res.body || !res.body.getReader) {
            return res.text().then(function (t) {
              acc.content = t || '（空回复）'
            })
          }
          var reader = res.body.getReader()
          var decoder = new TextDecoder('utf-8')
          var buf = ''
          var pump = function () {
            return reader.read().then(function (chunk) {
              if (chunk.done) return
              buf += decoder.decode(chunk.value, { stream: true })
              var parts = buf.split('\n\n')
              buf = parts.pop()
              for (var i = 0; i < parts.length; i++) {
                var raw = ''
                var lines = parts[i].split('\n')
                for (var j = 0; j < lines.length; j++) {
                  if (lines[j].indexOf('data:') === 0) {
                    raw = lines[j].slice(5).trim()
                    break
                  }
                }
                if (!raw || raw === '[DONE]') continue
                self.applyEvent(raw, acc)
              }
              self.scrollBottom()
              return pump()
            })
          }
          return pump()
        })
        .catch(function (e) {
          if (self.stopped || (e && e.name === 'AbortError')) {
            if (!acc.content) acc.content = '（已停止）'
            else acc.content += '\n\n（已停止）'
            return
          }
          acc.error = true
          acc.content = (acc.content ? acc.content + '\n\n' : '') + '网络错误：' + (e && e.message ? e.message : String(e))
        })
        .then(function () {
          if (!acc.content && !acc.error) acc.content = '（空回复）'
          self.loading = false
          self.scrollBottom()
          self.persist()
        })
    },
    applyEvent: function (raw, acc) {
      try {
        var evt = JSON.parse(raw)
        var type = evt.type || ''
        if (type === 'ERROR' || evt.code === 'STREAM_ERROR' || evt.error) {
          acc.error = true
          acc.content += (acc.content ? '\n\n' : '') + '[错误] ' + (evt.message || evt.error || '流式错误')
          return
        }
        if (type.indexOf('THINKING') >= 0 && evt.delta) {
          acc.thinking += evt.delta
          return
        }
        if (type.indexOf('TOOL') >= 0) {
          var name = evt.toolCallName || evt.toolName || ''
          var st = evt.state || type
          var exist = acc.tools.find(function (t) {
            return t.name === name && name
          })
          if (exist) {
            exist.state = st
            if (evt.delta) exist.raw = (exist.raw || '') + evt.delta
          } else if (name || evt.delta) {
            acc.tools.push({ name: name, state: st, raw: evt.delta || '' })
          }
          return
        }
        var delta = evt.delta || evt.text || ''
        if (!delta) return
        if (type.indexOf('TEXT') >= 0 || type === '' || (type.indexOf('DELTA') >= 0 && type.indexOf('TOOL') < 0)) {
          acc.content += delta
        }
      } catch (e) {
        acc.content += raw
      }
    }
  }
}
</script>

<style scoped>
.amp-aibot {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 10000;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.amp-aibot-fab {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0a71f4, #2563eb);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(10, 113, 244, 0.4);
}
.amp-aibot-panel {
  width: 440px;
  height: 580px;
  background: #fff;
  position: fixed;
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.18);
  display: flex;
  flex-direction: column;
  margin-bottom: 12px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  right: 24px;
  bottom: 88px;
  position: fixed;
}
.amp-aibot-header {
  padding: 12px 14px;
  background: linear-gradient(90deg, #0a71f4, #3b82f6);
  color: #fff;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: move;
  user-select: none;
}
.amp-aibot-title {
  display: flex;
  gap: 10px;
  align-items: center;
}
.amp-aibot-title .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 0 3px rgba(74, 222, 128, 0.35);
}
.t1 {
  font-weight: 600;
}
.t2 {
  font-size: 11px;
  opacity: 0.85;
}
.amp-aibot-acts {
  display: flex;
  gap: 12px;
  font-size: 15px;
}
.amp-aibot-acts .anticon {
  cursor: pointer;
  opacity: 0.9;
}
.amp-aibot-acts .anticon:hover {
  opacity: 1;
  transform: scale(1.08);
}
.amp-aibot-body {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}
.amp-aibot-history {
  width: 180px;
  border-right: 1px solid #e2e8f0;
  overflow: auto;
  background: #f8fafc;
  padding: 8px;
}
.h-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}
.h-item {
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
  background: #fff;
  border: 1px solid transparent;
  position: relative;
}
.h-item.active {
  border-color: #0a71f4;
}
.h-name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.h-time {
  font-size: 10px;
  color: #94a3b8;
}
.h-del {
  position: absolute;
  right: 6px;
  top: 6px;
  opacity: 0;
}
.h-item:hover .h-del {
  opacity: 1;
}
.h-empty {
  color: #94a3b8;
  font-size: 12px;
}
.amp-aibot-list {
  flex: 1;
  overflow: auto;
  padding: 12px;
  background: #f1f5f9;
}
.amp-welcome {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid #e2e8f0;
}
.w-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.w-sub {
  color: #64748b;
  font-size: 12px;
  margin-bottom: 10px;
}
.w-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.amp-msg {
  margin-bottom: 12px;
  display: flex;
}
.amp-msg.user {
  justify-content: flex-end;
}
.amp-bubble {
  max-width: 88%;
  background: #fff;
  border-radius: 12px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.amp-msg.user .amp-bubble {
  background: #0a71f4;
  color: #fff;
  border-color: #0a71f4;
}
.amp-content {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}
.amp-content >>> pre {
  background: #0f172a;
  color: #e2e8f0;
  padding: 8px;
  border-radius: 6px;
  overflow: auto;
}
.amp-content >>> code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.amp-content >>> table {
  border-collapse: collapse;
  width: 100%;
}
.amp-content >>> th,
.amp-content >>> td {
  border: 1px solid #e2e8f0;
  padding: 4px 6px;
}
.amp-think,
.amp-tools {
  margin-bottom: 8px;
  font-size: 12px;
}
.think-hd,
.tool-hd {
  color: #2563eb;
  cursor: pointer;
  user-select: none;
}
.think-body,
.tool-raw {
  margin-top: 4px;
  background: #f8fafc;
  border-radius: 6px;
  padding: 6px;
  color: #475569;
  max-height: 120px;
  overflow: auto;
  white-space: pre-wrap;
}
.tool-row {
  margin-top: 4px;
}
.tool-name {
  font-family: ui-monospace, Menlo, monospace;
  background: #e0e7ff;
  padding: 1px 6px;
  border-radius: 4px;
}
.tool-state {
  margin-left: 6px;
  color: #64748b;
}
.amp-err-actions {
  margin-top: 6px;
}
.amp-bubble.loading {
  color: #64748b;
}
.blink {
  animation: ampblink 1.2s infinite;
}
@keyframes ampblink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}
.amp-aibot-chips {
  padding: 8px 12px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}
.chip {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  cursor: pointer;
  border: 1px solid #bfdbfe;
}
.chip:hover {
  background: #dbeafe;
}
.amp-aibot-resize {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16px;
  height: 16px;
  cursor: nwse-resize;
  background: linear-gradient(135deg, transparent 50%, #94a3b8 50%);
  border-radius: 0 0 12px 0;
  z-index: 5;
}
.act-stop {
  color: #f87171 !important;
}
.amp-aibot-input {
  padding: 10px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}
.amp-aibot-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.hint {
  font-size: 11px;
  color: #94a3b8;
  max-width: 70%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
