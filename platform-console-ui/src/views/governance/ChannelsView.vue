<script setup>
import { ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { channelWebhookPath } from '@/api'
import { ElMessage } from 'element-plus'

const channelType = ref('mock')
const token = ref('demo-channel-token')
const copied = ref(false)

const webhookUrl = ref('')
const payload = ref(
  JSON.stringify(
    {
      event: 'message.received',
      from: 'user-1',
      text: '帮我看看平台状态',
    },
    null,
    2,
  ),
)
const result = ref(null)
const sending = ref(false)

function refreshUrl() {
  const origin = window.location.origin
  webhookUrl.value = `${origin}${channelWebhookPath(channelType.value)}`
}

async function copyUrl() {
  refreshUrl()
  try {
    await navigator.clipboard.writeText(webhookUrl.value)
    copied.value = true
    ElMessage.success('Webhook URL 已复制')
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    ElMessage.error('复制失败')
  }
}

async function simulate() {
  refreshUrl()
  sending.value = true
  result.value = null
  try {
    const res = await fetch(webhookUrl.value, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Channel-Token': token.value,
      },
      body: payload.value,
    })
    const text = await res.text()
    result.value = { status: res.status, body: text }
    ElMessage.success(`已投递 · HTTP ${res.status}`)
  } catch (e) {
    result.value = { error: e.message }
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}

refreshUrl()
</script>

<template>
  <PageHeader
    title="渠道 Channel"
    desc="外部消息渠道通过 Webhook 接入平台。请求头需携带 X-Channel-Token（不走 API Key）。"
  >
    <template #actions>
      <el-button @click="refreshUrl">刷新 URL</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">Webhook 配置</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="渠道类型">
            <el-select v-model="channelType" style="width:100%" @change="refreshUrl">
              <el-option label="mock" value="mock" />
              <el-option label="wecom" value="wecom" />
              <el-option label="dingtalk" value="dingtalk" />
              <el-option label="feishu" value="feishu" />
            </el-select>
          </el-form-item>
          <el-form-item label="Webhook URL">
            <div class="copy-row">
              <el-input :model-value="webhookUrl" readonly class="mono" />
              <el-button @click="copyUrl">{{ copied ? '已复制' : '复制' }}</el-button>
            </div>
          </el-form-item>
          <el-form-item label="X-Channel-Token">
            <el-input v-model="token" />
          </el-form-item>
          <el-alert type="info" :closable="false" show-icon>
            该接口用于外部渠道回调，鉴权使用 Channel Token，而非 Bearer API Key。
          </el-alert>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">模拟投递</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="Payload">
            <el-input v-model="payload" type="textarea" :rows="10" class="mono" />
          </el-form-item>
          <el-button type="primary" :loading="sending" @click="simulate">发送到 Webhook</el-button>
        </el-form>
        <div v-if="result" class="result">
          <div class="t-faint">响应</div>
          <pre class="code">{{ JSON.stringify(result, null, 2) }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.copy-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.copy-row .el-input {
  flex: 1;
}
.result {
  margin-top: 14px;
}
</style>
