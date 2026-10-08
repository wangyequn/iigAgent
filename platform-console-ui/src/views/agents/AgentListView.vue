<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import {
  listAgents,
  createAgent,
  updateAgent,
  deleteAgent,
  listVersions,
  createVersion,
  publishVersion,
} from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox } from 'element-plus'

const store = useAppStore()
const router = useRouter()

const agents = ref([])
const loading = ref(false)
const query = ref('')
const searchQ = ref('')
const statusFilter = ref('all')
const selected = ref([])
const page = ref(1)
const pageSize = ref(10)

const createOpen = ref(false)
const editOpen = ref(false)
const batchOpen = ref(false)
const creating = ref(false)
const saving = ref(false)
const batchSaving = ref(false)
const editRow = ref(null)
const editPrompt = ref('')
const editModelId = ref('')
const publishAfterSave = ref(false)
const promptLoading = ref(false)
const batchForm = ref({ enabled: true })

const form = ref({
  agentId: '',
  name: '',
  modelId: 'deepseek:deepseek-flash',
  sysPrompt: '你是智能助手，用中文简洁回答。',
})
const editForm = ref({ name: '', enabled: true })

function agentIdOf(a) {
  return a.agentId || a.id
}

/** 平台内置智能机器人（最佳实践入口） */
function isBot(a) {
  return agentIdOf(a) === 'console-bot'
}

const filtered = computed(() => {
  const q = (searchQ.value || query.value).trim().toLowerCase()
  return agents.value.filter((a) => {
    if (statusFilter.value === 'active' && a.enabled === false) return false
    if (statusFilter.value === 'disabled' && a.enabled !== false) return false
    if (!q) return true
    return (
      String(a.agentId || '').toLowerCase().includes(q) ||
      String(a.name || '').toLowerCase().includes(q) ||
      String(a.modelId || '').toLowerCase().includes(q)
    )
  })
})

const paged = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

function doSearch() {
  page.value = 1
  searchQ.value = query.value
}

function onSelectionChange(rows) {
  selected.value = rows || []
}

