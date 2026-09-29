<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { listAgents, createAgent, listVersions } from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox } from 'element-plus'

const store = useAppStore()
const router = useRouter()
const agents = ref([])
const loading = ref(false)
const createOpen = ref(false)
const form = ref({ agentId: '', name: '', modelId: 'deepseek:deepseek-flash', sysPrompt: '你是智能助手' })

async function load() {
  loading.value = true
  try {
    const data = await listAgents()
    const list = data.agents || data.items || (Array.isArray(data) ? data : [])
    agents.value = await Promise.all(
      list.map(async (a) => {
        let release = null
        try {
          const v = await listVersions(a.agentId || a.id)
          release = v.releaseVersion ?? v.release?.releaseVersion ?? null
        } catch {
          /* ignore */
        }
        return { ...a, release }
      }),
    )
  } catch (e) {
    ElMessage.error(e.message)
    agents.value = []
  } finally {
    loading.value = false
  }
}

async function create() {
  try {
    await createAgent({
      agentId: form.value.agentId,
      name: form.value.name || form.value.agentId,
      modelId: form.value.modelId,
      sysPrompt: form.value.sysPrompt,
    })
    ElMessage.success('Agent 已创建')
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openChat(id) {
  store.agentId = id
  router.push({ name: 'chat' })
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="Agent 列表"
    desc="浏览租户内 Agent，进入详情配置 Prompt / 工具 / 版本，或直接开始对话。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createOpen = true">新建 Agent</el-button>
    </template>
  </PageHeader>

  <div v-loading="loading" class="card card-pad">
    <EmptyState
      v-if="!agents.length"
      title="还没有 Agent"
      description="创建第一个 Agent，配置模型与系统提示词后即可对话。"
      icon="UserFilled"
    >
      <el-button type="primary" @click="createOpen = true">新建 Agent</el-button>
    </EmptyState>

    <div v-else class="grid-3">
      <div v-for="a in agents" :key="a.agentId || a.id" class="agent-card">
        <div class="agent-head">
          <div class="avatar">{{ (a.name || a.agentId || 'A').slice(0, 1) }}</div>
          <div>
            <div class="t-section">{{ a.name || a.agentId }}</div>
            <div class="t-faint mono">{{ a.agentId || a.id }}</div>
          </div>
        </div>
        <div class="meta">
          <StatusTag :value="a.status || 'ACTIVE'" />
          <el-tag size="small" effect="plain">release v{{ a.release ?? '—' }}</el-tag>
          <el-tag v-if="a.modelId" size="small" type="info" effect="plain">{{ a.modelId }}</el-tag>
        </div>
        <div class="actions">
          <el-button size="small" type="primary" @click="openChat(a.agentId || a.id)">对话</el-button>
          <el-button size="small" @click="router.push(`/agents/${a.agentId || a.id}`)">详情</el-button>
          <el-button size="small" @click="router.push(`/agents/${a.agentId || a.id}/versions`)">版本</el-button>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="createOpen" title="新建 Agent" width="520px">
    <el-form label-position="top">
      <el-form-item label="agentId" required>
        <el-input v-model="form.agentId" placeholder="demo2" />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" placeholder="演示助手" />
      </el-form-item>
      <el-form-item label="模型">
        <el-input v-model="form.modelId" placeholder="deepseek:deepseek-flash" />
      </el-form-item>
      <el-form-item label="系统提示词">
        <el-input v-model="form.sysPrompt" type="textarea" :rows="4" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" @click="create">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.agent-card {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.agent-head {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-weight: 700;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
