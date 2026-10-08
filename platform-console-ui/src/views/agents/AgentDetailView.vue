<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import JsonView from '@/components/ui/JsonView.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  listVersions,
  listSkills,
  listPermissions,
  listBusinessTools,
  listMcpServers,
  upsertPermission,
  deletePermission,
  registerBusinessTool,
  deleteBusinessTool,
} from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const agentId = route.params.agentId

const versions = ref([])
const skills = ref([])
const permissions = ref([])
const tools = ref([])
const mcp = ref([])
const loading = ref(false)
const tab = ref('overview')

const permDialog = ref(false)
const permForm = ref({ toolName: '', behavior: 'ASK', ruleContent: '*' })
const toolDialog = ref(false)
const toolForm = ref({
  toolName: '',
  description: '',
  kind: 'IN_PROCESS',
  url: '',
  method: 'GET',
  paramsJson: '{\n  "type": "object",\n  "properties": {},\n  "required": []\n}',
})
const saving = ref(false)

function asArray(data, ...keys) {
  if (Array.isArray(data)) return data
  for (const k of keys) {
    if (Array.isArray(data?.[k])) return data[k]
  }
  return []
}

async function load() {
  loading.value = true
  try {
    const v = await listVersions(agentId)
    versions.value = asArray(v, 'versions')
  } catch (e) {
    ElMessage.error(e.message)
    versions.value = []
  }
  try {
    const s = await listSkills()
    skills.value = asArray(s, 'skills')
  } catch {
    skills.value = []
  }
  try {
    const p = await listPermissions(agentId)
    permissions.value = asArray(p, 'rules', 'permissions')
  } catch (e) {
    ElMessage.error(e.message)
    permissions.value = []
  }
  try {
    const t = await listBusinessTools()
    tools.value = asArray(t, 'tools', 'items')
  } catch {
    tools.value = []
  }
  try {
    const m = await listMcpServers()
    mcp.value = asArray(m, 'servers', 'items')
  } catch {
    mcp.value = []
  } finally {
    loading.value = false
  }
}

function useAgent() {
  store.agentId = agentId
  router.push({ name: 'chat' })
}

function openPerm(row) {
  permForm.value = {
    toolName: row?.toolName || '',
    behavior: row?.behavior || 'ASK',
    ruleContent: row?.ruleContent || '*',
  }
  permDialog.value = true
}

