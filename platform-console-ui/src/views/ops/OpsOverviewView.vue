<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  metricsSummary,
  listSessionApprovals,
  getQuotas,
  listExternalAgents,
  otelStatus,
  nacosStatus,
  replicaInfo,
  lokiStatus,
  fsStatus,
  sandboxStatus,
} from '@/api'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const metrics = ref(null)
const quota = ref(null)
const pendingCount = ref(0)
const externals = ref([])
const stack = ref({ otel: null, nacos: null, replica: null, loki: null, fs: null, sandbox: null })
const usageChart = ref(null)
const latencyChart = ref(null)
let usageInst = null
let latencyInst = null

function renderCharts() {
  if (usageChart.value) {
    usageInst = usageInst || echarts.init(usageChart.value)
    const tokens = metrics.value?.tokens || {}
    usageInst.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
      series: [
        {
          type: 'pie',
          radius: ['42%', '68%'],
          label: { show: false },
          data: [
            { name: 'Prompt', value: tokens.prompt || 0, itemStyle: { color: '#4f46e5' } },
            { name: 'Completion', value: tokens.completion || 0, itemStyle: { color: '#818cf8' } },
          ],
        },
      ],
    })
  }
  if (latencyChart.value) {
    latencyInst = latencyInst || echarts.init(latencyChart.value)
    const lat = metrics.value?.latency || {}
    latencyInst.setOption({
      backgroundColor: 'transparent',
      tooltip: {},
      xAxis: {
        type: 'category',
        data: ['p50', 'p90', 'p95', 'p99'],
        axisLine: { lineStyle: { color: '#cbd5e1' } },
        axisLabel: { color: '#64748b' },
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        splitLine: { lineStyle: { color: '#e2e8f0' } },
        axisLabel: { color: '#64748b' },
      },
      series: [
        {
          type: 'bar',
          barWidth: 28,
          itemStyle: { color: '#4f46e5', borderRadius: [8, 8, 0, 0] },
          data: [lat.p50Ms || 0, lat.p90Ms || 0, lat.p95Ms || 0, lat.p99Ms || 0],
        },
      ],
    })
  }
}

async function load() {
  try {
    metrics.value = await metricsSummary()
  } catch {
    metrics.value = null
  }
  try {
    const q = await getQuotas()
    quota.value = q.quota || q.summary || q
  } catch {
    quota.value = null
  }
  try {
    const inbox = await listInboxSafe()
    pendingCount.value = inbox.length
  } catch {
    pendingCount.value = 0
  }
  try {
    const ex = await listExternalAgents()
    externals.value = ex.externalAgents || []
  } catch {
    externals.value = []
  }
  try {
    const [otel, nacos, replica, loki, fs, sandbox] = await Promise.all([
      otelStatus().catch(() => null),
      nacosStatus().catch(() => null),
      replicaInfo().catch(() => null),
      lokiStatus().catch(() => null),
      fsStatus().catch(() => null),
      sandboxStatus().catch(() => null),
    ])
    stack.value = { otel, nacos, replica, loki, fs, sandbox }
  } catch {
    /* ignore */
  }
  renderCharts()
}

async function listInboxSafe() {
  try {
    const { listInbox } = await import('@/api')
    const data = await listInbox()
    return (data.items || data.approvals || []).filter((x) => !x.status || x.status === 'PENDING')
  } catch {
    return []
  }
}

onMounted(async () => {
  await load()
  window.addEventListener('resize', () => {
    usageInst?.resize()
    latencyInst?.resize()
  })
})

onUnmounted(() => {
  usageInst?.dispose()
  latencyInst?.dispose()
})
</script>

<template>
  <PageHeader title="运行概览" desc="平台健康、吞吐、延迟、配额与待办一览。点击卡片可下钻到对应运营页。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-4">
    <StatCard
      label="Chat 请求"
      :value="metrics?.qpsHints?.chatRequests ?? 0"
      hint="累计 / 会话内"
      icon="ChatDotRound"
    />
    <StatCard
      label="延迟 P95"
      :value="Math.round(metrics?.latency?.p95Ms ?? 0)"
      hint="毫秒"
      tone="brand"
      icon="Timer"
    />
    <StatCard
      label="Tokens"
      :value="(metrics?.tokens?.prompt || 0) + (metrics?.tokens?.completion || 0)"
      hint="prompt + completion"
      icon="Coin"
    />
    <StatCard
      label="待审批"
      :value="pendingCount"
      :hint="pendingCount ? '需要人工处理' : '一切正常'"
      :tone="pendingCount ? 'warn' : 'ok'"
      icon="Bell"
    />
  </div>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">Token 构成</div>
      </div>
      <div class="card-pad">
        <div ref="usageChart" class="chart" />
      </div>
    </div>
    <div class="card">
      <div class="card-header">
        <div class="title">延迟分位</div>
      </div>
      <div class="card-pad">
        <div ref="latencyChart" class="chart" />
      </div>
    </div>
  </div>

  <div class="grid-3">
    <div class="card card-pad">
      <div class="t-section" style="margin-bottom:12px">观测栈</div>
      <div class="kv"><span>Replica</span><b class="mono">{{ stack.replica?.replica || stack.replica?.replicaId || '—' }}</b></div>
      <div class="kv"><span>OTel</span><b>{{ stack.otel?.mode || '—' }}</b></div>
      <div class="kv"><span>Nacos</span><b>{{ stack.nacos?.source || 'local' }}</b></div>
      <div class="kv"><span>Loki</span><b>{{ stack.loki?.enabled ? 'enabled' : 'local fallback' }}</b></div>
      <div class="kv"><span>FS</span><b>{{ stack.fs?.mode || '—' }} · {{ stack.fs?.itemCount ?? '—' }}</b></div>
      <div class="kv"><span>Sandbox</span><b>{{ stack.sandbox?.enabled ? stack.sandbox.image : 'disabled' }}</b></div>
    </div>

    <div class="card card-pad">
      <div class="t-section" style="margin-bottom:12px">配额消耗</div>
      <template v-if="quota">
        <div class="kv"><span>Tokens 已用</span><b>{{ quota.tokensUsed ?? '—' }}</b></div>
        <div class="kv"><span>Tokens 剩余</span><b>{{ quota.tokensRemaining ?? '—' }}</b></div>
        <div class="kv"><span>请求已用</span><b>{{ quota.requestsUsed ?? '—' }}</b></div>
        <div class="kv"><span>成本 micros</span><b>{{ quota.costMicrosUsed ?? '—' }}</b></div>
      </template>
      <div v-else class="t-faint">暂无配额数据（可去「配额与用量」配置）</div>
    </div>

    <div class="card card-pad">
      <div class="t-section" style="margin-bottom:12px">External Agents</div>
      <div v-if="!externals.length" class="t-faint">暂无外部 Agent</div>
      <div v-for="e in externals" :key="e.id || e.name" class="kv">
        <span>{{ e.name }}</span>
        <StatusTag :value="e.status || 'HEALTHY'" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chart {
  height: 240px;
}
.kv {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 13px;
  color: var(--ink-2);
}
.kv b {
  color: var(--ink-1);
  font-weight: 600;
}
</style>
