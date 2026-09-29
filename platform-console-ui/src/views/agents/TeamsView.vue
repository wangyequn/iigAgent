<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { listTeams, upsertTeam, createTeamTask, listAgents } from '@/api'
import { ElMessage } from 'element-plus'

const teams = ref([])
const agents = ref([])
const loading = ref(false)
const formOpen = ref(false)
const form = ref({
  teamId: '',
  name: '',
  leaderAgentId: 'demo',
  memberAgentIds: '',
})
const goal = ref('排查昨日异常订单')

async function load() {
  loading.value = true
  try {
    const t = await listTeams()
    teams.value = t.teams || t.items || (Array.isArray(t) ? t : [])
  } catch (e) {
    ElMessage.error(e.message)
    teams.value = []
  }
  try {
    const a = await listAgents()
    agents.value = a.agents || []
  } catch {
    agents.value = []
  } finally {
    loading.value = false
  }
}

async function save() {
  try {
    await upsertTeam(form.value.teamId, {
      name: form.value.name,
      leaderAgentId: form.value.leaderAgentId,
      memberAgentIds: String(form.value.memberAgentIds || '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean),
    })
    ElMessage.success('Team 已保存')
    formOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function sendTask(teamId) {
  try {
    const r = await createTeamTask(teamId, goal.value)
    ElMessage.success(`任务已受理 ${String(r.taskId || '').slice(0, 8)}`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function edit(t) {
  form.value = {
    teamId: t.teamId || t.team_id,
    name: t.name,
    leaderAgentId: t.leaderAgentId || t.leader_agent_id,
    memberAgentIds: (t.memberAgentIds || t.members || []).join?.(',') || String(t.memberAgentIds || ''),
  }
  formOpen.value = true
}

onMounted(load)
</script>

<template>
  <PageHeader title="Team 协作" desc="把多个 Agent 组成小队，下发共享目标，协同排查与执行。">
    <template #actions>
      <el-input v-model="goal" placeholder="任务目标" style="width:220px" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="formOpen = true">新建 Team</el-button>
    </template>
  </PageHeader>

  <div class="card card-pad" v-loading="loading">
    <EmptyState
      v-if="!teams.length"
      title="还没有 Team"
      description="创建 Agent 小队，适合订单排查、工单处理等多角色场景。"
      icon="Connection"
    >
      <el-button type="primary" @click="formOpen = true">新建 Team</el-button>
    </EmptyState>

    <div v-else class="grid-2">
      <div v-for="t in teams" :key="t.teamId || t.team_id" class="team-card">
        <div class="team-head">
          <div class="avatar">T</div>
          <div>
            <div class="t-section">{{ t.name || t.teamId || t.team_id }}</div>
            <div class="t-faint mono">{{ t.teamId || t.team_id }}</div>
          </div>
        </div>
        <div class="t-muted">
          Leader <b class="mono">{{ t.leaderAgentId || t.leader_agent_id }}</b>
        </div>
        <div class="chips">
          <el-tag v-for="m in t.memberAgentIds || t.members || []" :key="m" size="small">{{ m }}</el-tag>
        </div>
        <div class="actions">
          <el-button size="small" type="primary" @click="sendTask(t.teamId || t.team_id)">下发任务</el-button>
          <el-button size="small" @click="edit(t)">编辑</el-button>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="formOpen" title="Team" width="480px">
    <el-form label-position="top">
      <el-form-item label="teamId">
        <el-input v-model="form.teamId" :disabled="!!form.teamId && teams.some((t) => (t.teamId || t.team_id) === form.teamId)" />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="Leader Agent">
        <el-select v-model="form.leaderAgentId" style="width:100%">
          <el-option v-for="a in agents" :key="a.agentId || a.id" :label="a.agentId || a.id" :value="a.agentId || a.id" />
          <el-option v-if="!agents.length" label="demo" value="demo" />
        </el-select>
      </el-form-item>
      <el-form-item label="成员（逗号分隔）">
        <el-input v-model="form.memberAgentIds" placeholder="demo,demo2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formOpen = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.team-card {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.team-head {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--info-soft);
  color: var(--info);
  display: grid;
  place-items: center;
  font-weight: 700;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.actions {
  display: flex;
  gap: 8px;
}
</style>
