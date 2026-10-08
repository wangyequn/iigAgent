<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import {
  listMcpServers,
  registerMcpServer,
  mcpTest,
  listBusinessTools,
  registerBusinessTool,
  deleteBusinessTool,
} from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const tab = ref('tools')
const mcpList = ref([])
const bizTools = ref([])
const probe = ref(null)
const testing = ref(false)
const saving = ref(false)
const toolDialog = ref(false)
const mcpDialog = ref(false)
const probeDialog = ref(false)
const query = ref('')
const page = ref(1)
const mcpPage = ref(1)
const mcpPageSize = ref(10)
const pageSize = ref(10)
const searchQ = ref('')

const bizForm = ref({
  toolName: '',
  description: '',
  kind: 'HTTP',
  url: '',
  method: 'GET',
  paramsJson: '{\n  "type": "object",\n  "properties": {}\n}',
})
const mcpForm = ref({
  name: '',
  transport: 'stdio',
  command: '',
  args: '',
  url: '',
  enableTools: '',
})

const filteredTools = computed(() => {
  const q = (searchQ.value || query.value).trim().toLowerCase()
  if (!q) return bizTools.value
  return bizTools.value.filter(
    (t) =>
      String(t.toolName || '').toLowerCase().includes(q) ||
      String(t.displayName || t.description || '').toLowerCase().includes(q),
  )
})

const paged = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredTools.value.slice(start, start + pageSize.value)
})

const mcpPaged = computed(() => {
  const start = (mcpPage.value - 1) * mcpPageSize.value
  return mcpList.value.slice(start, start + mcpPageSize.value)
})

function doSearch() {
  page.value = 1
  searchQ.value = query.value
}

function toolNamesOfProbe() {
  const list = probe.value?.tools || probe.value?.data?.tools || []
  return list.map((t) => t.name || t.toolName || t)
}

