<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'
import ChatStream from '@/components/chat/ChatStream.vue'
import HitlConfirmDialog from '@/components/chat/HitlConfirmDialog.vue'
import JsonView from '@/components/ui/JsonView.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  chatOnce,
  streamChat,
  sessionHistory,
  interruptSession,
  clearContext,
  listSessionApprovals,
  confirmSession,
  openSessionStream,
} from '@/api'

const store = useAppStore()

const messages = ref([])
const pendingCalls = ref([])
const streamText = ref('')
const streaming = ref(false)
const input = ref('')
const busy = ref(false)
const confirming = ref(false)
const hitlOpen = ref(false)
const lastEventId = ref(null)
const sseEvents = ref([])
const showEvents = ref(false)
const showHistory = ref(false)
const history = ref(null)
const historyLoading = ref(false)
const toolTimeline = ref([])
const abortFn = ref(null)
const recentSessions = ref(loadRecentSessions())
const suggestions = ['查一下今天的订单', '总结昨日异常订单', '帮我下单并确认', '介绍一下退货政策']

const readyLabel = computed(() => {
  const r = store.ready
  if (!r) return 'UNKNOWN'
  if (r.status === 'UP' || r.ready === true || r.status === 'ready') return 'UP'
  return String(r.status || r.ready || 'DOWN').toUpperCase()
})

const stats = computed(() => ({
  tools: toolTimeline.value.length,
  events: sseEvents.value.length,
}))

function loadRecentSessions() {
  try {
    return JSON.parse(localStorage.getItem('amp.recentSessions') || '[]')
  } catch {
    return []
  }
}

function rememberSession() {
  const id = store.sessionId
  if (!id) return
  const item = {
    sessionId: id,
    agentId: store.agentId,
    userId: store.userId,
    at: Date.now(),
  }
  const list = recentSessions.value.filter((x) => x.sessionId !== id)
  list.unshift(item)
  recentSessions.value = list.slice(0, 12)
  localStorage.setItem('amp.recentSessions', JSON.stringify(recentSessions.value))
}

function switchSession(s) {
  store.agentId = s.agentId || store.agentId
  store.userId = s.userId || store.userId
  if (s.sessionId === store.sessionId) {
    reloadFromServer()
    return
  }
  // 切换会话：先清本地，再拉历史，保证可继续
  messages.value = []
  pendingCalls.value = []
  toolTimeline.value = []
  sseEvents.value = []
  lastEventId.value = null
  streamText.value = ''
  store.sessionId = s.sessionId
}

watch(
  () => [store.agentId, store.sessionId, store.userId],
  () => {
    loadHistoryIntoChat()
  },
)

watch(
  () => pendingCalls.value.length,
  (n) => {
    if (n > 0) hitlOpen.value = true
  },
)

function parseJson(s) {
  try {
    return JSON.parse(s)
  } catch {
    return { raw: s }
  }
}

function pushMsg(role, text, extra = {}) {
  messages.value.push({ role, text, ts: Date.now(), ...extra })
}

function flushStreamText() {
  if (streamText.value) {
    pushMsg('assistant', streamText.value)
    streamText.value = ''
  }
}

function normalizeToolCall(raw) {
  return {
    toolCallId: raw.toolCallId || raw.id,
    toolName: raw.toolName || raw.tool || raw.name || 'tool',
    toolInput: raw.toolInput || raw.content,
    input: raw.input && typeof raw.input === 'object' ? raw.input : undefined,
    suggestedRules: raw.suggestedRules || [],
    pending: true,
  }
}

