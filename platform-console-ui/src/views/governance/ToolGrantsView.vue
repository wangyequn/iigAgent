<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import {
  listAgents,
  listBusinessTools,
  listPermissions,
  upsertPermissionsBatch,
} from '@/api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const agents = ref([])
const tools = ref([])
const rules = ref({}) // agentId -> { toolName: behavior }
const loading = ref(false)
const dirty = ref(false)
const agentFilter = ref('')
const onlyUngranted = ref(false)

const agentRows = computed(() => {
  let list = agents.value
  if (agentFilter.value) list = list.filter((a) => a.agentId === agentFilter.value)
  if (onlyUngranted.value) {
    list = list.filter((a) =>
      tools.value.some((t) => {
        const b = rules.value[a.agentId]?.[t.toolName]
        return !b
      }),
    )
  }
  return list
})

const toolCols = computed(() =>
  tools.value.filter((t) => t.risk !== 'forbidden' && (t.status || t.lifecycle) === 'ENABLED'),
)

function behaviorOf(agentId, toolName) {
  return rules.value[agentId]?.[toolName] || ''
}

function setCell(agentId, toolName, behavior) {
  if (!rules.value[agentId]) rules.value[agentId] = {}
  const cur = rules.value[agentId][toolName] || ''
  rules.value[agentId][toolName] = cur === behavior ? '' : behavior
  dirty.value = true
}

async function load() {
  loading.value = true
  try {
    const [ag, biz] = await Promise.all([listAgents(), listBusinessTools()])
    agents.value = (ag.agents || []).map((a) => ({
      agentId: a.agentId || a.id,
      name: a.name || a.agentId || a.id,
    }))
    tools.value = biz.tools || []
    const map = {}
    await Promise.all(
      agents.value.map(async (a) => {
        try {
          const p = await listPermissions(a.agentId)
          const m = {}
          for (const r of p.rules || []) m[r.toolName] = r.behavior
          map[a.agentId] = m
        } catch {
          map[a.agentId] = {}
        }
      }),
    )
    rules.value = map
    dirty.value = false
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  loading.value = true
  const items = []
  for (const [agentId, m] of Object.entries(rules.value)) {
    for (const [toolName, behavior] of Object.entries(m)) {
      items.push({
        agentId,
        toolName,
        behavior: behavior || null,
        ruleContent: '*',
        source: 'console',
      })
    }
  }
  try {
    await upsertPermissionsBatch(items)
    ElMessage.success('已保存（实例已驱逐，下一轮生效）')
    dirty.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const t = route.query.tool
  if (t) onlyUngranted.value = false
  load()
})
</script>

<template>
  <PageHeader
    title="工具授权"
    back="/governance/tools"
    back-label="工具目录"
    desc="Agent × 工具矩阵（仅展示启用中的工具）。ALLOW=可调 · ASK=人工确认 · DENY=禁止 · 空=默认模式。装配绑定见工具中心详情。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :disabled="!dirty" :loading="loading" @click="save">
        保存更改
      </el-button>
    </template>
  </PageHeader>

  <div class="card" style="margin-bottom: 12px">
    <div style="display: flex; gap: 12px; flex-wrap: wrap; align-items: center">
      <el-select v-model="agentFilter" clearable placeholder="全部 Agent" style="width: 180px">
        <el-option v-for="a in agents" :key="a.agentId" :label="a.agentId" :value="a.agentId" />
      </el-select>
      <el-checkbox v-model="onlyUngranted">只看未授权</el-checkbox>
      <span class="muted">启用工具 {{ toolCols.length }} / 全部 {{ tools.length }}</span>
      <span v-if="dirty" class="warn">有未保存更改</span>
    </div>
  </div>

  <div class="card" v-loading="loading">
    <el-table :data="agentRows" empty-text="暂无 Agent" border>
      <el-table-column prop="agentId" label="Agent" width="160" fixed />
      <el-table-column
        v-for="t in toolCols"
        :key="t.toolName"
        :label="t.toolName"
        min-width="120"
      >
        <template #header>
          <div>
            <div>{{ t.toolName }}</div>
            <el-tag size="small" :type="t.risk === 'high' ? 'danger' : t.risk === 'low' ? 'success' : 'warning'">
              {{ t.risk || 'medium' }}
            </el-tag>
            <div class="muted">{{ t.kind }}</div>
          </div>
        </template>
        <template #default="{ row }">
          <el-radio-group
            :model-value="behaviorOf(row.agentId, t.toolName)"
            size="small"
            @update:model-value="(v) => setCell(row.agentId, t.toolName, v)"
          >
            <el-radio-button value="ALLOW">A</el-radio-button>
            <el-radio-button value="ASK">K</el-radio-button>
            <el-radio-button value="DENY">D</el-radio-button>
          </el-radio-group>
        </template>
      </el-table-column>
    </el-table>
    <p class="muted" style="margin-top: 12px">
      A=ALLOW · K=ASK · D=DENY · 未选=清除规则。forbidden 工具不可授权。
    </p>
  </div>
</template>

<style scoped>
.warn {
  color: var(--warn, #d97706);
}
.muted {
  color: var(--ink-3, #999);
  font-size: 12px;
}
</style>
