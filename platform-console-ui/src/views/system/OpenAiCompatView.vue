<script setup>
import { ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { getOpenKey } from '@/api/http'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const model = ref('demo')
const messages = ref(
  JSON.stringify(
    [
      { role: 'system', content: '你是智能助手' },
      { role: 'user', content: '介绍一下你自己' },
    ],
    null,
    2,
  ),
)
const stream = ref(true)
const sending = ref(false)
const result = ref(null)
const streamLog = ref('')

async function send() {
  sending.value = true
  result.value = null
  streamLog.value = ''
  try {
    const parsed = JSON.parse(messages.value)
    const url = '/api/v1/chat/completions'
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${getOpenKey()}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: model.value,
        messages: parsed,
        stream: stream.value,
      }),
    })

    if (!res.ok) {
      const text = await res.text()
      result.value = { status: res.status, body: text }
      ElMessage.error(`HTTP ${res.status}`)
      return
    }

    if (stream.value) {
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        streamLog.value += decoder.decode(value, { stream: true })
      }
      result.value = { mode: 'stream', chunks: streamLog.value }
    } else {
      result.value = await res.json()
    }
    ElMessage.success('请求完成')
  } catch (e) {
    result.value = { error: e.message }
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <PageHeader
    title="OpenAI 兼容调试"
    desc="调试 POST /api/v1/chat/completions。model 映射 agentId；stream 返回 SSE chunks + [DONE]。"
  >
    <template #actions>
      <el-tag size="small" effect="plain" class="mono">Bearer {{ store.auth.openKey }}</el-tag>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">请求</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="model（= agentId）">
            <el-input v-model="model" />
          </el-form-item>
          <el-form-item label="messages JSON">
            <el-input v-model="messages" type="textarea" :rows="12" class="mono" />
          </el-form-item>
          <div style="display:flex;align-items:center;gap:12px">
            <el-switch v-model="stream" active-text="stream" />
            <el-button type="primary" :loading="sending" @click="send">发送</el-button>
          </div>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">响应</div>
      </div>
      <div class="card-pad">
        <pre v-if="streamLog" class="code" style="max-height:320px;white-space:pre-wrap">{{ streamLog }}</pre>
        <JsonView v-if="result && !streamLog" :data="result" label="response" max-height="420px" />
        <div v-if="!result && !streamLog" class="t-faint">发送请求后显示响应。</div>
      </div>
    </div>
  </div>
</template>