/** history → 对话流；role 兼容 user/assistant/ai/human */
function mapHistoryToMessages(data) {
  const list = data?.messages || data?.items || data?.history || []
  return list
    .map((m, i) => {
      const roleRaw = String(m.role || '').toLowerCase()
      let role = 'assistant'
      if (roleRaw === 'user' || roleRaw === 'human') role = 'user'
      else if (roleRaw === 'assistant' || roleRaw === 'ai' || roleRaw === 'model') role = 'assistant'
      else if (roleRaw === 'tool') role = 'tool'
      else if (roleRaw === 'thinking' || roleRaw === 'system') role = 'thinking'
      return {
        role,
        text: m.text || m.content || m.message || '',
        ts: m.timestamp ? Number(m.timestamp) || Date.parse(m.timestamp) || Date.now() : Date.now(),
        _key: `h-${i}`,
      }
    })
    .filter((m) => m.text || m.role === 'tool')
}

/**
 * 拉取服务端历史并写入对话流。
 * - force：强制以服务端为准（恢复历史 / 切换会话）
 * - 否则仅在本地还没有「本轮新消息」时填充，避免覆盖进行中对话
 */
async function loadHistoryIntoChat({ force = false } = {}) {
  if (!store.agentId || !store.sessionId) return
  historyLoading.value = true
  try {
    const data = await sessionHistory(store.agentId, store.sessionId, store.userId)
    history.value = data
    const mapped = mapHistoryToMessages(data)
    const hasLive = messages.value.some((m) => !m._key?.startsWith('h-'))
    if (force || !hasLive) {
      messages.value = mapped
    }
    rememberSession()
  } catch {
    // 空会话 / 新会话无历史属正常
    if (force) messages.value = []
  } finally {
    historyLoading.value = false
  }
}

function handleEvent(name, data, id) {
  if (id) lastEventId.value = id
  sseEvents.value.push({ name, data, id, at: Date.now() })
  if (sseEvents.value.length > 200) sseEvents.value.splice(0, sseEvents.value.length - 200)

  const obj = parseJson(data)
  const type = obj.type || name

  if (type === 'TEXT_BLOCK_DELTA' || name === 'TEXT_BLOCK_DELTA') {
    streamText.value += obj.delta || obj.text || ''
    return
  }
  if (type === 'THINKING_DELTA' || name === 'THINKING_DELTA') {
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'thinking') last.text += obj.delta || obj.text || ''
    else pushMsg('thinking', obj.delta || obj.text || '')
    return
  }
  if (type === 'REQUIRE_USER_CONFIRM' || name === 'REQUIRE_USER_CONFIRM') {
    flushStreamText()
    const list = Array.isArray(obj.toolCalls) ? obj.toolCalls : [obj]
    for (const raw of list) {
      const tool = normalizeToolCall(raw)
      if (!tool.toolCallId && !tool.toolName) continue
      const exists = pendingCalls.value.some(
        (x) =>
          (x.toolCallId && x.toolCallId === tool.toolCallId) ||
          (!x.toolCallId && x.toolName === tool.toolName),
      )
      if (!exists) {
        pendingCalls.value.push(tool)
        toolTimeline.value.push({ ...tool, state: 'WAITING_CONFIRM', at: Date.now() })
      }
    }
    hitlOpen.value = true
    store.notify({
      type: 'warning',
      title: '需要人工确认',
      message: list.map((x) => x.toolName || x.tool || 'tool').join(', '),
    })
    return
  }
  if (type === 'TOOL_RESULT_END' || name === 'TOOL_RESULT_END') {
    toolTimeline.value.push({
      toolName: obj.toolCallName || obj.toolName,
      toolCallId: obj.toolCallId,
      state: obj.state || 'OK',
      result: obj.result || obj.output,
      at: Date.now(),
    })
    return
  }
  if (type === 'ExceedMaxIters' || type === 'AllToolsDenied' || type === 'Hint') {
    store.notify({ type: 'info', title: type, message: obj.message || obj.reason || '' })
  }
}

