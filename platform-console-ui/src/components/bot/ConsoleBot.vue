<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { streamChat, clearContext, listSessionApprovals, confirmSession } from '@/api'
import { useAppStore } from '@/stores/app'
import botAvatar from '@/assets/bot-avatar.png'
import {
  botBus,
  botAsk,
  botConsumeAsk,
  botConsumeContext,
  botClose,
  botOpen,
} from '@/stores/botBus'

const store = useAppStore()
const route = useRoute()
const router = useRouter()

const BOT_AGENT = 'console-bot'
const BOT_SESSION_KEY = 'amp.consoleBot.sessionId'

/** 面板开关 —— 以 botBus.open 为唯一状态源，避免双源同步竞态 */
const open = computed(() => botBus.open)
const input = ref('')
const sending = ref(false)
const streaming = ref(false)
const errorText = ref('')
const messages = ref([])
const scrollBox = ref(null)
const inputBox = ref(null)
const chipOptions = ref([])
const askCard = ref(null)
const pendingHidl = ref([])
const drag = ref({ active: false, moved: false, x: 0, y: 0, dx: 0, dy: 0 })
const floating = ref({ x: 0, y: 0 })

function botSessionId() {
  let id = localStorage.getItem(BOT_SESSION_KEY)
  if (!id) {
    id = 'consolebot_' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem(BOT_SESSION_KEY, id)
  }
  return id
}

const sessionId = computed(() => botSessionId())
const userId = computed(() => store.userId || 'console-user')

const quickPrompts = [
  '系统状态怎么样？',
  '有哪些 Agent？',
  '帮我看看用量',
  '打开权限页',
  '平台最佳实践是什么？',
  '最近有什么审计？',
]

/* ───────── 消息模型 ───────── */
function pushUser(text) {
  messages.value.push({ id: uid(), role: 'user', text, ts: Date.now() })
  scrollBottom()
}

function pushAssistant() {
  const m = {
    id: uid(),
    role: 'assistant',
    text: '',
    thinking: '',
    tools: [],
    chips: [],
    ask: null,
    ts: Date.now(),
  }
  messages.value.push(m)
  return m
}

function uid() {
  return Math.random().toString(36).slice(2, 10)
}

