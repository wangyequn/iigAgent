<script setup>
import { computed } from 'vue'

const props = defineProps({
  value: { type: String, default: '' },
})

const tone = computed(() => {
  const raw = props.value
  const v = String(raw === true || raw === 1 ? 'OK' : raw === false || raw === 0 ? 'DOWN' : raw || '').toUpperCase()
  if (['UP', 'READY', 'RUNNING', 'APPROVED', 'ALLOW', 'SUCCESS', 'HEALTHY', 'OK', 'ONLINE', 'DONE', 'COMPLETED'].includes(v)) return 'ok'
  if (['PENDING', 'WAITING', 'WAITING_CONFIRM', 'ASK', 'DRAFT', 'WARN', 'UNHEALTHY', 'RUNNING_CONFIRM'].includes(v)) return 'warn'
  if (['DOWN', 'FAILED', 'DENY', 'ERROR', 'REJECTED', 'CANCELLED', 'TIMEOUT', 'OFFLINE'].includes(v)) return 'danger'
  if (['RELEASE', 'ACTIVE', 'VISIBLE', 'DEFAULT'].includes(v)) return 'brand'
  return 'info'
})
</script>

<template>
  <el-tag :type="tone === 'brand' ? 'primary' : tone" size="small" effect="light" round>
    <slot>{{ value === true ? 'OK' : value === false ? 'DOWN' : value || '—' }}</slot>
  </el-tag>
</template>
