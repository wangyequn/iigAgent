<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { getConfig, updateConfig, metricsSummary } from '@/api'
import { ElMessage } from 'element-plus'

const config = ref(null)
const rateLimitPerMinute = ref(120)
const saving = ref(false)
const metrics = ref(null)

async function load() {
  try {
    config.value = await getConfig()
    rateLimitPerMinute.value =
      config.value?.rateLimitPerMinute ?? config.value?.ops?.rateLimitPerMinute ?? 120
  } catch (e) {
    ElMessage.error(e.message)
  }
  try {
    metrics.value = await metricsSummary()
  } catch {
    metrics.value = null
  }
}

async function save() {
  saving.value = true
  try {
    await updateConfig({ rateLimitPerMinute: rateLimitPerMinute.value })
    ElMessage.success('配置已热更新')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="配置热更" desc="查看 amp.ops.* 运行配置并热更新（限流等）。Nacos 启用时优先远端配置。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">限流</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="每分钟请求数">
            <el-input-number v-model="rateLimitPerMinute" :min="1" :max="10000" style="width:100%" />
          </el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
        </el-form>
        <div class="t-faint" style="margin-top:10px">
          触发限流时返回 429 <span class="mono">RATE_LIMITED</span>。
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">当前配置</div>
      </div>
      <div class="card-pad">
        <JsonView :data="config" label="config" max-height="320px" />
        <JsonView :data="metrics" label="metrics" max-height="180px" style="margin-top:12px" />
      </div>
    </div>
  </div>
</template>
