import { reactive, watch } from 'vue'

/**
 * 控制台智能机器人 ↔ 页面 深度交互总线。
 *
 * 页面侧：
 *   botAsk('帮我看看用量')
 *   botSetContext({ title: 'Agent 列表', data: {...} })
 *   botSuggest([{ type: 'navigate', path: '/skills', text: '打开技能' }])
 *
 * 机器人侧（ConsoleBot）通过 open/pendingAsk/context/suggestions 响应。
 * 注意：consume 系列不再 bump tick，避免 watch 自触发死循环。
 */
export const botBus = reactive({
  /** 待发送提问（由页面触发） */
  pendingAsk: null,
  /** 附加上下文（注入下一轮消息） */
  context: null,
  /** 建议芯片 */
  suggestions: [],
  /** 面板开关 —— 唯一开关状态源 */
  open: false,
})

function setOpen(v) {
  botBus.open = !!v
}

export function botOpen() {
  setOpen(true)
}

export function botClose() {
  setOpen(false)
}

export function botToggle() {
  setOpen(!botBus.open)
}

/** 页面请求机器人回答；会打开面板 */
export function botAsk(text, context) {
  if (context) botBus.context = context
  botBus.pendingAsk = text
  setOpen(true)
}

/** 注入页面上下文（不自动提问） */
export function botSetContext(context) {
  botBus.context = context || null
}

/** 设置快捷芯片 */
export function botSuggest(items) {
  botBus.suggestions = Array.isArray(items) ? items : []
}

/** 取出并清空一次性提问（不改 open、不触发额外变更） */
export function botConsumeAsk() {
  const t = botBus.pendingAsk
  botBus.pendingAsk = null
  return t
}

export function botConsumeContext() {
  const c = botBus.context
  botBus.context = null
  return c
}