async function send(mode = 'stream') {
  const text = input.value.trim()
  if (!text || busy.value) return
  pushMsg('user', text)
  input.value = ''
  busy.value = true
  streaming.value = mode === 'stream'
  streamText.value = ''
  rememberSession()
  const body = {
    userId: store.userId,
    sessionId: store.sessionId,
    message: text,
  }
  try {
    if (mode === 'stream') {
      lastEventId.value = await streamChat(store.agentId, body, handleEvent, null)
      flushStreamText()
      if (pendingCalls.value.length) {
        hitlOpen.value = true
        ElMessage.warning('工具调用等待确认，请在弹窗中处理')
      }
    } else {
      const data = await chatOnce(store.agentId, body)
      pushMsg('assistant', data.reply || data.text || data.message || JSON.stringify(data))
    }
  } catch (e) {
    if (e.status === 409) {
      flushStreamText()
      ElMessage.warning('工具调用等待人工确认')
      await refreshApprovals()
      if (pendingCalls.value.length) hitlOpen.value = true
    } else {
      ElMessage.error(e.message || String(e))
      store.notify({ type: 'error', title: '对话失败', message: e.message, code: e.code })
    }
  } finally {
    busy.value = false
    streaming.value = false
  }
}

async function refreshApprovals() {
  try {
    const data = await listSessionApprovals(store.agentId, store.sessionId, store.userId)
    pendingCalls.value = (data.pending || []).map(normalizeToolCall)
  } catch {
    /* optional */
  }
}

function parseToolInput(s) {
  if (s && typeof s === 'object') return s
  try {
    return JSON.parse(s || '{}')
  } catch {
    return {}
  }
}

async function confirmTool(call, approved, acceptRule = false) {
  if (confirming.value) return
  confirming.value = true
  try {
    flushStreamText()
    const data = await confirmSession(store.agentId, store.sessionId, {
      userId: store.userId,
      confirmed: approved,
      toolCalls: [
        {
          toolCallId: call.toolCallId || call.id,
          toolName: call.toolName || call.tool || 'tool',
          confirmed: approved,
          input: call.input || parseToolInput(call.toolInput),
          acceptRule: approved && acceptRule,
        },
      ],
      acceptRule: approved && acceptRule,
      note: call.note || (approved ? 'ok' : 'reject'),
    })

    const reply = data?.reply || data?.text || ''
    pushMsg('tool', '', {
      call: {
        toolName: call.toolName,
        toolCallId: call.toolCallId,
        state: approved ? 'OK' : 'DENIED',
        result: approved ? '用户已批准，已继续执行' : '用户已拒绝',
      },
    })
    if (reply) pushMsg('assistant', reply)
    else pushMsg('assistant', approved ? '已按批准继续处理。' : '已按拒绝结束该工具调用。')

    pendingCalls.value = pendingCalls.value.filter(
      (x) => (x.toolCallId || x.id) !== (call.toolCallId || call.id),
    )
    if (!pendingCalls.value.length) hitlOpen.value = false

    ElMessage.success(approved ? (acceptRule ? '已批准并永久允许，会话已继续' : '已批准，会话已继续') : '已拒绝')
    await refreshApprovals()
    if (pendingCalls.value.length) hitlOpen.value = true
  } catch (e) {
    if (e.status === 409) {
      pushMsg('assistant', '执行后再次进入人工确认（如模型重试），请继续处理。')
      await refreshApprovals()
      hitlOpen.value = true
      ElMessage.warning('需要继续确认')
    } else {
      ElMessage.error(e.message || String(e))
    }
  } finally {
    confirming.value = false
  }
}

function onHitlDecide({ call, confirmed, acceptRule, note }) {
  confirmTool({ ...call, note }, confirmed, acceptRule)
}

