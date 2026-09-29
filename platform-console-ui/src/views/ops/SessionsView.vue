<script setup>
import { ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { lokiSearch, sessionHistory, interruptSession, clearContext } from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox } from 'element-plus'

const store = useAppStore()
const sessionId = ref(store.sessionId)
const lines = ref([])
const history = ref(null)
const loading = ref(false)

async function searchLogs() {
  loading.value = true
  try {
    const data = await lokiSearch(sessionId.value, 50)
    lines.value = data.lines || data.items || data.results || (Array.isArray(data) ? data : [])
  } catch (e) {
    ElMessage.error(e.message)
    lines.value = []
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  try {
    history.value = await sessionHistory(store.agentId, sessionId.value, store.userId)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doInterrupt() {
  try {
    await interruptSession(store.agentId, sessionId.value, store.userId)
    ElMessage.success('已请求中断')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doClear() {
  try {
    await ElMessageBox.confirm(`清空会话 ${sessionId.value} 的上下文？`, '确认', { type: 'warning' })
    await clearContext(store.agentId, sessionId.value, store.userId)
    ElMessage.success('已清空')
    history.value = null
  } catch {
    /* cancel */
  }
}
</script>

<template>
  <PageHeader title="会话与日志" desc="按 sessionId 检索会话事件与日志，支持查看历史、中断与清上下文。">
    <template #actions>
      <el-input v-model="sessionId" placeholder="sessionId" style="width:220px" />
      <el-button :loading="loading" @click="searchLogs">检索日志</el-button>
      <el-button @click="loadHistory">加载历史</el-button>
      <el-button @click="doInterrupt">中断</el-button>
      <el-button type="danger" plain @click="doClear">清上下文</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">日志检索</div>
        <span class="t-faint mono">{{ sessionId }}</span>
      </div>
      <div class="card-pad log-list" v-loading="loading">
        <EmptyState v-if="!lines.length" title="无日志" description="输入 sessionId 检索 Loki / 本地回退日志。" icon="Document" />
        <div v-for="(l, i) in lines" :key="i" class="log-line">
          <span class="t-faint mono">{{ l.timestamp || l.ts || l.at || '' }}</span>
          <span class="mono">{{ l.line || l.message || l.text || JSON.stringify(l) }}</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">会话历史</div>
      </div>
      <div class="card-pad">
        <EmptyState v-if="!history" title="尚未加载" description="点击右上角「加载历史」。" icon="Clock" />
        <JsonView v-else :data="history" label="history" max-height="560px" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 560px;
  overflow: auto;
}
.log-line {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  font-size: 12px;
}
</style>
