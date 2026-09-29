<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  data: { type: [Object, Array, String], default: null },
  label: { type: String, default: '' },
  maxHeight: { type: String, default: '240px' },
})

const open = ref(true)

const text = computed(() => {
  if (typeof props.data === 'string') return props.data
  try {
    return JSON.stringify(props.data, null, 2)
  } catch {
    return String(props.data)
  }
})

async function copy() {
  try {
    await navigator.clipboard.writeText(text.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<template>
  <div class="json-view">
    <div class="json-bar">
      <button type="button" class="toggle" @click="open = !open">
        <el-icon><component :is="open ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
        <span>{{ label || 'JSON' }}</span>
      </button>
      <button type="button" class="copy" @click="copy">复制</button>
    </div>
    <pre v-if="open" class="code" :style="{ maxHeight }">{{ text }}</pre>
  </div>
</template>

<style scoped>
.json-view {
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--bg-subtle);
}
.json-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--line);
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.copy {
  border: 0;
  background: transparent;
  color: var(--brand);
  font-size: 12px;
  cursor: pointer;
}
.code {
  border: 0;
  border-radius: 0;
  background: transparent;
}
</style>
