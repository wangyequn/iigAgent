<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { getQuotas, upsertQuota, getUsage, getUsageReport, exportUsageCsv } from '@/api'
import { ElMessage } from 'element-plus'

const quota = ref(null)
const usage = ref([])
const form = ref({ monthlyTokenLimit: 0, monthlyRequestLimit: 0 })
const year = ref(new Date().getFullYear())
const month = ref(new Date().getMonth() + 1)
const report = ref(null)
const exporting = ref(false)

async function load() {
  try {
    const q = await getQuotas()
    quota.value = q.quota || q
    form.value.monthlyTokenLimit = quota.value.monthlyTokenLimit ?? 0
    form.value.monthlyRequestLimit = quota.value.monthlyRequestLimit ?? 0
  } catch (e) {
    ElMessage.error(e.message)
  }
  try {
    const u = await getUsage(50)
    usage.value = u.items || u.usage || u.records || (Array.isArray(u) ? u : [])
  } catch {
    usage.value = []
  }
  await loadReport()
}

async function loadReport() {
  try {
    report.value = await getUsageReport(year.value, month.value)
  } catch {
    report.value = null
  }
}

async function saveQuota() {
  try {
    await upsertQuota(form.value)
    ElMessage.success('配额已保存')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doExport() {
  exporting.value = true
  try {
    const from = `${year.value}-${String(month.value).padStart(2, '0')}-01`
    const to = `${year.value}-${String(month.value).padStart(2, '0')}-31`
    const csv = await exportUsageCsv(from, to)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `usage-${year.value}-${month.value}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success('CSV 已导出')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="配额与用量" desc="设置月度 Token / 请求上限，查看用量流水与月报，并导出 CSV。">
    <template #actions>
      <el-button :loading="exporting" @click="doExport">导出 CSV</el-button>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-4">
    <StatCard label="Token 上限" :value="form.monthlyTokenLimit || '不限'" icon="Coin" />
    <StatCard label="请求上限" :value="form.monthlyRequestLimit || '不限'" icon="Tickets" />
    <StatCard label="已用 Tokens" :value="quota?.tokensUsed ?? 0" tone="brand" />
    <StatCard label="已用成本(micros)" :value="quota?.costMicrosUsed ?? 0" />
  </div>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">配额设置</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="月 Token 上限（0=不限）">
            <el-input-number v-model="form.monthlyTokenLimit" :min="0" style="width:100%" />
          </el-form-item>
          <el-form-item label="月请求上限（0=不限）">
            <el-input-number v-model="form.monthlyRequestLimit" :min="0" style="width:100%" />
          </el-form-item>
          <el-button type="primary" @click="saveQuota">保存配额</el-button>
        </el-form>

        <div class="t-section" style="margin:18px 0 8px">月报</div>
        <div style="display:flex;gap:8px;align-items:center">
          <el-input-number v-model="year" :min="2020" :max="2100" />
          <el-input-number v-model="month" :min="1" :max="12" />
          <el-button @click="loadReport">查询</el-button>
        </div>
        <pre v-if="report" class="code" style="margin-top:10px;max-height:180px">{{ JSON.stringify(report, null, 2) }}</pre>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">用量流水</div>
      </div>
      <div class="card-pad">
        <EmptyState v-if="!usage.length" title="暂无用量" icon="Wallet" />
        <el-table v-else :data="usage" size="small" max-height="420">
          <el-table-column prop="agent_id" label="agent" width="100" />
          <el-table-column prop="prompt_tokens" label="prompt" width="90" />
          <el-table-column prop="completion_tokens" label="completion" width="100" />
          <el-table-column prop="source" label="source" width="110" />
          <el-table-column prop="cost_micros" label="cost" width="100" />
        </el-table>
      </div>
    </div>
  </div>
</template>