async function load() {
  loading.value = true
  try {
    const data = await listAgents()
    const list = Array.isArray(data) ? data : data.agents || data.items || []
    agents.value = await Promise.all(
      list.map(async (a) => {
        let release = a.releaseVersion ?? null
        if (release == null) {
          try {
            const v = await listVersions(agentIdOf(a))
            release = v.releaseVersion ?? null
          } catch {
            /* ignore */
          }
        }
        return {
          ...a,
          agentId: agentIdOf(a),
          name: a.name || agentIdOf(a),
          release,
          modelId: a.modelId || '',
          enabled: a.enabled !== false,
        }
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
  const id = String(form.value.agentId || '').trim()
  if (!id) {
    ElMessage.warning('请填写 agentId')
    return
  }
  creating.value = true
  try {
    await createAgent({
      agentId: id,
      name: String(form.value.name || '').trim() || id,
      modelId: form.value.modelId,
      sysPrompt: form.value.sysPrompt,
    })
    try {
      await createVersion(id, {
        modelId: form.value.modelId,
        sysPrompt: form.value.sysPrompt,
      })
    } catch {
      /* optional */
    }
    ElMessage.success(`Agent「${id}」已创建`)
    createOpen.value = false
    form.value.agentId = ''
    form.value.name = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

function openEdit(row) {
  editRow.value = row
  editForm.value = {
    name: row.name || '',
    enabled: row.enabled !== false,
  }
  publishAfterSave.value = false
  editOpen.value = true
  loadEditPrompt(row.agentId)
}

async function loadEditPrompt(agentId) {
  promptLoading.value = true
  editPrompt.value = ''
  editModelId.value = editRow.value?.modelId || ''
  try {
    const data = await listVersions(agentId)
    const list = data.versions || []
    const release = data.releaseVersion
    const current = list.find((v) => v.version === release) || list[list.length - 1] || null
    if (current) {
      editPrompt.value = current.sysPrompt || ''
      editModelId.value = current.modelId || editModelId.value
    }
  } catch {
    /* no versions */
  } finally {
    promptLoading.value = false
  }
}

async function saveEdit() {
  if (!editRow.value) return
  saving.value = true
  const id = editRow.value.agentId
  try {
    await updateAgent(id, {
      name: editForm.value.name,
      enabled: editForm.value.enabled,
    })
    if (String(editPrompt.value || '').trim()) {
      try {
        const v = await createVersion(id, {
          modelId: editModelId.value || editRow.value.modelId || 'deepseek:deepseek-flash',
          sysPrompt: editPrompt.value,
        })
        const createdVersion = v?.version ?? null
        if (publishAfterSave.value && createdVersion != null) {
          await publishVersion(id, createdVersion)
          ElMessage.success(`已保存并发布 v${createdVersion}`)
        } else {
          ElMessage.success(
            createdVersion != null
              ? `已保存，提示词已写入草稿 v${createdVersion}`
              : '已保存',
          )
        }
      } catch (e) {
        ElMessage.warning(`元数据已保存，提示词写入版本失败：${e.message}`)
      }
    } else {
      ElMessage.success('已保存')
    }
    editOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

function openBatch() {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选 Agent')
    return
  }
  batchForm.value.enabled = true
  batchOpen.value = true
}

async function applyBatch() {
  batchSaving.value = true
  let ok = 0
  let fail = 0
  for (const row of selected.value) {
    try {
      await updateAgent(row.agentId, {
        name: row.name,
        enabled: batchForm.value.enabled,
      })
      ok++
    } catch {
      fail++
    }
  }
  batchSaving.value = false
  batchOpen.value = false
  ElMessage.success(`批量完成：成功 ${ok}${fail ? `，失败 ${fail}` : ''}`)
  await load()
}

async function batchDelete() {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选 Agent')
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除选中的 ${selected.value.length} 个 Agent 及其版本？`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  let ok = 0
  for (const row of selected.value) {
    try {
      await deleteAgent(row.agentId)
      ok++
    } catch {
      /* continue */
    }
  }
  ElMessage.success(`已删除 ${ok} 个`)
  await load()
}

async function batchDeleteOne(row) {
  try {
    await ElMessageBox.confirm(
      `删除 Agent「${row.name || row.agentId}」及其全部版本？`,
      '删除 Agent',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteAgent(row.agentId)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openChat(a) {
  store.agentId = a.agentId
  router.push({ name: 'chat' })
}

onMounted(load)
</script>

<template>
  <div class="page compact-page">
    <div class="page-bar">
      <h1 class="title">Agent 列表</h1>
    </div>

    <div class="card">
      <div class="toolbar">
        <div class="row search-row">
          <el-input
            v-model="query"
            placeholder="agentId / 名称 / 模型"
            clearable
            class="search-input"
            @keyup.enter="doSearch"
          />
          <el-button type="primary" @click="doSearch">搜索</el-button>
          <el-select v-model="statusFilter" class="status-select" placeholder="状态">
            <el-option label="全部" value="all" />
            <el-option label="启用" value="active" />
            <el-option label="停用" value="disabled" />
          </el-select>
        </div>
        <div class="row action-row">
          <el-button size="small" :disabled="!selected.length" @click="openBatch">批量修改</el-button>
          <el-button size="small" type="danger" plain :disabled="!selected.length" @click="batchDelete">
            批量删除
          </el-button>
          <el-button size="small" type="primary" @click="createOpen = true">新建</el-button>
          <span class="t-faint count">已选 {{ selected.length }} · 共 {{ filtered.length }} 条</span>
        </div>
      </div>

      <div v-loading="loading" class="table-wrap">
        <EmptyState
          v-if="!filtered.length"
          title="没有匹配的 Agent"
          description="换个关键词，或创建第一个 Agent。"
          icon="UserFilled"
        >
          <el-button type="primary" @click="createOpen = true">新建 Agent</el-button>
        </EmptyState>

        <el-table
          v-else
          :data="paged"
          stripe
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column label="Agent" min-width="180">
            <template #default="{ row }">
              <div class="cell-agent">
                <div class="avatar" :class="{ bot: isBot(row) }">
                  {{ isBot(row) ? '助' : (row.name || row.agentId || 'A').slice(0, 1) }}
                </div>
                <div>
                  <div class="name">
                    {{ row.name }}
                    <el-tag v-if="isBot(row)" size="small" type="primary" effect="light" round>
                      平台内置
                    </el-tag>
                  </div>
                  <div class="t-faint mono">{{ row.agentId }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="modelId" label="模型" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="t-muted">{{ row.modelId || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag :value="row.enabled ? 'ACTIVE' : 'DISABLED'" />
            </template>
          </el-table-column>
          <el-table-column label="Release" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.release != null" size="small" type="success" effect="light">
                v{{ row.release }}
              </el-tag>
              <el-tag v-else size="small" type="info" effect="plain">—</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right" align="right">
            <template #default="{ row }">
              <div class="ops">
                <el-button size="small" type="primary" text @click="openChat(row)">对话</el-button>
                <el-button size="small" text @click="router.push(`/agents/${row.agentId}`)">详情</el-button>
                <el-button size="small" text @click="router.push(`/agents/${row.agentId}/versions`)">版本</el-button>
                <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" text type="danger" @click="batchDeleteOne(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="table-foot">
          <span class="t-faint">共 {{ filtered.length }} 条</span>
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="filtered.length"
            :page-sizes="[10, 20, 50]"
            layout="sizes, prev, pager, next"
            small
          />
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="createOpen" title="新建 Agent" width="680px">
    <el-form label-position="top">
      <el-form-item label="agentId" required>
        <el-input v-model="form.agentId" placeholder="例如：order-bot" />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" placeholder="客服助手" />
      </el-form-item>
      <el-form-item label="模型">
        <el-input v-model="form.modelId" />
      </el-form-item>
      <el-form-item label="系统提示词（Markdown）">
        <MarkdownEditor v-model="form.sysPrompt" :rows="8" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="create">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="editOpen" title="编辑 Agent" width="720px">
    <el-form label-position="top">
      <el-form-item label="agentId">
        <el-input :model-value="editRow?.agentId" disabled />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="editForm.name" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="editForm.enabled" />
      </el-form-item>
      <el-form-item label="系统提示词（Markdown）">
        <div v-loading="promptLoading" style="width:100%">
          <MarkdownEditor v-model="editPrompt" :rows="10" />
        </div>
        <div class="t-faint" style="margin-top:6px">保存会新建草稿版本；勾选下方选项可直接发布。</div>
      </el-form-item>
      <el-form-item label="模型（写入新版本）">
        <el-input v-model="editModelId" />
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="publishAfterSave">保存后立即发布新版本</el-checkbox>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editOpen = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchOpen" title="批量修改" width="420px">
    <p class="t-muted">将对已选 <b>{{ selected.length }}</b> 个 Agent 应用：</p>
    <el-form label-position="top">
      <el-form-item label="启用状态">
        <el-radio-group v-model="batchForm.enabled">
          <el-radio-button :value="true">启用</el-radio-button>
          <el-radio-button :value="false">停用</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="batchOpen = false">取消</el-button>
      <el-button type="primary" :loading="batchSaving" @click="applyBatch">应用</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.compact-page {
  gap: 12px;
  padding: 12px 16px 16px;
  max-width: none;
}
.page-bar {
  display: flex;
  align-items: center;
}
.title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  color: var(--ink-1);
}
.toolbar {
  padding: 12px 12px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.search-input {
  width: 260px;
}
.status-select {
  width: 120px;
}
.count {
  margin-left: auto;
}
.table-wrap {
  padding: 8px;
}
.cell-agent {
  display: flex;
  gap: 10px;
  align-items: center;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-weight: 700;
  flex-shrink: 0;
}
.avatar.bot {
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #fff;
}
.name {
  font-weight: 600;
}
.ops {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: nowrap;
}
.ops .el-button {
  margin-left: 4px;
}
.table-foot {
  padding: 8px 8px 4px;
  text-align: right;
}
</style>


watch([query, searchQ, statusFilter, () => filtered.value.length], () => {
  const max = Math.max(1, Math.ceil((filtered.value?.length || 0) / pageSize.value))
  if (page.value > max) page.value = max
})
