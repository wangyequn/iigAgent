<script setup>
import { Handle, Position } from '@vue-flow/core'

const props = defineProps({
  id: { type: String, required: true },
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false },
})
defineEmits(['select'])

const meta = {
  ECHO: { color: '#64748b', label: 'ECHO' },
  AGENT: { color: '#4f46e5', label: 'AGENT' },
  HUMAN_GATE: { color: '#d97706', label: 'HUMAN_GATE' },
}
</script>

<template>
  <div
    class="flow-node"
    :class="{ selected }"
    :style="{ borderColor: meta[data.type]?.color }"
  >
    <Handle type="target" :position="Position.Left" />
    <div class="type" :style="{ color: meta[data.type]?.color }">
      {{ meta[data.type]?.label || data.type }}
    </div>
    <div class="id mono">{{ id }}</div>
    <div class="prev">
      {{
        data.type === 'AGENT'
          ? data.agentId || 'agent'
          : data.type === 'HUMAN_GATE'
            ? data.prompt || '确认'
            : data.input || '—'
      }}
    </div>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<style scoped>
.flow-node {
  width: 188px;
  min-height: 72px;
  border: 1.5px solid var(--line);
  border-radius: 12px;
  background: var(--bg-surface);
  padding: 10px 12px;
  box-shadow: var(--shadow-sm);
  font-family: var(--font-sans);
}
.flow-node.selected {
  border-color: var(--brand);
  box-shadow: var(--shadow-focus), var(--shadow-md);
}
.type {
  font-size: 11px;
  font-weight: 700;
}
.id {
  font-size: 12px;
  margin-top: 2px;
  color: var(--ink-1);
}
.prev {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