async function doInterrupt() {
  try {
    await interruptSession(store.agentId, store.sessionId, store.userId)
    ElMessage.success('已请求中断')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doClear() {
  try {
    await clearContext(store.agentId, store.sessionId, store.userId)
    messages.value = []
    pendingCalls.value = []
    toolTimeline.value = []
    hitlOpen.value = false
    ElMessage.success('上下文已清空')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function showRawHistory() {
  try {
    history.value = await sessionHistory(store.agentId, store.sessionId, store.userId)
    showHistory.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 从服务端恢复历史到对话流（可继续聊） */
async function reloadFromServer() {
  await loadHistoryIntoChat({ force: true })
  await refreshApprovals()
  if (pendingCalls.value.length) hitlOpen.value = true
  const n = history.value?.messages?.length || messages.value.length
  ElMessage.success(n ? `已恢复 ${n} 条历史，可直接继续对话` : '该会话暂无历史，可直接开始对话')
}

async function resumeStream() {
  if (!lastEventId.value) {
    ElMessage.warning('暂无 Last-Event-ID，请先流式对话')
    return
  }
  busy.value = true
  try {
    await streamChat(
      store.agentId,
      { userId: store.userId, sessionId: store.sessionId, message: '(resume)' },
      handleEvent,
      lastEventId.value,
    )
    flushStreamText()
    ElMessage.success('续传完成')
    if (pendingCalls.value.length) hitlOpen.value = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

function toggleLiveStream() {
  if (abortFn.value) {
    abortFn.value()
    abortFn.value = null
    ElMessage.info('已关闭只读事件流')
    return
  }
  abortFn.value = openSessionStream(
    store.agentId,
    store.sessionId,
    store.userId,
    handleEvent,
    lastEventId.value,
  )
  ElMessage.success('已打开会话只读事件流')
}

function newSession() {
  const id = `s_${Date.now().toString(36)}`
  store.sessionId = id
  messages.value = []
  pendingCalls.value = []
  toolTimeline.value = []
  sseEvents.value = []
  lastEventId.value = null
  rememberSession()
  ElMessage.success(`已新建会话 ${id}`)
}

onMounted(async () => {
  await store.refreshHealth()
  await reloadFromServer()
})

onUnmounted(() => {
  if (abortFn.value) abortFn.value()
})
</script>

<template>
  <div class="page chat-page">
    <div class="page-header">
      <div>
        <h1 class="t-page">智能对话 Playground</h1>
        <p class="desc">
          历史可恢复 · 流式对话 · HITL 审批后自动继续
          <span class="mono t-faint" style="margin-left:8px">
            {{ store.agentId }} / {{ store.sessionId }}
          </span>
        </p>
      </div>
      <div class="page-actions">
        <el-button size="small" type="primary" plain @click="newSession">新会话</el-button>
        <el-button size="small" @click="reloadFromServer" :loading="historyLoading">恢复历史</el-button>
        <el-button size="small" @click="doInterrupt" :disabled="busy">中断</el-button>
        <el-button size="small" @click="resumeStream" :disabled="busy">续传 SSE</el-button>
        <el-button size="small" @click="toggleLiveStream">
          {{ abortFn ? '关闭事件流' : '打开事件流' }}
        </el-button>
        <el-button size="small" @click="showRawHistory">原始 History</el-button>
        <el-button size="small" type="danger" plain @click="doClear">清上下文</el-button>
      </div>
    </div>

    <transition name="fade">
      <div v-if="pendingCalls.length" class="hitl-banner" @click="hitlOpen = true">
        <div class="banner-left">
          <div class="pulse-dot" />
          <div>
            <div class="banner-title">工具调用等待你的确认（{{ pendingCalls.length }}）</div>
            <div class="banner-sub">
              {{ pendingCalls.map((c) => c.toolName).join('、') }} · 点击处理，批准后会话自动继续
            </div>
          </div>
        </div>
        <el-button type="warning" size="small" @click.stop="hitlOpen = true">立即处理</el-button>
      </div>
    </transition>

    <div class="chat-layout">
      <aside class="session-side">
        <div class="card session-card">
          <div class="card-header">
            <div class="title">最近会话</div>
            <el-button size="small" text type="primary" @click="newSession">新建</el-button>
          </div>
          <div class="session-list">
            <div
              v-for="s in recentSessions"
              :key="s.sessionId"
              class="session-item"
              :class="{ active: s.sessionId === store.sessionId }"
              @click="switchSession(s)"
            >
              <div class="sid mono">{{ s.sessionId }}</div>
              <div class="meta t-faint">{{ s.agentId }} · {{ new Date(s.at).toLocaleString() }}</div>
            </div>
            <el-empty v-if="!recentSessions.length" description="暂无最近会话" :image-size="48" />
          </div>
        </div>
        <div class="card session-card">
          <div class="card-header">
            <div class="title">当前上下文</div>
          </div>
          <div class="card-pad compact">
            <div class="side-row"><span>Agent</span><b class="mono">{{ store.agentId }}</b></div>
            <div class="side-row"><span>Session</span><b class="mono">{{ store.sessionId }}</b></div>
            <div class="side-row"><span>User</span><b class="mono">{{ store.userId }}</b></div>
            <div class="side-row"><span>消息</span><b>{{ messages.length }}</b></div>
            <div class="side-row"><span>待审批</span><b>{{ pendingCalls.length }}</b></div>
          </div>
        </div>
      </aside>

      <section class="chat-main card">
        <div class="chat-scroll">
          <div v-if="historyLoading && !messages.length" class="loading-hint t-muted">
            正在恢复历史对话…
          </div>
          <ChatStream
            :messages="messages"
            :streaming="streaming"
            :stream-text="streamText"
            :pending-calls="pendingCalls"
            :suggestions="suggestions"
            @approve="(c) => confirmTool(c, true, false)"
            @reject="(c) => confirmTool(c, false, false)"
            @allow-always="(c) => confirmTool(c, true, true)"
            @pick-suggestion="(s) => { input = s; send('stream') }"
          />
        </div>
        <div class="composer">
          <textarea
            v-model="input"
            rows="3"
            placeholder="继续对话…历史已加载时可直接接着聊，Ctrl+Enter 发送"
            @keydown.enter.ctrl.prevent="send('stream')"
            @keydown.enter.meta.prevent="send('stream')"
          />
          <div class="composer-bar">
            <div class="left t-faint">
              <StatusTag
                :value="busy ? 'RUNNING' : pendingCalls.length ? 'WAITING_CONFIRM' : messages.length ? 'ACTIVE' : 'READY'"
              />
              <span>消息 {{ messages.length }} · 工具 {{ stats.tools }}</span>
            </div>
            <div class="right">
              <el-button :disabled="busy" @click="send('once')">非流式</el-button>
              <el-button type="primary" :loading="busy" @click="send('stream')">继续发送</el-button>
            </div>
          </div>
        </div>
      </section>

      <aside class="chat-side">
        <div v-if="pendingCalls.length" class="card side-card hitl-side">
          <div class="card-header">
            <div class="title" style="color:var(--warn)">待确认工具</div>
            <el-button size="small" type="warning" @click="hitlOpen = true">打开确认</el-button>
          </div>
          <div class="card-pad side-stats">
            <div v-for="c in pendingCalls" :key="c.toolCallId || c.toolName" class="tl-item">
              <StatusTag value="WAITING_CONFIRM" />
              <div>
                <div class="mono t-body">{{ c.toolName }}</div>
                <div class="t-faint">批准后自动继续执行</div>
              </div>
            </div>
          </div>
        </div>

        <div class="card side-card">
          <div class="card-header">
            <div class="title">工具调用时间线</div>
          </div>
          <div class="card-pad timeline">
            <el-empty v-if="!toolTimeline.length" description="暂无工具调用" :image-size="56" />
            <div v-for="(t, i) in toolTimeline" :key="i" class="tl-item">
              <StatusTag :value="t.state || 'OK'" />
              <div>
                <div class="mono t-body">{{ t.toolName }}</div>
                <div class="t-faint">{{ t.result?.slice?.(0, 80) || '—' }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="card side-card">
          <div class="card-header">
            <div class="title">SSE 事件</div>
            <el-switch v-model="showEvents" size="small" />
          </div>
          <div v-if="showEvents" class="card-pad">
            <div class="event-list">
              <div v-for="(e, i) in sseEvents.slice(-30)" :key="i" class="event-row">
                <span class="mono name">{{ e.name }}</span>
                <span class="mono t-faint">{{ e.id || '' }}</span>
              </div>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <HitlConfirmDialog
      v-model="hitlOpen"
      :calls="pendingCalls"
      :busy="confirming"
      :agent-id="store.agentId"
      :session-id="store.sessionId"
      @decide="onHitlDecide"
    />

    <el-drawer v-model="showHistory" title="原始 History JSON" size="520px">
      <JsonView :data="history" label="history" max-height="70vh" />
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-page {
  max-width: none;
  height: calc(100vh - var(--topbar-h));
  padding-bottom: 16px;
}
.chat-layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 280px;
  gap: 14px;
  min-height: 0;
  flex: 1;
}
.session-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.session-card {
  overflow: hidden;
}
.session-list {
  max-height: 320px;
  overflow: auto;
  padding: 8px;
}
.session-item {
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  border: 1px solid transparent;
}
.session-item:hover {
  background: var(--bg-hover);
}
.session-item.active {
  background: var(--brand-soft);
  border-color: color-mix(in srgb, var(--brand) 30%, var(--line));
}
.sid {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-1);
  word-break: break-all;
}
.meta {
  margin-top: 2px;
  font-size: 11px;
}
.compact {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}
.loading-hint {
  text-align: center;
  padding: 24px;
}
.composer {
  border-top: 1px solid var(--line);
  padding: 12px 14px 14px;
  background: var(--bg-surface);
}
.composer textarea {
  width: 100%;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  font: inherit;
  color: var(--ink-1);
  background: var(--bg-app);
  outline: none;
}
.composer textarea:focus {
  border-color: var(--brand);
  box-shadow: var(--shadow-focus);
}
.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.composer-bar .left,
.composer-bar .right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.chat-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow: auto;
}
.side-card .card-header {
  padding: 12px 14px;
}
.side-stats {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.side-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-2);
}
.side-row b {
  color: var(--ink-1);
  font-weight: 600;
}
.timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 220px;
  overflow: auto;
}
.tl-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.event-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}
.event-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
}
.event-row .name {
  color: var(--brand);
}

.hitl-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 12px;
  border: 2px solid color-mix(in srgb, var(--warn) 55%, var(--line));
  background: linear-gradient(90deg, var(--warn-soft), color-mix(in srgb, var(--warn-soft) 60%, white));
  box-shadow: 0 8px 24px rgba(217, 119, 6, 0.12);
  cursor: pointer;
  margin-bottom: 4px;
}
.banner-left {
  display: flex;
  gap: 12px;
  align-items: center;
}
.banner-title {
  font-weight: 700;
  color: var(--ink-1);
  font-size: 14px;
}
.banner-sub {
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.pulse-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--warn);
  box-shadow: 0 0 0 0 rgba(217, 119, 6, 0.55);
  animation: pulse 1.4s infinite;
  flex-shrink: 0;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(217, 119, 6, 0.55); }
  70% { box-shadow: 0 0 0 12px rgba(217, 119, 6, 0); }
  100% { box-shadow: 0 0 0 0 rgba(217, 119, 6, 0); }
}
.hitl-side {
  border-color: color-mix(in srgb, var(--warn) 45%, var(--line));
  background: var(--warn-soft);
}

@media (max-width: 1200px) {
  .chat-layout {
    grid-template-columns: 200px minmax(0, 1fr);
  }
  .chat-side {
    display: none;
  }
}
@media (max-width: 900px) {
  .chat-layout {
    grid-template-columns: 1fr;
  }
  .session-side {
    display: none;
  }
  .chat-page {
    height: auto;
  }
}
</style>