function scrollBottom() {
  nextTick(() => {
    const el = scrollBox.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/* ───────── UI 深度交互：执行 ui.actions ───────── */
function applyUiActions(actions) {
  if (!Array.isArray(actions)) return
  for (const a of actions) {
    const type = a.type
    try {
      if (type === 'navigate' && a.path) {
        const q = a.query && typeof a.query === 'object' ? a.query : undefined
        router.push({ path: a.path, query: q })
        if (a.label) ElMessage.info(a.label)
      } else if (type === 'toast') {
        const level = (a.level || 'info').toLowerCase()
        if (level === 'success') ElMessage.success(a.message || '')
        else if (level === 'warning') ElMessage.warning(a.message || '')
        else if (level === 'error') ElMessage.error(a.message || '')
        else ElMessage.info(a.message || '')
      } else if (type === 'focus') {
        focusEntity(a.entityType, a.entityId)
      } else if (type === 'askUser') {
        askCard.value = {
          prompt: a.prompt,
          options: Array.isArray(a.options) ? a.options : [],
        }
      } else if (type === 'chips' && Array.isArray(a.items)) {
        chipOptions.value = a.items.map((it) => ({
          text: it.text || it.label || '操作',
          path: it.path || null,
          ask: it.type === 'chipAsk' || (!it.path && it.type !== 'chipNavigate'),
        }))
      } else if (type === 'chipNavigate' && a.path) {
        chipOptions.value = [...chipOptions.value, { text: a.text || '打开', path: a.path, ask: false }]
      } else if (type === 'chipAsk') {
        chipOptions.value = [...chipOptions.value, { text: a.text || '提问', path: null, ask: true }]
      }
    } catch (e) {
      console.warn('ui action failed', a, e)
    }
  }
  scrollBottom()
}

function focusEntity(entityType, entityId) {
  const t = String(entityType || '').toLowerCase()
  const id = entityId
  if (t === 'agent') {
    router.push({ path: `/agents/${id}` })
  } else if (t === 'skill') {
    router.push({ path: '/skills', query: { q: id } })
  } else if (t === 'knowledge') {
    router.push({ path: '/workbench/knowledge', query: { q: id } })
  } else if (t === 'workflow') {
    router.push({ path: '/workflows', query: { q: id } })
  } else if (t === 'team') {
    router.push({ path: '/teams', query: { q: id } })
  } else if (t === 'session') {
    router.push({ path: '/ops/sessions', query: { sessionId: id } })
  } else {
    ElMessage.info(`已定位 ${entityType}:${id}`)
  }
}

/* ───────── 流式对话 ───────── */
function parseToolResult(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function handleEvent(name, data, msg) {
  const type = name
  let obj = null
  try {
    obj = data ? JSON.parse(data) : null
  } catch {
    obj = null
  }

  if (type === 'TEXT_BLOCK_DELTA') {
    const delta = obj?.delta ?? obj?.text ?? obj?.content ?? ''
    if (delta) msg.text += delta
    if (obj?.generateReason) msg.generateReason = obj.generateReason
    scrollBottom()
  } else if (type === 'THINKING_BLOCK_DELTA') {
    const delta = obj?.delta ?? obj?.text ?? ''
    if (delta) msg.thinking += delta
  } else if (type === 'TOOL_RESULT_END') {
    const toolName = obj?.toolCallName || obj?.toolName
    const toolCallId = obj?.toolCallId
    const content = obj?.content ?? obj?.result ?? obj?.output
    let parsed = null
    if (typeof content === 'string') {
      parsed = parseToolResult(content)
    } else if (content && typeof content === 'object') {
      parsed = content
    }
    const entry = {
      toolCallId,
      toolName,
      state: obj?.state,
      raw: typeof content === 'string' ? content : JSON.stringify(content),
      data: parsed?.data,
      ok: parsed?.ok,
      error: parsed?.error,
    }
    const idx = msg.tools.findIndex(
      (x) => (toolCallId && x.toolCallId === toolCallId) || (!toolCallId && x.toolName === toolName),
    )
    if (idx >= 0) msg.tools[idx] = { ...msg.tools[idx], ...entry }
    else msg.tools.push(entry)

    if (parsed?.ui?.actions) applyUiActions(parsed.ui.actions)
    if (Array.isArray(parsed?.data?.suggested)) {
      chipOptions.value = parsed.data.suggested.map((s) => ({
        text: s.text,
        path: s.path || null,
        ask: s.type !== 'navigate',
      }))
    }
    scrollBottom()
  } else if (type === 'TOOL_CALL_START' || type === 'TOOL_RESULT_START') {
    const toolName = obj?.toolCallName || obj?.toolName
    const toolCallId = obj?.toolCallId
    if (toolName && !msg.tools.some((x) => x.toolCallId === toolCallId || x.toolName === toolName)) {
      msg.tools.push({ toolCallId, toolName, state: 'RUNNING', raw: '', data: null })
    }
    scrollBottom()
  } else if (type === 'HITL_CONFIRM_REQUIRED' || type === 'REQUIRE_USER_CONFIRM') {
    void loadApprovals(msg)
  } else if (type === 'ERROR') {
    errorText.value = obj?.message || obj?.error || '对话出错'
    msg.text = msg.text || `（出错：${errorText.value}）`
  }
}

async function loadApprovals(msg) {
  try {
    const data = await listSessionApprovals(BOT_AGENT, sessionId.value, userId.value)
    const items = data?.items || data?.approvals || []
    pendingHidl.value = items.filter((x) => !x.status || x.status === 'PENDING' || x.status === 'WAITING')
    if (msg && pendingHidl.value.length) {
      msg.text += (msg.text ? '\n\n' : '') + '⚠️ 有工具调用需要你确认（见下方审批卡片）。'
    }
    scrollBottom()
  } catch {
    /* ignore */
  }
}

async function confirmOne(item, confirmed) {
  try {
    await confirmSession(BOT_AGENT, sessionId.value, {
      userId: userId.value,
      confirmed,
      toolCalls: [
        {
          toolCallId: item.toolCallId || item.id,
          toolName: item.toolName,
          input: typeof item.toolInput === 'string' ? JSON.parse(item.toolInput || '{}') : item.toolInput || {},
          acceptRule: false,
        },
      ],
    })
    ElMessage.success(confirmed ? '已确认' : '已拒绝')
    pendingHidl.value = pendingHidl.value.filter((x) => (x.toolCallId || x.id) !== (item.toolCallId || item.id))
    // 确认后继续追问，让机器人恢复
    await send('请继续', { silentUser: true })
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

async function send(text, opts = {}) {
  const content = (text ?? input.value ?? '').trim()
  if (!content || sending.value) return
  errorText.value = ''
  askCard.value = null

  let full = content
  const ctx = botConsumeContext()
  if (ctx) {
    const page = `[当前页面] ${route.path}${route.fullPath !== route.path ? ' ' + route.fullPath : ''}`
    const extra = ctx.title ? `\n[页面标题] ${ctx.title}` : ''
    const data = ctx.data ? `\n[页面数据] ${JSON.stringify(ctx.data).slice(0, 800)}` : ''
    full = `${page}${extra}${data}\n\n${content}`
  }

  if (!opts.silentUser) pushUser(content)

  input.value = ''
  const msg = pushAssistant()
  sending.value = true
  streaming.value = true
  chipOptions.value = []
  scrollBottom()

  try {
    const body = {
      userId: userId.value,
      sessionId: sessionId.value,
      message: full,
    }
    await streamChat(BOT_AGENT, body, (name, data) => handleEvent(name, data, msg))
  } catch (e) {
    errorText.value = e?.body || e?.message || '发送失败'
    try {
      const errObj = JSON.parse(e.body || '{}')
      if (errObj.code === 'HITL_CONFIRM_REQUIRED') {
        msg.text += (msg.text ? '\n\n' : '') + '需要人工确认后才能继续。'
        await loadApprovals(msg)
      } else {
        msg.text += (msg.text ? '\n\n' : '') + `（${errObj.message || errorText.value}）`
      }
    } catch {
      msg.text += (msg.text ? '\n\n' : '') + `（${errorText.value}）`
    }
  } finally {
    sending.value = false
    streaming.value = false
    scrollBottom()
    if (!msg.text && !msg.tools.length) {
      msg.text = '（本轮没有产生回复，可换个说法或再试一次）'
    }
  }
}

function onChip(c) {
  if (c.path) {
    router.push(c.path)
    return
  }
  send(c.text)
}

function onAskOption(opt) {
  askCard.value = null
  send(opt)
}

function clearChat() {
  messages.value = []
  chipOptions.value = []
  askCard.value = null
  pendingHidl.value = []
}

async function resetSession() {
  try {
    await clearContext(BOT_AGENT, sessionId.value, userId.value)
  } catch {
    /* optional */
  }
  const next = 'consolebot_' + Math.random().toString(36).slice(2, 10)
  localStorage.setItem(BOT_SESSION_KEY, next)
  clearChat()
  ElMessage.success('已开启新会话')
  greet()
}

function greet() {
  if (messages.value.length) return
  messages.value.push({
    id: uid(),
    role: 'assistant',
    text: '你好，我是控制台智能机器人「小助」。\n\n我可以帮你：查 Agent / 权限 / 知识库 / 编排 / 用量 / 审计，也能直接把界面跳到对应页面。试试下面的问题，或直接输入。',
    ts: Date.now(),
    tools: [],
    chips: [],
    ask: null,
  })
}

function toggleOpen() {
  // 拖拽后不触发开关
  if (drag.value.moved) return
  if (botBus.open) {
    botClose()
  } else {
    botOpen()
    nextTick(() => inputBox.value?.focus())
  }
}

/* ───────── 拖拽（位移 > 6px 才算拖拽，否则当点击） ───────── */
const DRAG_THRESHOLD = 6

function onDragStart(e) {
  // 只在浮动球上启动拖拽；面板内部不拖
  const point = e.clientX != null ? e : e.touches?.[0]
  if (!point) return
  drag.value = {
    active: true,
    moved: false,
    x: point.clientX,
    y: point.clientY,
    dx: floating.value.x,
    dy: floating.value.y,
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
  window.addEventListener('touchmove', onDragMove, { passive: false })
  window.addEventListener('touchend', onDragEnd)
}

function onDragMove(e) {
  if (!drag.value.active) return
  const point = e.clientX != null ? e : e.touches?.[0]
  if (!point) return
  const dx = point.clientX - drag.value.x
  const dy = point.clientY - drag.value.y
  if (!drag.value.moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
  drag.value.moved = true
  floating.value = {
    x: drag.value.dx + dx,
    y: drag.value.dy + dy,
  }
  if (e.cancelable) e.preventDefault()
}

function onDragEnd() {
  drag.value.active = false
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
  window.removeEventListener('touchmove', onDragMove)
  window.removeEventListener('touchend', onDragEnd)
  // 下一帧再清 moved，保证同一次手势的 click 能读到
  setTimeout(() => {
    drag.value.moved = false
  }, 0)
}

/* ───────── 订阅 botBus ───────── */
watch(
  () => botBus.pendingAsk,
  (ask) => {
    if (!ask) return
    const text = botConsumeAsk()
    if (text) nextTick(() => send(text))
  },
)

watch(
  () => botBus.suggestions,
  (list) => {
    if (!list?.length) return
    chipOptions.value = list.map((s) => ({
      text: s.text || s.label,
      path: s.path || null,
      ask: !s.path,
    }))
  },
  { deep: true },
)

watch(open, (v) => {
  if (v) nextTick(() => inputBox.value?.focus())
})

function onGlobalAsk(e) {
  const detail = e?.detail || e
  const text = detail?.text || detail?.message
  if (text) botAsk(text, detail?.context)
}

onMounted(() => {
  greet()
  window.addEventListener('amp-bot-ask', onGlobalAsk)
  // 页面可派发 CustomEvent('amp-bot-ask', { detail: { text } })
  window.ampBot = {
    ask: (text, context) => botAsk(text, context),
    open: () => {
      botBus.open = true
    },
  }
})

onUnmounted(() => {
  window.removeEventListener('amp-bot-ask', onGlobalAsk)
  delete window.ampBot
})

const fabStyle = computed(() => ({
  right: `${22 - floating.value.x}px`,
  bottom: `${22 - floating.value.y}px`,
  transform: 'none',
}))

const panelStyle = computed(() => ({
  right: `${22 - floating.value.x}px`,
  bottom: `${90 - floating.value.y}px`,
  transform: 'none',
}))

function toolSummary(t) {
  if (t.error) return t.error
  if (t.data == null) return t.state || ''
  const d = t.data
  if (Array.isArray(d.agents)) return `${d.agents.length} 个 Agent`
  if (Array.isArray(d.skills)) return `${d.skills.length} 个技能`
  if (Array.isArray(d.documents)) return `${d.documents.length} 篇文档`
  if (Array.isArray(d.workflows)) return `${d.workflows.length} 个 Workflow`
  if (d.navigatedTo) return `跳转 ${d.navigatedTo}`
  if (d.ready !== undefined) return `ready=${d.ready}`
  return ''
}
</script>

<template>
  <!-- 浮动球 -->
  <div
    class="bot-fab"
    :class="{ open, dragging: drag.active && drag.moved }"
    :style="fabStyle"
    title="控制台智能机器人"
    @click.stop="toggleOpen"
    @mousedown="onDragStart"
    @touchstart.passive="onDragStart"
  >
    <div class="bot-fab-drag">
      <img v-if="!open" class="bot-avatar-img" :src="botAvatar" alt="小助" draggable="false" />
      <svg v-else viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" d="M6 6l12 12M18 6L6 18" />
      </svg>
    </div>
    <span v-if="!open" class="bot-fab-pulse" />
  </div>

  <!-- 面板 -->
  <transition name="bot-panel">
    <div v-if="open" class="bot-panel" :style="panelStyle" @mousedown.stop>
      <header class="bot-hd">
        <div class="bot-title">
          <img class="bot-hd-avatar" :src="botAvatar" alt="小助" draggable="false" />
          <div>
            <div class="bot-name">小助 · 控制台机器人</div>
            <div class="bot-sub mono">{{ BOT_AGENT }} · {{ sessionId.slice(0, 12) }}</div>
          </div>
        </div>
        <div class="bot-acts">
          <el-button size="small" text @click="resetSession" title="新会话">新会话</el-button>
          <el-button size="small" text @click="clearChat" title="清屏">清屏</el-button>
        </div>
      </header>

      <div ref="scrollBox" class="bot-body">
        <div v-for="m in messages" :key="m.id" class="bot-msg" :class="m.role">
          <div class="bubble">
            <div v-if="m.thinking" class="thinking t-faint">{{ m.thinking }}</div>
            <div class="text" v-text="m.text" />
            <div v-if="m.tools?.length" class="tools">
              <div v-for="t in m.tools" :key="t.toolCallId || t.toolName" class="tool-row">
                <span class="tool-name mono">{{ t.toolName }}</span>
                <span class="tool-sum t-faint">{{ toolSummary(t) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="pendingHidl.length" class="hidl-card">
          <div class="hidl-title">需要确认</div>
          <div v-for="p in pendingHidl" :key="p.toolCallId || p.id" class="hidl-item">
            <div class="mono">{{ p.toolName }}</div>
            <div class="t-faint" style="font-size: 12px; word-break: break-all">
              {{ p.toolInput || p.input || '' }}
            </div>
            <div class="hidl-btns">
              <el-button size="small" type="primary" @click="confirmOne(p, true)">确认</el-button>
              <el-button size="small" @click="confirmOne(p, false)">拒绝</el-button>
            </div>
          </div>
        </div>

        <div v-if="askCard" class="ask-card">
          <div class="ask-prompt">{{ askCard.prompt }}</div>
          <div class="ask-opts">
            <el-button
              v-for="o in askCard.options"
              :key="o"
              size="small"
              round
              @click="onAskOption(o)"
            >
              {{ o }}
            </el-button>
          </div>
        </div>

        <div v-if="errorText" class="bot-error">{{ errorText }}</div>
      </div>

      <div v-if="chipOptions.length || quickPrompts.length" class="bot-chips">
        <el-tag
          v-for="c in chipOptions.length ? chipOptions : quickPrompts.map((t) => ({ text: t, path: null, ask: true }))"
          :key="c.text"
          size="small"
          class="chip"
          effect="plain"
          @click="onChip(c)"
        >
          {{ c.text }}
        </el-tag>
      </div>

      <footer class="bot-ft">
        <el-input
          ref="inputBox"
          v-model="input"
          type="textarea"
          :rows="2"
          :disabled="sending"
          placeholder="问问小助…（Enter 发送，Shift+Enter 换行）"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="bot-ft-row">
          <span class="t-faint" style="font-size: 11px">
            {{ streaming ? '生成中…' : '可导航页面 / 查询平台 / 知识检索' }}
          </span>
          <el-button type="primary" size="small" :loading="sending" @click="send()">发送</el-button>
        </div>
      </footer>
    </div>
  </transition>
</template>

<style scoped>
.bot-fab {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: var(--z-modal, 3000);
  width: 56px;
  height: 56px;
  border-radius: var(--radius-full, 999px);
  background: var(--brand, #4f46e5);
  color: var(--ink-inverse, #fff);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: var(--shadow-lg, 0 12px 40px rgba(15, 23, 42, 0.12));
  overflow: hidden;
  border: 1px solid var(--line, #e2e8f0);
  user-select: none;
  touch-action: none;
}
.bot-fab:hover {
  background: var(--brand-hover, #4338ca);
  filter: none;
}
.bot-fab.open {
  background: var(--bg-inverse, #0f172a);
}
.bot-fab.dragging {
  box-shadow: var(--shadow-lg);
  filter: none;
}
.bot-fab-drag {
  display: flex;
  align-items: center;
  justify-content: center;
}
.bot-avatar-img {
  width: 52px;
  height: 52px;
  border-radius: var(--radius-full, 999px);
  object-fit: cover;
  pointer-events: none;
  user-select: none;
}
.bot-hd-avatar {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md, 10px);
  object-fit: cover;
  flex-shrink: 0;
  border: 1px solid var(--line, #e2e8f0);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(15, 23, 42, 0.05));
  user-select: none;
}
.bot-fab-pulse {
  position: absolute;
  inset: 0;
  border-radius: var(--radius-full, 999px);
  border: 2px solid var(--brand-ring, rgba(79, 70, 229, 0.28));
  animation: botPulse 2.4s ease-out infinite;
  pointer-events: none;
}
@keyframes botPulse {
  0% {
    transform: scale(1);
    opacity: 0.85;
  }
  100% {
    transform: scale(1.55);
    opacity: 0;
  }
}

.bot-panel {
  position: fixed;
  right: 22px;
  bottom: 90px;
  width: min(400px, calc(100vw - 24px));
  height: min(560px, calc(100vh - 120px));
  background: var(--bg-surface, #ffffff);
  color: var(--ink-1, #0f172a);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: var(--radius-lg, 14px);
  box-shadow: var(--shadow-lg, 0 12px 40px rgba(15, 23, 42, 0.12));
  display: flex;
  flex-direction: column;
  z-index: calc(var(--z-modal, 3000) + 1);
  overflow: hidden;
  font-family: var(--font-sans);
}

.bot-panel-enter-active,
.bot-panel-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.bot-panel-enter-from,
.bot-panel-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

.bot-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line, #e2e8f0);
  background: var(--bg-surface, #fff);
}
.bot-title {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}
.bot-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-full, 999px);
  background: var(--ok, #059669);
  box-shadow: 0 0 0 3px var(--ok-soft, #ecfdf5);
}
.bot-name {
  font-size: var(--text-md, 14px);
  font-weight: 650;
  color: var(--ink-1, #0f172a);
  letter-spacing: -0.01em;
}
.bot-sub {
  font-size: var(--text-2xs, 11px);
  color: var(--ink-3, #94a3b8);
}

.bot-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4, 16px);
  display: flex;
  flex-direction: column;
  gap: var(--space-3, 12px);
  background: var(--bg-app, #f4f6fa);
}

.bot-msg {
  display: flex;
}
.bot-msg.user {
  justify-content: flex-end;
}
.bot-msg .bubble {
  max-width: 88%;
  padding: 10px 12px;
  border-radius: var(--radius-md, 10px);
  font-size: var(--text-sm, 13px);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.bot-msg.user .bubble {
  background: var(--brand, #4f46e5);
  color: #fff;
  border-bottom-right-radius: var(--radius-sm, 6px);
}
.bot-msg.assistant .bubble {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--line, #e2e8f0);
  color: var(--ink-1, #0f172a);
  border-bottom-left-radius: var(--radius-sm, 6px);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(15, 23, 42, 0.05));
}
.thinking {
  font-size: var(--text-2xs, 11px);
  margin-bottom: 6px;
  color: var(--ink-3, #94a3b8);
  white-space: pre-wrap;
}

.tools {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tool-row {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: var(--text-2xs, 11px);
  padding: 4px 8px;
  background: var(--bg-subtle, #f0f3f8);
  border-radius: var(--radius-sm, 6px);
}
.tool-name {
  color: var(--brand, #4f46e5);
  font-family: var(--font-mono);
}
.tool-sum {
  color: var(--ink-3, #94a3b8);
}

.hidl-card,
.ask-card {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: var(--radius-md, 10px);
  padding: 12px;
  box-shadow: var(--shadow-sm);
}
.hidl-title,
.ask-prompt {
  font-size: var(--text-sm, 13px);
  font-weight: 650;
  color: var(--ink-1, #0f172a);
  margin-bottom: 8px;
}
.hidl-item {
  margin-bottom: 8px;
  font-family: var(--font-mono);
  font-size: var(--text-2xs, 11px);
  color: var(--ink-2, #475569);
}
.hidl-btns {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.ask-opts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.bot-error {
  color: var(--danger, #dc2626);
  font-size: var(--text-xs, 12px);
  padding: 8px 10px;
  background: var(--danger-soft, #fef2f2);
  border: 1px solid color-mix(in srgb, var(--danger, #dc2626) 20%, transparent);
  border-radius: var(--radius-md, 10px);
}

.bot-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 12px;
  border-top: 1px solid var(--line, #e2e8f0);
  background: var(--bg-surface, #fff);
  max-height: 76px;
  overflow-y: auto;
}
.chip {
  cursor: pointer;
  user-select: none;
}
.chip:hover {
  border-color: var(--brand, #4f46e5) !important;
  color: var(--brand, #4f46e5) !important;
  background: var(--brand-softer, #f5f7ff) !important;
}

.bot-ft {
  padding: 10px 12px 12px;
  border-top: 1px solid var(--line, #e2e8f0);
  background: var(--bg-surface, #fff);
}
.bot-ft :deep(.el-textarea__inner) {
  background: var(--bg-app, #f4f6fa);
  color: var(--ink-1, #0f172a);
  border-color: var(--line, #e2e8f0);
  border-radius: var(--radius-md, 10px);
}
.bot-ft :deep(.el-textarea__inner:focus) {
  border-color: var(--brand, #4f46e5);
  box-shadow: var(--shadow-focus, 0 0 0 3px var(--brand-ring));
}
.bot-ft-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.t-faint {
  color: var(--ink-3, #94a3b8);
}
.mono {
  font-family: var(--font-mono);
}
</style>
