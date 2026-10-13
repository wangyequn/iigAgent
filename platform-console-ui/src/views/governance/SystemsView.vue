<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import {
  listBusinessSystems,
  createBusinessSystem,
  updateBusinessSystem,
  deleteBusinessSystem,
  listBusinessTools,
  listAgents,
} from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const systems = ref([])
const tools = ref([])
const agents = ref([])
const loading = ref(false)
const query = ref('')
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

const form = reactive({
  systemCode: '',
  name: '',
  owner: '',
  contact: '',
  description: '',
  status: 'ACTIVE',
})

const rows = computed(() => {
  const q = (query.value || '').trim().toLowerCase()
  if (!q) return systems.value
  return systems.value.filter(
    (s) =>
      String(s.systemCode || '').toLowerCase().includes(q) ||
      String(s.name || '').toLowerCase().includes(q) ||
      String(s.owner || '').toLowerCase().includes(q),
  )
})

const toolCount = (code) => tools.value.filter((t) => t.systemCode === code).length
const agentCount = (code) => agents.value.filter((a) => a.systemCode === code).length

const summary = computed(() => ({
  total: systems.value.length,
  active: systems.value.filter((s) => s.status === 'ACTIVE').length,
  tools: tools.value.filter((t) => t.systemCode).length,
  agents: agents.value.filter((a) => a.systemCode).length,
}))

async function load() {
  loading.value = true
  try {
    const [sys, biz, ag] = await Promise.allSettled([
      listBusinessSystems(),
      listBusinessTools(),
      listAgents(),
    ])
    systems.value = sys.status === 'fulfilled' ? sys.value.systems || [] : []
    tools.value = biz.status === 'fulfilled' ? biz.value.tools || [] : []
    agents.value = ag.status === 'fulfilled' ? ag.value.agents || [] : []
    if (sys.status === 'rejected') ElMessage.error(sys.reason?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.systemCode = ''
  form.name = ''
  form.owner = ''
  form.contact = ''
  form.description = ''
  form.status = 'ACTIVE'
  dialog.value = true
}

function openEdit(row) {
  editing.value = row
  form.systemCode = row.systemCode
  form.name = row.name
  form.owner = row.owner || ''
  form.contact = row.contact || ''
  form.description = row.description || ''
  form.status = row.status || 'ACTIVE'
  dialog.value = true
}

async function save() {
  if (!form.systemCode || !form.name) {
    ElMessage.warning('请填写编码与名称')
    return
  }
  saving.value = true
  try {
    const body = {
      systemCode: form.systemCode,
      name: form.name,
      owner: form.owner,
      contact: form.contact,
      description: form.description,
      status: form.status,
    }
    if (editing.value) {
      await updateBusinessSystem(editing.value.systemCode, body)
      ElMessage.success('已更新')
    } else {
      await createBusinessSystem(body)
      ElMessage.success('已创建')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除系统「${row.name}」？需先解绑工具。`, '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteBusinessSystem(row.systemCode)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="业务系统"
    desc="按系统划分智能体与工具归属，便于多系统接入时的筛选、责任与治理。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建系统</el-button>
    </template>
  </PageHeader>

  <div class="grid-4" style="margin-bottom: 12px">
    <StatCard label="系统数" :value="summary.total" :hint="`${summary.active} 个启用`" tone="brand" />
    <StatCard label="已归属工具" :value="summary.tools" hint="tools.systemCode" />
    <StatCard label="已归属智能体" :value="summary.agents" hint="agents.systemCode" />
    <StatCard
      label="未归属"
      :value="tools.filter((t) => !t.systemCode).length + agents.filter((a) => !a.systemCode).length"
      hint="可在详情/接入时补系统"
      tone="warn"
    />
  </div>

  <div class="card filter-bar" style="margin-bottom: 12px">
    <el-input
      v-model="query"
      placeholder="搜索编码 / 名称 / 负责人"
      clearable
      style="width: 280px"
    />
    <span class="count-pill">{{ rows.length }} / {{ systems.length }}</span>
  </div>

  <div class="card" v-loading="loading">
    <el-table :data="rows">
      <el-table-column label="系统" min-width="220">
        <template #default="{ row }">
          <div class="t-name">
            <span
              class="dot"
              :style="{ background: row.status === 'ACTIVE' ? 'var(--ok)' : 'var(--ink-3)' }"
            />
            <span class="name">{{ row.name }}</span>
            <span class="kind-chip">{{ row.systemCode }}</span>
          </div>
          <div class="t-desc">{{ row.description || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="120" />
      <el-table-column prop="contact" label="联系方式" width="140" />
      <el-table-column label="归属" width="140">
        <template #default="{ row }">
          <div class="t-meta-block">工具 {{ toolCount(row.systemCode) }}</div>
          <div class="t-meta">Agent {{ agentCount(row.systemCode) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <div class="st">
            <span
              class="dot"
              :style="{ background: row.status === 'ACTIVE' ? 'var(--ok)' : 'var(--ink-3)' }"
            />
            <span>{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="" width="140" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">
          <div class="empty-icon">🏢</div>
          <div class="empty-title">还没有业务系统</div>
          <div class="empty-desc">先建系统，再把智能体 / 工具划进去</div>
          <el-button type="primary" style="margin-top: 12px" @click="openCreate">新建系统</el-button>
        </div>
      </template>
    </el-table>
  </div>

  <el-dialog v-model="dialog" :title="editing ? '编辑系统' : '新建系统'" width="520px">
    <el-form label-width="90px">
      <el-form-item label="编码" required>
        <el-input v-model="form.systemCode" :disabled="!!editing" placeholder="order-svc" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="订单系统" />
      </el-form-item>
      <el-form-item label="负责人">
        <el-input v-model="form.owner" />
      </el-form-item>
      <el-form-item label="联系方式">
        <el-input v-model="form.contact" placeholder="邮箱 / 企微" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 160px">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}
.count-pill {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 12px;
  font-weight: 600;
  margin-left: auto;
}
.t-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.t-name .name {
  font-weight: 600;
  color: var(--ink-1);
}
.kind-chip {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-2);
}
.t-desc {
  font-size: 12px;
  color: var(--ink-2);
  margin: 2px 0 0 16px;
}
.t-meta {
  font-size: 11px;
  color: var(--ink-3);
}
.t-meta-block {
  font-size: 12px;
  color: var(--ink-2);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.ops {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}
.empty {
  padding: 36px 16px;
  text-align: center;
}
.empty-icon {
  font-size: 36px;
}
.empty-title {
  font-weight: 600;
  margin-top: 8px;
}
.empty-desc {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 4px;
}
</style>
