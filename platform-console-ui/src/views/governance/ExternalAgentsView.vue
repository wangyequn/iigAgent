<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { listExternalAgents, registerExternalAgent, heartbeatExternal } from '@/api'
import { ElMessage } from 'element-plus'

const items = ref([])
const loading = ref(false)
const formOpen = ref(false)
const form = ref({
  name: 'ext-demo',
  baseUrl: 'http://127.0.0.1:19099',
  capabilities: 'chat',
})

async function load() {
  loading.value = true
  try {
    const data = await listExternalAgents()
    items.value = data.externalAgents || data.items || data.agents || []
  } catch (e) {
    ElMessage.error(e.message)
    items.value = []
  } finally {
    loading.value = false
  }
}

async function register() {
  try {
    await registerExternalAgent(form.value)
    ElMessage.success('External Agent 已注册')
    formOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function heartbeat(row) {
  try {
    await heartbeatExternal(row.id || row.name)
    ElMessage.success('心跳已更新')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="External Agent"
    desc="托管转发的外部智能体。注册后心跳保活；超时将标记 unhealthy 并可能拒绝转发。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="formOpen = true">注册</el-button>
    </template>
  </PageHeader>

  <div class="card card-pad" v-loading="loading">
    <EmptyState
      v-if="!items.length"
      title="暂无 External Agent"
      description="注册外部 Agent 后，未托管 agentId 的对话会转发到其 /messages。"
      icon="Link"
    >
      <el-button type="primary" @click="formOpen = true">注册</el-button>
    </EmptyState>

    <div v-else class="grid-2">
      <div v-for="a in items" :key="a.id || a.name" class="ext-card">
        <div class="ext-head">
          <div class="avatar">E</div>
          <div>
            <div class="t-section">{{ a.name }}</div>
            <div class="t-faint mono">{{ a.baseUrl }}</div>
          </div>
          <StatusTag :value="a.status || 'HEALTHY'" />
        </div>
        <div class="t-muted">caps: {{ a.capabilities || '—' }}</div>
        <div class="actions">
          <el-button size="small" @click="heartbeat(a)">心跳</el-button>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="formOpen" title="注册 External Agent" width="480px">
    <el-form label-position="top">
      <el-form-item label="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="baseUrl">
        <el-input v-model="form.baseUrl" />
      </el-form-item>
      <el-form-item label="capabilities">
        <el-input v-model="form.capabilities" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formOpen = false">取消</el-button>
      <el-button type="primary" @click="register">注册</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ext-card {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ext-head {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--warn-soft);
  color: var(--warn);
  display: grid;
  place-items: center;
  font-weight: 700;
}
</style>