async function load() {
  try {
    mcpList.value = (await listMcpServers()).servers || []
    const t = await listBusinessTools()
    bizTools.value = t.tools || []
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
  bizForm.value = {
    toolName: row?.toolName || '',
    description: row?.description || row?.displayName || '',
    kind: row?.kind || 'HTTP',
    url: cfg.url || '',
    method: cfg.method || 'GET',
    paramsJson: JSON.stringify(cfg.parameters || { type: 'object', properties: {} }, null, 2),
  }
  toolDialog.value = true
}

async function saveTool() {
  if (!bizForm.value.toolName) {
    ElMessage.warning('请填写 toolName')
    return
  }
  saving.value = true
  try {
    let config = ''
    if (bizForm.value.kind === 'HTTP') {
      if (!bizForm.value.url) {
        ElMessage.warning('HTTP 工具需要填写 URL')
        saving.value = false
        return
      }
      let parameters = {}
      try {
        parameters = JSON.parse(bizForm.value.paramsJson || '{}')
      } catch {
        ElMessage.error('参数 Schema 不是合法 JSON')
        saving.value = false
        return
      }
      config = JSON.stringify({
        url: bizForm.value.url,
        method: bizForm.value.method || 'GET',
        readOnly: bizForm.value.method === 'GET',
        parameters,
      })
    }
    await registerBusinessTool({
      toolName: bizForm.value.toolName,
      description: bizForm.value.description || bizForm.value.toolName,
      kind: bizForm.value.kind,
      config,
    })
    ElMessage.success(bizForm.value.kind === 'HTTP' ? 'HTTP 工具已登记，Agent 重建后可调用' : '工具已登记')
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
    await ElMessageBox.confirm(`删除工具「${row.toolName}」？`, '删除工具', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteBusinessTool(row.toolName)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

function openMcp(row) {
  mcpForm.value = {
    name: row?.name || '',
    transport: row?.transport || 'stdio',
    command: row?.command || '',
    args: row?.args || '',
    url: row?.url || '',
    enableTools: row?.enableTools || '',
  }
  mcpDialog.value = true
}

async function saveMcp() {
  if (!mcpForm.value.name) {
    ElMessage.warning('请填写 name')
    return
  }
  saving.value = true
  try {
    await registerMcpServer(mcpForm.value)
    ElMessage.success(
      mcpForm.value.enableTools
        ? 'MCP 已登记，Agent 重建后加载已启用工具'
        : 'MCP 已登记；未填启用工具则不会注入',
    )
    mcpDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function doTest(row) {
  const name = row?.name || mcpForm.value.name
  if (!name) {
    ElMessage.warning('请先保存 MCP Server')
    return
  }
  testing.value = true
  try {
    probe.value = await mcpTest(name)
    probeDialog.value = true
    ElMessage.success('探测成功')
  } catch (e) {
    probe.value = { error: e.message }
    probeDialog.value = true
    ElMessage.error(e.message)
  } finally {
    testing.value = false
  }
}

function useProbeTools() {
  const names = toolNamesOfProbe()
  if (!names.length) return
  mcpForm.value.enableTools = names.join(',')
  probeDialog.value = false
  ElMessage.success(`已填入 ${names.length} 个工具名，请保存启用`)
}

onMounted(load)
</script>

<template>
  <PageHeader title="MCP 与工具" compact>
    <template #actions>
      <el-button v-if="tab === 'tools'" size="small" type="primary" @click="openTool()">登记工具</el-button>
      <el-button v-else size="small" type="primary" @click="openMcp()">登记 MCP</el-button>
    </template>
  </PageHeader>

  <div class="card">
    <el-tabs v-model="tab" class="tabs">
      <!-- 业务工具 -->
      <el-tab-pane label="业务工具" name="tools">
        <div class="toolbar">
          <div class="row search-row">
            <el-input
              v-model="query"
              placeholder="toolName / 名称"
              clearable
              class="search-input"
              @keyup.enter="doSearch"
            />
            <el-button type="primary" @click="doSearch">搜索</el-button>
            <span class="t-faint count">共 {{ filteredTools.length }} 条</span>
          </div>
          <div class="row action-row">
            <el-button size="small" type="primary" @click="openTool()">新建 HTTP 工具</el-button>
            <span class="t-faint">
              HTTP 登记后 Agent 自动加载；IN_PROCESS 仅目录元数据
            </span>
          </div>
        </div>

        <div class="table-wrap">
          <EmptyState
            v-if="!filteredTools.length"
            title="暂无业务工具"
            description="登记 HTTP 工具后，模型可直接调用外部接口。"
            icon="Grid"
          >
            <el-button type="primary" @click="openTool()">登记工具</el-button>
          </EmptyState>
          <el-table v-else :data="paged" stripe>
            <el-table-column prop="toolName" label="toolName" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.toolName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称 / 描述" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.displayName || row.description || '—' }}</template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.kind === 'HTTP' ? 'success' : 'info'" effect="light">
                  {{ row.kind }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <StatusTag :value="row.status || 'ENABLED'" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right" align="right">
              <template #default="{ row }">
                <div class="ops">
                  <el-button size="small" text type="primary" @click="openTool(row)">编辑</el-button>
                  <el-button size="small" text type="danger" @click="removeTool(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        <div class="table-foot">
          <span class="t-faint">共 {{ filteredTools.length }} 条</span>
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="filteredTools.length"
            :page-sizes="[10, 20, 50]"
            layout="sizes, prev, pager, next"
            small
          />
        </div>
        </div>
      </el-tab-pane>

      <!-- MCP -->
      <el-tab-pane label="MCP Server" name="mcp">
        <div class="toolbar">
          <div class="row action-row">
            <el-button size="small" type="primary" @click="openMcp()">登记 MCP</el-button>
            <span class="t-faint">
              先「探测」列出工具，再在 enableTools 中显式启用；空白 = 不注入
            </span>
          </div>
        </div>
        <div class="table-wrap">
          <EmptyState
            v-if="!mcpList.length"
            title="暂无 MCP Server"
            description="登记后探测连通性，启用工具才会注入 Agent。"
            icon="Tools"
          >
            <el-button type="primary" @click="openMcp()">登记 MCP</el-button>
          </EmptyState>
          <el-table v-else :data="mcpPaged" stripe>
            <el-table-column prop="name" label="name" min-width="120">
              <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column prop="transport" label="transport" width="110" />
            <el-table-column label="command / url" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono t-muted">{{ row.command || row.url || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用工具" min-width="140">
              <template #default="{ row }">
                <el-tag v-if="row.enableTools" size="small" type="success" effect="light">
                  {{ row.enableTools }}
                </el-tag>
                <el-tag v-else size="small" type="warning" effect="light">未启用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right" align="right">
              <template #default="{ row }">
                <div class="ops">
                  <el-button size="small" text @click="doTest(row)">探测</el-button>
                  <el-button size="small" text type="primary" @click="openMcp(row)">编辑</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        <div class="table-foot">
          <span class="t-faint">共 {{ mcpList.length }} 条</span>
          <el-pagination
            v-model:current-page="mcpPage"
            v-model:page-size="mcpPageSize"
            :total="mcpList.length"
            :page-sizes="[10, 20, 50]"
            layout="sizes, prev, pager, next"
            small
          />
        </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>

  <!-- 业务工具编辑 -->
  <el-dialog v-model="toolDialog" :title="bizForm.toolName ? '编辑业务工具' : '登记业务工具'" width="560px">
    <el-form label-position="top">
      <el-form-item label="toolName" required>
        <el-input v-model="bizForm.toolName" placeholder="get_weather" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="bizForm.description" placeholder="查询天气" />
      </el-form-item>
      <el-form-item label="类型">
        <el-radio-group v-model="bizForm.kind">
          <el-radio-button value="HTTP">HTTP 可执行</el-radio-button>
          <el-radio-button value="IN_PROCESS">仅目录</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="bizForm.kind === 'HTTP'">
        <el-form-item label="URL" required>
          <el-input v-model="bizForm.url" placeholder="http://127.0.0.1:9000/api/weather" />
        </el-form-item>
        <el-form-item label="Method">
          <el-select v-model="bizForm.method" style="width:100%">
            <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE']" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 Schema">
          <el-input v-model="bizForm.paramsJson" type="textarea" :rows="6" class="mono" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="toolDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveTool">保存</el-button>
    </template>
  </el-dialog>

  <!-- MCP 编辑 -->
  <el-dialog v-model="mcpDialog" :title="mcpForm.name ? '编辑 MCP Server' : '登记 MCP Server'" width="560px">
    <el-form label-position="top">
      <el-form-item label="name" required>
        <el-input v-model="mcpForm.name" placeholder="demo-mcp" />
      </el-form-item>
      <el-form-item label="transport">
        <el-select v-model="mcpForm.transport" style="width:100%">
          <el-option label="stdio" value="stdio" />
          <el-option label="sse" value="sse" />
          <el-option label="streamable_http" value="streamable_http" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="mcpForm.transport === 'stdio'" label="command">
        <el-input v-model="mcpForm.command" placeholder="python" />
      </el-form-item>
      <el-form-item v-if="mcpForm.transport === 'stdio'" label="args">
        <el-input v-model="mcpForm.args" placeholder="scripts/mcp-demo-server.py" />
      </el-form-item>
      <el-form-item v-else label="url">
        <el-input v-model="mcpForm.url" placeholder="http://127.0.0.1:8000/mcp" />
      </el-form-item>
      <el-form-item label="启用工具（逗号分隔）">
        <el-input v-model="mcpForm.enableTools" placeholder="留空则不注入任何工具" />
        <div class="t-faint" style="margin-top:6px">
          可先「探测」列出工具名，再填入启用。
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="mcpDialog = false">取消</el-button>
      <el-button :loading="testing" @click="doTest(mcpForm)">探测</el-button>
      <el-button type="primary" :loading="saving" @click="saveMcp">保存</el-button>
    </template>
  </el-dialog>

  <!-- 探测结果 -->
  <el-dialog v-model="probeDialog" title="MCP 探测结果" width="560px">
    <JsonView :data="probe" label="probe" max-height="280px" />
    <div v-if="toolNamesOfProbe().length" class="probe-tools">
      <div class="t-faint" style="width:100%;margin-bottom:4px">工具列表</div>
      <el-tag v-for="n in toolNamesOfProbe()" :key="n" size="small" effect="plain">{{ n }}</el-tag>
    </div>
    <template #footer>
      <el-button @click="probeDialog = false">关闭</el-button>
      <el-button v-if="toolNamesOfProbe().length" type="primary" @click="useProbeTools">
        填入启用工具
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tabs {
  padding: 4px 16px 8px;
}
.toolbar {
  padding: 4px 0 0;
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
  width: 240px;
}
.count {
  margin-left: auto;
}
.table-wrap {
  padding: 4px 0 12px;
}
.ops {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}
.ops .el-button {
  margin-left: 4px;
}
.probe-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 4px 4px;
}
</style>


watch([query, searchQ, () => filteredTools.value.length], () => {
  const max = Math.max(1, Math.ceil((filteredTools.value?.length || 0) / pageSize.value))
  if (page.value > max) page.value = max
})
