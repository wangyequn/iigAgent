<script setup>
import { computed } from 'vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'

const props = defineProps({
  call: { type: Object, required: true },
})
const emit = defineEmits(['approve', 'reject', 'allow-always'])

const input = computed(() => {
  if (props.call.input && typeof props.call.input === 'object') return props.call.input
  try {
    return JSON.parse(props.call.toolInput || props.call.content || '{}')
  } catch {
    return { raw: props.call.toolInput || props.call.content || '' }
  }
})
</script>

<template>
  <div class="tool-card" :class="`state-${(call.state || call.status || 'IDLE').toLowerCase()}`">
    <div class="tool-head">
      <div class="tool-icon">
        <el-icon><Tools /></el-icon>
      </div>
      <div class="tool-title">
        <div class="name mono">{{ call.toolName || call.tool || 'tool' }}</div>
        <div class="meta">
          <StatusTag :value="call.state || call.status || (call.pending ? 'WAITING_CONFIRM' : 'OK')" />
          <span v-if="call.durationMs" class="t-faint">{{ call.durationMs }}ms</span>
          <span v-if="call.toolCallId" class="t-faint mono">{{ String(call.toolCallId).slice(0, 8) }}</span>
        </div>
      </div>
    </div>

    <JsonView v-if="call.pending || call.requireConfirm" :data="input" label="调用入参" max-height="140px" />
    <div v-else-if="call.result || call.output" class="tool-result">
      <div class="t-faint" style="margin-bottom:4px">结果</div>
      <div class="result-text">{{ call.result || call.output }}</div>
    </div>

    <div v-if="call.pending || call.requireConfirm || call.suggestedRules?.length" class="tool-actions">
      <template v-if="call.pending || call.requireConfirm">
        <el-button size="small" type="success" @click="emit('approve')">批准</el-button>
        <el-button size="small" type="danger" plain @click="emit('reject')">拒绝</el-button>
        <el-button size="small" @click="emit('allow-always')">永久允许</el-button>
      </template>
      <div v-if="call.suggestedRules?.length" class="suggested">
        <span class="t-faint">建议规则：</span>
        <el-tag
          v-for="r in call.suggestedRules"
          :key="r.ruleContent + r.behavior"
          size="small"
          effect="plain"
        >
          {{ r.behavior }} · {{ r.ruleContent || '*' }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-card {
  border: 1.5px solid var(--line);
  border-radius: 12px;
  background: var(--bg-surface);
  overflow: hidden;
}
.tool-card.state-waiting_confirm,
.tool-card.state-asking {
  border-color: var(--warn);
  background: var(--warn-soft);
  box-shadow: 0 8px 28px rgba(217, 119, 6, 0.16);
  animation: tool-glow 1.6s ease-in-out infinite;
}
@keyframes tool-glow {
  0%, 100% { box-shadow: 0 8px 28px rgba(217, 119, 6, 0.16); }
  50% { box-shadow: 0 8px 32px rgba(217, 119, 6, 0.28); }
}
.tool-card.state-error,
.tool-card.state-failed {
  border-color: color-mix(in srgb, var(--danger) 45%, var(--line));
  background: var(--danger-soft);
}
.tool-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 14px 8px;
}
.tool-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.tool-card.state-waiting_confirm .tool-icon,
.tool-card.state-asking .tool-icon {
  background: var(--warn);
  color: #fff;
}
.tool-title { min-width: 0; flex: 1; }
.name { font-size: 14px; font-weight: 700; color: var(--ink-1); }
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  flex-wrap: wrap;
}
.tool-result {
  padding: 0 12px 10px;
}
.result-text {
  font-family: var(--font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  color: var(--ink-1);
  background: var(--bg-subtle);
  border-radius: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
}
.tool-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 0 14px 14px;
}
.tool-actions .el-button--success {
  font-weight: 600;
}
.suggested {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  width: 100%;
  margin-top: 4px;
}
</style>
