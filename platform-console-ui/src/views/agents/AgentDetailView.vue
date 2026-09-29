<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { listVersions, listSkills, listPermissions, listBusinessTools, listMcpServers } from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const agentId = route.params.agentId

const versions = ref([])
const skills = ref([])
const permissions = ref([])
const tools = ref([])
const mcp = ref([])
const tab = ref('overview')

async function load() {
  try {
    const v = await listVersions(agentId)
    versions.value = v.versions || []
  } catch (e) {
    ElMessage.error(e.message)
  }
  try {
    skills.value = (await listVisibleSkillsSafe()).skills || []
  } catch {
    /* optional */
  }
  try {
    permissions.value = await listPermissions()
  } catch {
    permissions.value = []
  }
  try {
    tools.value = (await listBusinessTools()).tools || []
    mcp.value = (await listMcpServers()).servers || []
  } catch {
    /* optional */
  }
}

async function listVisibleSkillsSafe() {
  try {
    return await listSkills()
  } catch {
    return { skills: [] }
  }
}

function useAgent() {
  store.agentId = agentId
  router.push({ name: 'chat' })
}

onMounted(load)
</script>

<template>
  <PageHeader :title="`Agent · ${agentId}`" desc="查看版本、挂载能力，或设为当前对话 Agent。">
    <template #actions>
      <el-button @click="router.push(`/agents/${agentId}/versions`)">版本与发布</el-button>
      <el-button type="primary" @click="useAgent">设为当前并对话</el-button>
    </template>
  </PageHeader>

  <div class="card">
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane label="概览" name="overview">
        <div class="grid-3">
          <div class="card card-pad">
            <div class="t-faint">Agent ID</div>
            <div class="t-section mono">{{ agentId }}</div>
          </div>
          <div class="card card-pad">
            <div class="t-faint">版本数</div>
            <div class="t-section">{{ versions.length }}</div>
          </div>
          <div class="card card-pad">
            <div class="t-faint">当前会话上下文</div>
            <div class="t-section mono">{{ store.sessionId }}</div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="版本" name="versions">
        <el-table :data="versions" size="small">
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.status || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="modelId" label="模型" />
          <el-table-column prop="sysPrompt" label="系统提示词" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="权限 / 工具" name="caps">
        <div class="grid-2">
          <div>
            <div class="t-section" style="margin-bottom:8px">权限规则</div>
            <el-table :data="permissions" size="small" max-height="280">
              <el-table-column prop="toolName" label="tool" />
              <el-table-column prop="behavior" label="behavior" width="100" />
              <el-table-column prop="ruleContent" label="rule" show-overflow-tooltip />
            </el-table>
          </div>
          <div>
            <div class="t-section" style="margin-bottom:8px">业务工具 / MCP</div>
            <el-table :data="tools" size="small" max-height="140">
              <el-table-column prop="toolName" label="tool" />
              <el-table-column prop="displayName" label="名称" />
            </el-table>
            <el-table :data="mcp" size="small" style="margin-top:10px" max-height="140">
              <el-table-column prop="name" label="mcp" />
              <el-table-column prop="transport" label="transport" />
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="原始数据" name="raw">
        <JsonView :data="versions" label="versions" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tabs {
  padding: 8px 16px 16px;
}
</style>
