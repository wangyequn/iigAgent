<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import {
  healthReady,
  replicaInfo,
  otelStatus,
  nacosStatus,
  lokiStatus,
  fsStatus,
  sandboxStatus,
} from '@/api'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const checks = ref([])
const raw = ref({})

async function load() {
  const entries = [
    { key: 'ready', label: '就绪 / Redis + PG', fn: healthReady },
    { key: 'replica', label: '副本信息', fn: replicaInfo },
    { key: 'otel', label: 'OTel', fn: otelStatus },
    { key: 'nacos', label: 'Nacos', fn: nacosStatus },
    { key: 'loki', label: 'Loki', fn: lokiStatus },
    { key: 'fs', label: '远程 FS', fn: fsStatus },
    { key: 'sandbox', label: 'Docker 沙箱', fn: sandboxStatus },
  ]
  checks.value = []
  raw.value = {}
  for (const e of entries) {
    try {
      const data = await e.fn()
      raw.value[e.key] = data
      const ok =
        data?.status === 'UP' ||
        data?.ready === true ||
        data?.enabled === true ||
        data?.status === 'ready' ||
        e.key !== 'ready'
      checks.value.push({ ...e, ok: e.key === 'ready' ? !!(data?.status === 'UP' || data?.ready) : true, data })
    } catch (err) {
      checks.value.push({ ...e, ok: false, error: err.message })
    }
  }
  await store.refreshHealth()
}

onMounted(load)
</script>

<template>
  <PageHeader title="环境与就绪" desc="检查 Redis、PG、观测组件与沙箱等运行时前置条件。">
    <template #actions>
      <el-button @click="load">重新检查</el-button>
    </template>
  </PageHeader>

  <div class="grid-3">
    <div v-for="c in checks" :key="c.key" class="card card-pad check">
      <div class="check-head">
        <div class="t-section">{{ c.label }}</div>
        <StatusTag :value="c.ok ? 'UP' : 'DOWN'" />
      </div>
      <div class="t-faint mono" style="margin-top:8px">
        {{ c.error || (c.data?.status || c.data?.mode || c.data?.source || c.data?.replica || 'OK') }}
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-header">
      <div class="title">原始响应</div>
    </div>
    <div class="card-pad">
      <JsonView :data="raw" label="health" max-height="360px" />
    </div>
  </div>
</template>

<style scoped>
.check-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}
</style>
