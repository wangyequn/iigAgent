<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { listInbox, listSessionApprovals, decideApproval, confirmSession } from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'

const store = useAppStore()
const items = ref([])
const sessionPending = ref([])
const loading = ref(false)
const filter = ref('all')
const detail = ref(null)
const note = ref('')

const filtered = computed(() => {
  if (filter.value === 'pending') return items.value.filter((x) => !x.status || x.status === 'PENDING' || x.status === 'WAITING')
  if (filter.value === 'done') return items.value.filter((x) => ['APPROVED', 'REJECTED', 'DONE'].includes(String(x.status || '').toUpperCase()))
  return items.value
})

async function load() {
  loading.value = true
  try {
    const inbox = await listInbox()
    items.value = inbox.items || inbox.approvals || []
  } catch (e) {
    ElMessage.error(e.message)
    items.value = []
  }
  try {
    const data = await listSessionApprovals(store.agentId, store.sessionId, store.userId)
    sessionPending.value = data.pending || []
  } catch {
    sessionPending.value = []
  } finally {
    loading.value = false
  }
}

async function decide(row, confirmed) {
  try {
    await decideApproval(row.id, confirmed, note.value || (confirmed ? 'ok' : 'reject'))
    ElMessage.success(confirmed ? '已批准' : '已拒绝')
    if (detail.value?.id === row.id) detail.value = null
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function confirmSessionItem(row, confirmed, acceptRule = false) {
  try {
    const toolInput = typeof row.toolInput === 'string' ? JSON.parse(row.toolInput || '{}') : row.toolInput || row.input || {}
    const data = await confirmSession(store.agentId, store.sessionId, {
      userId: store.userId,
      confirmed,
      toolCalls: [
        {
          toolCallId: row.toolCallId || row.id,
          toolName: row.toolName || row.tool,
          confirmed,
          input: toolInput,
          acceptRule: confirmed && acceptRule,
        },
      ],
      acceptRule: confirmed && acceptRule,
      note: note.value || (confirmed ? 'ok' : 'reject'),
    })
    const reply = data?.reply
    ElMessage.success(reply ? `已处理，续跑回复：${String(reply).slice(0, 80)}` : '已处理')
    await load()
  } catch (e) {
    if (e.status === 409) ElMessage.warning('再次进入确认，请继续处理')
    else ElMessage.error(e.message)
    await load()
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="审批中心"
    desc="统一处理工具调用人工确认（HITL）。可采纳建议规则，实现同工具后续免问。"
  >
    <template #actions>
      <el-radio-group v-model="filter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="pending">待处理</el-radio-button>
        <el-radio-button value="done">已处理</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">管理台 Inbox</div>
        <span class="t-faint">{{ filtered.length }} 条</span>
      </div>
      <div v-loading="loading" class="card-pad list">
        <EmptyState
          v-if="!filtered.length"
          title="没有待审批项"
          description="当 Agent 触发 ASK 权限规则时，会出现在这里。"
          icon="Bell"
        />
        <div
          v-for="row in filtered"
          :key="row.id"
          class="approval-card"
          :class="{ active: detail?.id === row.id }"
          @click="detail = row"
        >
          <div class="row-top">
            <div>
              <div class="t-section">{{ row.toolName || row.tool || 'tool' }}</div>
              <div class="t-faint mono">{{ row.id }} · {{ row.agentId || '—' }}</div>
            </div>
            <StatusTag :value="row.status || 'PENDING'" />
          </div>
          <div class="t-muted" style="margin-top:8px">
            {{ row.sessionId || store.sessionId }} · {{ row.userId || store.userId }}
          </div>
          <div v-if="row.suggestedRules?.length" class="suggest-row">
            <span class="t-faint">建议</span>
            <el-tag v-for="r in row.suggestedRules" :key="r.behavior + r.ruleContent" size="small" effect="plain">
              {{ r.behavior }} · {{ r.ruleContent || '*' }}
            </el-tag>
          </div>
          <div class="actions" @click.stop>
            <el-button size="small" type="success" @click="decide(row, true)">批准</el-button>
            <el-button size="small" type="danger" plain @click="decide(row, false)">拒绝</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">详情 / 当前会话待确认</div>
      </div>
      <div class="card-pad">
        <template v-if="detail">
          <div class="t-section">{{ detail.toolName }}</div>
          <JsonView :data="detail.toolInput || detail.input || detail" label="入参" max-height="220px" />
          <el-input v-model="note" type="textarea" :rows="2" placeholder="处理备注（可选）" style="margin:12px 0" />
          <div class="actions">
            <el-button type="success" @click="decide(detail, true)">批准</el-button>
            <el-button type="danger" plain @click="decide(detail, false)">拒绝</el-button>
          </div>
        </template>
        <EmptyState
          v-else
          title="选择左侧审批查看入参"
          description="支持查看 toolInput、建议规则，并写处理备注。"
          icon="View"
        />

        <el-divider>当前会话 pending</el-divider>
        <EmptyState v-if="!sessionPending.length" title="当前会话无待确认" icon="CircleCheck" />
        <div v-for="p in sessionPending" :key="p.toolCallId || p.id" class="approval-card">
          <div class="row-top">
            <div class="t-section">{{ p.toolName || p.tool }}</div>
            <StatusTag value="WAITING_CONFIRM" />
          </div>
          <div class="actions">
            <el-button size="small" type="success" @click="confirmSessionItem(p, true)">批准</el-button>
            <el-button size="small" type="danger" plain @click="confirmSessionItem(p, false)">拒绝</el-button>
            <el-button size="small" @click="confirmSessionItem(p, true, true)">永久允许</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 640px;
  overflow: auto;
}
.approval-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  cursor: pointer;
  background: var(--bg-surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.approval-card:hover,
.approval-card.active {
  border-color: color-mix(in srgb, var(--brand) 40%, var(--line));
  box-shadow: var(--shadow-sm);
}
.row-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}
.suggest-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
</style>
