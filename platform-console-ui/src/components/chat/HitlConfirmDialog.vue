<script setup>
import { computed, ref, watch } from 'vue'
import JsonView from '@/components/ui/JsonView.vue'
import StatusTag from '@/components/ui/StatusTag.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  calls: { type: Array, default: () => [] },
  busy: { type: Boolean, default: false },
  agentId: { type: String, default: '' },
  sessionId: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'decide'])

const index = ref(0)
const note = ref('')
const acceptRule = ref(false)

watch(
  () => props.calls.length,
  () => {
    if (index.value >= props.calls.length) index.value = 0
  },
)

const current = computed(() => props.calls[index.value] || null)
const inputObj = computed(() => {
  const c = current.value
  if (!c) return {}
  if (c.input && typeof c.input === 'object') return c.input
  try {
    return JSON.parse(c.toolInput || '{}')
  } catch {
    return { raw: c.toolInput || '' }
  }
})

function decide(confirmed, always = false) {
  if (!current.value) return
  emit('decide', {
    call: current.value,
    confirmed,
    acceptRule: always || (confirmed && acceptRule.value),
    note: note.value || (confirmed ? 'ok' : 'reject'),
  })
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="hitl-dialog"
    width="560px"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    append-to-body
    @update:model-value="(v) => !v && close()"
  >
    <template #header>
      <div class="hitl-title">
        <div class="pulse-dot" />
        <div>
          <div class="title-text">需要你确认工具调用</div>
          <div class="sub-text">
            会话已暂停 · {{ agentId }} / {{ sessionId }}
            <span v-if="calls.length > 1"> · {{ index + 1 }} / {{ calls.length }}</span>
          </div>
        </div>
      </div>
    </template>

    <div v-if="current" class="hitl-body">
      <div class="tool-name-row">
        <div class="tool-badge">{{ current.toolName || 'tool' }}</div>
        <StatusTag value="WAITING_CONFIRM" />
      </div>

      <JsonView :data="inputObj" label="调用入参" max-height="220px" />

      <div v-if="current.suggestedRules?.length" class="suggest-box">
        <div class="suggest-title">建议规则（可一键永久允许）</div>
        <el-tag
          v-for="r in current.suggestedRules"
          :key="r.behavior + (r.ruleContent || '')"
          size="small"
          effect="plain"
          type="success"
        >
          {{ r.behavior }} · {{ r.ruleContent || '*' }}
        </el-tag>
      </div>

      <el-input v-model="note" type="textarea" :rows="2" placeholder="备注（可选）" />

      <el-checkbox v-model="acceptRule">批准后为该工具写入允许规则（不再询问）</el-checkbox>
    </div>

    <div v-if="calls.length > 1" class="pager">
      <el-button size="small" :disabled="index === 0" @click="index--">上一条</el-button>
      <el-button size="small" :disabled="index >= calls.length - 1" @click="index++">下一条</el-button>
    </div>

    <template #footer>
      <div class="hitl-actions">
        <el-button :disabled="busy" @click="close">稍后处理</el-button>
        <el-button type="danger" plain :disabled="busy" @click="decide(false)">拒绝</el-button>
        <el-button type="primary" :disabled="busy" :loading="busy" @click="decide(true)">
          批准并继续
        </el-button>
        <el-button type="success" :disabled="busy" :loading="busy" @click="decide(true, true)">
          批准 · 永久允许
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.hitl-title {
  display: flex;
  gap: 12px;
  align-items: center;
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
.title-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
}
.sub-text {
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.hitl-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tool-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-badge {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 700;
  color: var(--warn);
  background: var(--warn-soft);
  border: 1px solid color-mix(in srgb, var(--warn) 35%, var(--line));
  border-radius: 10px;
  padding: 8px 12px;
}
.suggest-box {
  border: 1px dashed color-mix(in srgb, var(--ok) 40%, var(--line));
  background: var(--ok-soft);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.suggest-title {
  width: 100%;
  font-size: 12px;
  font-weight: 600;
  color: var(--ok);
}
.pager {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
}
.hitl-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

:deep(.hitl-dialog .el-dialog) {
  border-radius: 16px !important;
  border: 2px solid color-mix(in srgb, var(--warn) 55%, var(--line));
  box-shadow: 0 24px 64px rgba(217, 119, 6, 0.18), var(--shadow-lg);
}
:deep(.el-dialog__header) {
  background: var(--warn-soft);
  border-bottom: 1px solid color-mix(in srgb, var(--warn) 25%, var(--line));
  margin-right: 0;
  padding: 16px 20px;
}
:deep(.el-dialog__body) {
  padding: 18px 20px;
}
:deep(.el-dialog__footer) {
  border-top: 1px solid var(--line);
  padding: 14px 20px;
  background: var(--bg-subtle);
}
</style>

<style>
/* dialog teleported to body — unscoped */
.hitl-dialog .el-dialog {
  border-radius: 16px !important;
  border: 2px solid #f59e0b;
  box-shadow: 0 24px 64px rgba(217, 119, 6, 0.18), 0 12px 40px rgba(15, 23, 42, 0.12);
  overflow: hidden;
}
.hitl-dialog .el-dialog__header {
  background: #fffbeb;
  border-bottom: 1px solid #fde68a;
  margin-right: 0;
  padding: 16px 20px;
}
.hitl-dialog .el-dialog__body {
  padding: 18px 20px;
}
.hitl-dialog .el-dialog__footer {
  border-top: 1px solid #e2e8f0;
  padding: 14px 20px;
  background: #f8fafc;
}
html.theme-dark .hitl-dialog .el-dialog__header {
  background: #451a03;
  border-bottom-color: #92400e;
}
html.theme-dark .hitl-dialog .el-dialog__footer {
  background: #1a2332;
  border-top-color: #2a3648;
}
</style>
