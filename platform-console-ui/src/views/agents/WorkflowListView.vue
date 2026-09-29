<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { listWorkflows, listRuns, upsertWorkflow, publishWorkflowRevision, startWorkflowRun, getRun, getRunEvents, confirmRun, cancelRun } from '@/api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const workflows = ref([])
const runs = ref([])
const loading = ref(false)
const createOpen = ref(false)
const form = ref({
  workflowId: 'wf-demo',
  name: '演示流程',
  definitionJson: JSON.stringify(
    {
      steps: [
        { id: 's1', type: 'ECHO', input: '准备排查', next: 'gate' },
        { id: 'gate', type: 'HUMAN_GATE', prompt: '是否继续调用 Agent？', next: 's2' },
        { id: 's2', type: 'AGENT', agentId: 'demo', input: '总结今日订单异常' },
      ],
    },
    null,
    2,
  ),
})
const runGoal = ref('排查昨日异常订单')
const selectedRun = ref(null)
const runEvents = ref([])

async function load() {
  loading.value = true
  try {
    const w = await listWorkflows()
    workflows.value = w.workflows || w.items || (Array.isArray(w) ? w : [])
  } catch (e) {
    ElMessage.error(e.message)
    workflows.value = []
  }
  try {
    const r = await listRuns(30)
    runs.value = r.runs || r.items || []
  } catch {
    runs.value = []
  } finally {
    loading.value = false
  }
}

async function createWorkflow() {
  try {
    const definition = JSON.parse(form.value.definitionJson)
    await upsertWorkflow(form.value.workflowId, { name: form.value.name, definition })
    await publishWorkflowRevision(form.value.workflowId)
    ElMessage.success('Workflow 已保存并发布')
    createOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof SyntaxError) ElMessage.error('定义 JSON 不合法')
    else ElMessage.error(e.message)
  }
}

async function startRun(workflowId) {
  try {
    const run = await startWorkflowRun(workflowId, { input: { goal: runGoal.value } })
    ElMessage.success(`Run 已启动 ${String(run.runId || '').slice(0, 8)}`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openRun(id) {
  try {
    selectedRun.value = await getRun(id)
    const ev = await getRunEvents(id)
    runEvents.value = ev.events || ev.items || (Array.isArray(ev) ? ev : [])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function approveRun(approved) {
  try {
    selectedRun.value = await confirmRun(selectedRun.value.runId, approved, 'console')
    ElMessage.success(approved ? '已批准' : '已拒绝')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function stopRun() {
  try {
    selectedRun.value = await cancelRun(selectedRun.value.runId)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="编排 Workflow" desc="定义多步流程（ECHO / AGENT / HUMAN_GATE），发布 Revision 并跟踪 Run。">
    <template #actions>
      <el-input v-model="runGoal" placeholder="Run 目标输入" style="width:220px" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createOpen = true">新建 Workflow</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">Workflow</div>
      </div>
      <div class="card-pad" v-loading="loading">
        <EmptyState
          v-if="!workflows.length"
          title="还没有 Workflow"
          description="创建编排，把 Agent 与人工门禁串成可跟踪的流程。"
          icon="Operation"
        >
          <el-button type="primary" @click="createOpen = true">新建 Workflow</el-button>
        </EmptyState>
        <div v-for="w in workflows" :key="w.workflowId || w.id" class="wf-card">
          <div class="wf-head">
            <div>
              <div class="t-section">{{ w.name || w.workflowId }}</div>
              <div class="t-faint mono">{{ w.workflowId || w.id }}</div>
            </div>
            <StatusTag :value="w.status || 'ACTIVE'" />
          </div>
          <div class="actions">
            <el-button size="small" type="primary" @click="router.push(`/workflows/${w.workflowId || w.id}`)">编辑器</el-button>
            <el-button size="small" type="success" @click="startRun(w.workflowId || w.id)">启动 Run</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">最近 Run</div>
      </div>
      <div class="card-pad">
        <el-table :data="runs" size="small" @row-click="(r) => openRun(r.runId)" style="cursor:pointer">
          <el-table-column prop="runId" label="runId" width="130" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ String(row.runId).slice(0, 10) }}</span></template>
          </el-table-column>
          <el-table-column prop="workflowId" label="workflow" width="110" />
          <el-table-column prop="status" label="status">
            <template #default="{ row }"><StatusTag :value="row.status" /></template>
          </el-table-column>
          <el-table-column prop="currentStep" label="step" width="90" />
        </el-table>

        <div v-if="selectedRun" class="run-detail">
          <div class="run-head">
            <b>{{ selectedRun.status }}</b>
            <span class="mono t-faint">{{ selectedRun.runId }}</span>
            <div class="actions">
              <el-button
                v-if="selectedRun.status === 'WAITING_CONFIRM'"
                size="small"
                type="success"
                @click="approveRun(true)"
              >
                批准门禁
              </el-button>
              <el-button
                v-if="selectedRun.status === 'WAITING_CONFIRM'"
                size="small"
                type="danger"
                plain
                @click="approveRun(false)"
              >
                拒绝
              </el-button>
              <el-button
                v-if="['PENDING', 'RUNNING', 'WAITING_CONFIRM'].includes(selectedRun.status)"
                size="small"
                @click="stopRun"
              >
                取消
              </el-button>
            </div>
          </div>
          <el-timeline style="margin-top:12px">
            <el-timeline-item v-for="(ev, i) in runEvents" :key="i" :timestamp="String(ev.at || '')">
              <span class="mono">{{ ev.type }}</span>
              <span class="t-faint"> · {{ ev.stepId || '-' }}</span>
              <div class="t-muted">{{ ev.message }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="createOpen" title="新建 Workflow" width="640px">
    <el-form label-position="top">
      <el-form-item label="workflowId">
        <el-input v-model="form.workflowId" />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="定义 JSON">
        <el-input v-model="form.definitionJson" type="textarea" :rows="12" class="mono" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" @click="createWorkflow">保存并发布</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wf-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px;
  margin-bottom: 10px;
}
.wf-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.run-detail {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.run-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