async function savePerm() {
  if (!permForm.value.toolName) {
    ElMessage.warning('请填写 toolName')
    return
  }
  saving.value = true
  try {
    await upsertPermission({
      agentId,
      toolName: permForm.value.toolName,
      behavior: permForm.value.behavior,
      ruleContent: permForm.value.ruleContent || '*',
      source: 'console',
    })
    ElMessage.success('规则已保存')
    permDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function removePerm(row) {
  try {
    await ElMessageBox.confirm(
      `删除规则 ${row.toolName} · ${row.behavior}？`,
      '删除权限规则',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deletePermission({
      agentId,
      toolName: row.toolName,
      behavior: row.behavior,
      ruleContent: row.ruleContent,
    })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openTool(row) {
  let cfg = { url: '', method: 'GET', parameters: { type: 'object', properties: {} } }
  try {
    if (row?.config) cfg = { ...cfg, ...JSON.parse(row.config) }
  } catch {
    /* ignore */
  }
  toolForm.value = {
    toolName: row?.toolName || '',
    description: row?.description || row?.displayName || '',
    kind: row?.kind || 'IN_PROCESS',
    url: cfg.url || '',
    method: cfg.method || 'GET',
    paramsJson: JSON.stringify(cfg.parameters || { type: 'object', properties: {} }, null, 2),
  }
  toolDialog.value = true
}

async function saveTool() {
  if (!toolForm.value.toolName) {
    ElMessage.warning('请填写 toolName')
    return
  }
  saving.value = true
  try {
    let config = ''
    if (toolForm.value.kind === 'HTTP') {
      if (!toolForm.value.url) {
        ElMessage.warning('HTTP 工具需要填写 url')
        saving.value = false
        return
      }
      let parameters = {}
      try {
        parameters = JSON.parse(toolForm.value.paramsJson || '{}')
      } catch {
        ElMessage.error('参数 Schema 不是合法 JSON')
        saving.value = false
        return
      }
      config = JSON.stringify({
        url: toolForm.value.url,
        method: toolForm.value.method || 'GET',
        readOnly: toolForm.value.method === 'GET',
        parameters,
      })
    }
    await registerBusinessTool({
      toolName: toolForm.value.toolName,
      description: toolForm.value.description,
      kind: toolForm.value.kind,
      config,
    })
    ElMessage.success(toolForm.value.kind === 'HTTP' ? 'HTTP 工具已保存，Agent 实例重建后即可调用' : '业务工具已保存')
    toolDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function removeTool(row) {
  try {
    await ElMessageBox.confirm(`删除业务工具 ${row.toolName}？`, '删除工具', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteBusinessTool(row.toolName)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    :title="`Agent · ${agentId}`"
    back="/agents"
    back-label="Agent 列表"
  >
    <template #actions>
      <el-button @click="router.push(`/agents/${agentId}/versions`)">版本与发布</el-button>
      <el-button type="primary" @click="useAgent">设为当前并对话</el-button>
    </template>
  </PageHeader>

  <div class="card" v-loading="loading">
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
            <div class="t-faint">当前会话</div>
            <div class="t-section mono">{{ store.sessionId }}</div>
          </div>
        </div>
        <div v-if="versions.length" class="card card-pad" style="margin-top:12px">
          <div class="t-faint" style="margin-bottom:8px">最新系统提示词</div>
          <MarkdownView :text="versions[0].sysPrompt || '（空）'" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="版本" name="versions">
        <EmptyState v-if="!versions.length" title="暂无版本" description="到「版本与发布」创建并发布。" icon="Box">
          <el-button type="primary" @click="router.push(`/agents/${agentId}/versions`)">去创建版本</el-button>
        </EmptyState>
        <el-table v-else :data="versions" size="small">
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column prop="modelId" label="模型" width="200" />
          <el-table-column prop="sysPrompt" label="系统提示词" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="权限 / 工具" name="caps">
        <div class="caps-grid">
          <div>
            <div class="section-head">
              <div class="t-section">权限规则</div>
              <el-button size="small" type="primary" @click="openPerm()">新增规则</el-button>
            </div>
            <EmptyState
              v-if="!permissions.length"
              title="暂无权限规则"
              description="为敏感工具配置 ASK / DENY，或 ALLOW 免问。"
              icon="Lock"
            >
              <el-button type="primary" size="small" @click="openPerm()">新增规则</el-button>
            </EmptyState>
            <el-table v-else :data="permissions" size="small">
              <el-table-column prop="toolName" label="tool" min-width="120" />
              <el-table-column prop="behavior" label="行为" width="100">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    :type="row.behavior === 'ALLOW' ? 'success' : row.behavior === 'DENY' ? 'danger' : 'warning'"
                  >
                    {{ row.behavior }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="ruleContent" label="规则" show-overflow-tooltip />
              <el-table-column prop="source" label="来源" width="90" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="openPerm(row)">编辑</el-button>
                  <el-button size="small" text type="danger" @click="removePerm(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div>
            <div class="section-head">
              <div class="t-section">业务工具</div>
              <el-button size="small" type="primary" @click="openTool()">登记工具</el-button>
            </div>
            <EmptyState
              v-if="!tools.length"
              title="暂无业务工具"
              description="登记 safe_lookup / dangerous_write 等工具元数据。"
              icon="Grid"
            >
              <el-button type="primary" size="small" @click="openTool()">登记工具</el-button>
            </EmptyState>
            <el-table v-else :data="tools" size="small">
              <el-table-column prop="toolName" label="tool" min-width="120" />
              <el-table-column label="名称" min-width="120">
                <template #default="{ row }">{{ row.displayName || row.description || row.toolName }}</template>
              </el-table-column>
              <el-table-column prop="kind" label="kind" width="110" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="openTool(row)">编辑</el-button>
                  <el-button size="small" text type="danger" @click="removeTool(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="section-head" style="margin-top:16px">
              <div class="t-section">MCP Server</div>
            </div>
            <EmptyState v-if="!mcp.length" title="暂无 MCP" description="到「治理 / MCP 与工具」登记。" icon="Tools" />
            <el-table v-else :data="mcp" size="small">
              <el-table-column prop="name" label="mcp" />
              <el-table-column prop="transport" label="transport" />
              <el-table-column prop="status" label="status" width="100" />
            </el-table>

            <div class="section-head" style="margin-top:16px">
              <div class="t-section">已审核技能</div>
            </div>
            <div class="chips">
              <el-tag v-for="s in skills" :key="s.id || s.name" size="small">{{ s.name }} {{ s.version }}</el-tag>
              <span v-if="!skills.length" class="t-faint">暂无可见技能</span>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="原始数据" name="raw">
        <JsonView :data="{ agentId, versions, permissions, tools, mcp }" label="agent" max-height="420px" />
      </el-tab-pane>
    </el-tabs>
  </div>

  <el-dialog v-model="permDialog" :title="permForm.toolName ? '编辑权限规则' : '新增权限规则'" width="480px">
    <el-form label-position="top">
      <el-form-item label="toolName" required>
        <el-input v-model="permForm.toolName" placeholder="dangerous_write" />
      </el-form-item>
      <el-form-item label="行为">
        <el-radio-group v-model="permForm.behavior">
          <el-radio-button value="ALLOW">ALLOW</el-radio-button>
          <el-radio-button value="ASK">ASK</el-radio-button>
          <el-radio-button value="DENY">DENY</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="ruleContent">
        <el-input v-model="permForm.ruleContent" placeholder="* 表示不限内容" />
        <div class="t-faint" style="margin-top:6px">
          保存后会驱逐实例并重同步会话权限，立即生效。
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="permDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="savePerm">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="toolDialog" :title="toolForm.toolName ? '编辑业务工具' : '登记业务工具'" width="560px">
    <el-alert type="info" :closable="false" style="margin-bottom:12px">
      <b>HTTP</b>：登记后 Agent 自动加载，模型可直接调用；
      <b>IN_PROCESS</b>：仅目录元数据（需代码实现）；工具类实现见「治理 / MCP」。
    </el-alert>
    <el-form label-position="top">
      <el-form-item label="toolName" required>
        <el-input v-model="toolForm.toolName" placeholder="safe_lookup" />
      </el-form-item>
      <el-form-item label="描述 / 显示名">
        <el-input v-model="toolForm.description" placeholder="通用只读查询" />
      </el-form-item>
      <el-form-item label="kind">
        <el-select v-model="toolForm.kind" style="width:100%">
          <el-option label="HTTP（可执行）" value="HTTP" />
          <el-option label="IN_PROCESS（仅目录）" value="IN_PROCESS" />
        </el-select>
      </el-form-item>
      <template v-if="toolForm.kind === 'HTTP'">
        <el-form-item label="请求 URL" required>
          <el-input v-model="toolForm.url" placeholder="http://127.0.0.1:8080/api/items" />
        </el-form-item>
        <el-form-item label="Method">
          <el-select v-model="toolForm.method" style="width:100%">
            <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE']" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 Schema（JSON）">
          <el-input v-model="toolForm.paramsJson" type="textarea" :rows="8" class="mono" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="toolDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveTool">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tabs {
  padding: 8px 16px 16px;
}
.caps-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
@media (max-width: 1100px) {
  .caps-grid {
    grid-template-columns: 1fr;
  }
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
