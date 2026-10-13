<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import {
  listBusinessTools,
  listMcpServers,
  listAgents,
  listToolBindings,
  upsertToolBinding,
  deleteBusinessTool,
  toolLifecycle,
  testBusinessTool,
  cloneBusinessTool,
  listAudit,
  mcpTest,
  listBusinessSystems,
} from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const tools = ref([])
const mcps = ref([])
const agents = ref([])
const loading = ref(false)
const query = ref('')
const riskFilter = ref('')
const statusFilter = ref('')
const kindFilter = ref('')
const systemFilter = ref('')
const systems = ref([])
/** tools | mcp —— MCP 与工具同级，不沉底 */
const view = ref('tools')
const mcpQuery = ref('')
const page = ref(1)
const pageSize = ref(20)
const mcpPage = ref(1)
const mcpPageSize = ref(20)

const detail = ref(null)
const detailVisible = ref(false)
const detailTab = ref('overview')
const toolAudit = ref([])
const bindings = ref([])
const testing = ref(false)
const testResult = ref(null)

const rows = computed(() => {
  const q = (query.value || '').trim().toLowerCase()
  return tools.value.filter((t) => {
    if (riskFilter.value && (t.risk || 'medium') !== riskFilter.value) return false
    if (statusFilter.value && (t.status || t.lifecycle) !== statusFilter.value) return false
    if (kindFilter.value && t.kind !== kindFilter.value) return false
    if (systemFilter.value && (t.systemCode || '') !== systemFilter.value) return false
    if (!q) return true
    return (
      String(t.toolName || '').toLowerCase().includes(q) ||
      String(t.ownerSystem || t.owner || '').toLowerCase().includes(q) ||
      String(t.description || '').toLowerCase().includes(q) ||
      String(t.url || '').toLowerCase().includes(q) ||
      String(t.tags || '').toLowerCase().includes(q)
    )
  })
})

const summary = computed(() => {
  const st = (t) => t.status || t.lifecycle || 'ENABLED'
  return {
    total: tools.value.length,
    enabled: tools.value.filter((t) => st(t) === 'ENABLED').length,
    draft: tools.value.filter((t) => st(t) === 'DRAFT').length,
    disabled: tools.value.filter((t) => st(t) === 'DISABLED').length,
    deprecated: tools.value.filter((t) => st(t) === 'DEPRECATED').length,
    high: tools.value.filter((t) => t.risk === 'high' || t.risk === 'forbidden').length,
    mcp: mcps.value.length,
    agents: agents.value.length,
    http: tools.value.filter((t) => t.kind === 'HTTP').length,
  }
})

const filteredMcps = computed(() => {
  const q = (mcpQuery.value || '').trim().toLowerCase()
  if (!q) return mcps.value
  return mcps.value.filter((m) => {
    return (
      String(m.name || '').toLowerCase().includes(q) ||
      String(m.url || m.command || '').toLowerCase().includes(q) ||
      String(m.enableTools || '').toLowerCase().includes(q)
    )
  })
})

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})

const pagedMcps = computed(() => {
  const start = (mcpPage.value - 1) * mcpPageSize.value
  return filteredMcps.value.slice(start, start + mcpPageSize.value)
})

watch([query, statusFilter, riskFilter, kindFilter, systemFilter], () => {
  page.value = 1
})
watch([mcpQuery, view], () => {
  if (view.value === 'mcp') mcpPage.value = 1
  if (view.value === 'tools') page.value = 1
})

function splitTools(s) {
  return String(s || '')
    .split(/[,\s]+/)
    .filter(Boolean)
}

async function testMcp(row) {
  try {
    const r = await mcpTest(row.name)
    ElMessage.success(`MCP「${row.name}」连通：${JSON.stringify(r).slice(0, 120)}`)
  } catch (e) {
    ElMessage.error(e.message || '连通失败')
  }
}

const riskDot = (r) =>
  ({ low: 'var(--ok)', medium: 'var(--warn)', high: 'var(--danger)', forbidden: 'var(--ink-3)' }[r] ||
  'var(--warn)')

const statusDot = (s) =>
  ({
    ENABLED: 'var(--ok)',
    DRAFT: 'var(--info)',
    DISABLED: 'var(--warn)',
    DEPRECATED: 'var(--ink-3)',
  }[s] || 'var(--info)')

const statusLabel = (s) =>
  ({ ENABLED: '启用', DRAFT: '草稿', DISABLED: '停用', DEPRECATED: '废弃' }[s] || s)

const kindLabel = (k) => ({ HTTP: 'HTTP', MCP: 'MCP', IN_PROCESS: '进程内' }[k] || k)

const authLabel = (a) =>
  ({ user_token: '用户票', client: '客户端凭证', none: '无鉴权' }[a] || a || '用户票')

function metaLine(t) {
  const bits = []
  if (t.kind === 'HTTP' && t.method) bits.push(t.method)
  if (t.url) bits.push(t.url.replace(/^https?:\/\//, '').slice(0, 42))
  else if (t.ownerSystem || t.owner) bits.push(t.ownerSystem || t.owner)
  if (t.timeoutMs) bits.push(`${Math.round(t.timeoutMs / 1000)}s`)
  if (t.env) bits.push(t.env)
  if (t.systemCode) bits.push(t.systemCode)
  return bits.join('  ·  ')
}

function prettyJson(s) {
  if (!s) return ''
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

async function load() {
  loading.value = true
  try {
    const [biz, mcp, ag, sys] = await Promise.allSettled([
      listBusinessTools(),
      listMcpServers(),
      listAgents(),
      listBusinessSystems(),
    ])
    tools.value = biz.status === 'fulfilled' ? biz.value.tools || [] : []
    mcps.value = mcp.status === 'fulfilled' ? mcp.value.servers || [] : []
    agents.value = ag.status === 'fulfilled' ? ag.value.agents || [] : []
    systems.value = sys.status === 'fulfilled' ? sys.value.systems || [] : []
    if (biz.status === 'rejected') {
      ElMessage.error(biz.reason?.message || '工具列表加载失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(t) {
  detail.value = t
  detailVisible.value = true
  detailTab.value = 'overview'
  testResult.value = null
  toolAudit.value = []
  bindings.value = []
  try {
    const a = await listAudit(30, { toolName: t.toolName })
    toolAudit.value = a.items || []
  } catch {
    toolAudit.value = []
  }
  try {
    const list = []
    for (const ag of agents.value.slice(0, 20)) {
      const b = await listToolBindings(ag.agentId || ag.id)
      const hit = (b.bindings || []).find((x) => x.toolName === t.toolName)
      if (hit) {
        list.push({
          agentId: ag.agentId || ag.id,
          name: ag.name,
          enabled: hit.enabled,
          policy: hit.policyOverride,
        })
      }
    }
    bindings.value = list
  } catch {
    bindings.value = []
  }
}

async function runTest() {
  if (!detail.value) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testBusinessTool(detail.value.toolName)
  } catch (e) {
    testResult.value = { ok: false, error: e.message }
  } finally {
    testing.value = false
  }
}

async function runTestFor(row) {
  detail.value = row
  detailVisible.value = true
  detailTab.value = 'overview'
  await runTest()
}

async function setLifecycle(t, lifecycle, reason) {
  try {
    await toolLifecycle(t.toolName, { lifecycle, reason: reason || '', updatedBy: 'console' })
    ElMessage.success(`已切换为 ${lifecycle}`)
    await load()
    if (detail.value?.toolName === t.toolName) {
      detail.value = tools.value.find((x) => x.toolName === t.toolName) || detail.value
    }
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function cloneTool(t) {
  const { value } = await ElMessageBox.prompt('新工具名', '克隆为草稿', {
    inputValue: `${t.toolName}-copy`,
  })
  try {
    await cloneBusinessTool(t.toolName, { toolName: value })
    ElMessage.success('已克隆为 DRAFT')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '克隆失败')
  }
}

async function removeTool(t) {
  try {
    await ElMessageBox.confirm(
      `将「${t.toolName}」标记为 DEPRECATED（软删除）。硬删请用 API ?hard=true。`,
      '确认下线',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteBusinessTool(t.toolName, false)
    ElMessage.success('已标记 DEPRECATED')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onAction(cmd, row) {
  const t = row || detail.value
  if (!t) return
  if (cmd === 'enable') await setLifecycle(t, 'ENABLED')
  else if (cmd === 'disable') await setLifecycle(t, 'DISABLED')
  else if (cmd === 'deprecate') await setLifecycle(t, 'DEPRECATED', 'console deprecate')
  else if (cmd === 'clone') await cloneTool(t)
  else if (cmd === 'delete') await removeTool(t)
  else if (cmd === 'test') await runTestFor(t)
}

async function toggleBinding(agentId, toolName, enabled) {
  try {
    await upsertToolBinding(agentId, { toolName, enabled })
    ElMessage.success('绑定已更新')
  } catch (e) {
    ElMessage.error(e.message || '绑定失败')
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="工具中心"
    desc="HTTP / MCP / 进程内工具的目录、生命周期、试调用与 Agent 装配。"
  >
    <template #actions>
      <el-button @click="load">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <el-button
        v-if="view === 'tools'"
        type="primary"
        @click="router.push('/governance/tools/register')"
      >
        <el-icon><Plus /></el-icon>
        <span>接入工具</span>
      </el-button>
      <el-button
        v-else
        type="primary"
        @click="router.push('/governance/tools/register?type=mcp')"
      >
        <el-icon><Plus /></el-icon>
        <span>登记 MCP</span>
      </el-button>
    </template>
  </PageHeader>

  <!-- 工具 / MCP 同级切换 -->
  <div class="view-switch card">
    <button
      type="button"
      class="view-btn"
      :class="{ on: view === 'tools' }"
      @click="view = 'tools'"
    >
      <span class="view-label">业务工具</span>
      <span class="view-count">{{ summary.total }}</span>
    </button>
    <button
      type="button"
      class="view-btn"
      :class="{ on: view === 'mcp' }"
      @click="view = 'mcp'"
    >
      <span class="view-label">MCP 服务器</span>
      <span class="view-count">{{ mcps.length }}</span>
    </button>
    <div class="view-spacer" />
    <el-button text @click="router.push('/governance/tools/grants')">授权矩阵</el-button>
  </div>

  <!-- 指标卡（仅工具视图） -->
  <div v-if="view === 'tools'" class="grid-4" style="margin-bottom: 4px">
    <StatCard
      label="工具总数"
      :value="summary.total"
      :hint="`HTTP ${summary.http} · 进程内 ${summary.total - summary.http}`"
      tone="brand"
    />
    <StatCard
      label="启用中"
      :value="summary.enabled"
      :hint="summary.draft ? `${summary.draft} 草稿待发布` : '全部可用'"
      :tone="summary.enabled ? 'ok' : 'default'"
    />
    <StatCard
      label="高危工具"
      :value="summary.high"
      :hint="summary.high ? '需关注授权策略' : '风险可控'"
      :tone="summary.high ? 'danger' : 'ok'"
    />
    <StatCard
      label="停用 / 废弃"
      :value="summary.disabled + summary.deprecated"
      :hint="`停用 ${summary.disabled} · 废弃 ${summary.deprecated}`"
      :tone="summary.disabled + summary.deprecated ? 'warn' : 'default'"
    />
  </div>

  <!-- MCP 指标卡 -->
  <div v-else class="grid-4" style="margin-bottom: 4px">
    <StatCard label="MCP 服务器" :value="mcps.length" hint="已登记连接" tone="brand" />
    <StatCard
      label="可用"
      :value="mcps.filter((m) => m.status === 'REGISTERED' || m.status === 'READY').length"
      hint="REGISTERED / READY"
      tone="ok"
    />
    <StatCard
      label="已启用工具"
      :value="mcps.reduce((n, m) => n + splitTools(m.enableTools).length, 0)"
      hint="enableTools 白名单"
    />
    <StatCard
      label="异常连接"
      :value="mcps.filter((m) => m.status && m.status !== 'REGISTERED' && m.status !== 'READY').length"
      hint="需检查网络 / 配置"
      tone="warn"
    />
  </div>

  <!-- 工具：筛选栏 -->
  <div v-if="view === 'tools'" class="card filter-bar">
    <div class="filter-left">
      <el-input
        v-model="query"
        placeholder="搜索工具 / URL / 所属系统 / 标签"
        clearable
        class="search-input"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="statusFilter" placeholder="生命周期" clearable style="width: 120px">
        <el-option label="启用" value="ENABLED" />
        <el-option label="草稿" value="DRAFT" />
        <el-option label="停用" value="DISABLED" />
        <el-option label="废弃" value="DEPRECATED" />
      </el-select>
      <el-select v-model="riskFilter" placeholder="风险" clearable style="width: 110px">
        <el-option label="low" value="low" />
        <el-option label="medium" value="medium" />
        <el-option label="high" value="high" />
        <el-option label="forbidden" value="forbidden" />
      </el-select>
      <el-select v-model="kindFilter" placeholder="类型" clearable style="width: 110px">
        <el-option label="HTTP" value="HTTP" />
        <el-option label="MCP" value="MCP" />
        <el-option label="进程内" value="IN_PROCESS" />
      </el-select>
      <el-select v-model="systemFilter" placeholder="业务系统" clearable style="width: 140px">
        <el-option
          v-for="s in systems"
          :key="s.systemCode"
          :label="s.name"
          :value="s.systemCode"
        />
      </el-select>
    </div>
    <div class="filter-right">
      <span class="count-pill">{{ rows.length }} / {{ tools.length }}</span>
    </div>
  </div>

  <!-- 工具表 -->
  <div v-if="view === 'tools'" class="card" v-loading="loading" style="margin-top: 12px">
    <el-table :data="pagedRows" class="tool-table" @row-click="openDetail">
      <el-table-column label="工具" min-width="280">
        <template #default="{ row }">
          <div class="t-name">
            <span class="dot" :style="{ background: statusDot(row.status || row.lifecycle) }" />
            <span class="name">{{ row.toolName }}</span>
            <span class="kind-chip">{{ kindLabel(row.kind) }}</span>
          </div>
          <div class="t-desc">{{ row.description || '—' }}</div>
          <div class="t-meta">{{ metaLine(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <div class="st">
            <span class="dot" :style="{ background: statusDot(row.status || row.lifecycle) }" />
            <span>{{ statusLabel(row.status || row.lifecycle) }}</span>
          </div>
          <div class="t-meta">v{{ row.version || 1 }}</div>
        </template>
      </el-table-column>
      <el-table-column label="风险 / 鉴权" width="120">
        <template #default="{ row }">
          <div class="st">
            <span class="dot" :style="{ background: riskDot(row.risk) }" />
            <span>{{ row.risk || 'medium' }}</span>
          </div>
          <div class="t-meta">{{ authLabel(row.authMode) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="责任人" width="110">
        <template #default="{ row }">
          <span class="t-meta-block">{{ row.owner || row.ownerSystem || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="220" fixed="right" class-name="ops-col">
        <template #default="{ row }">
          <div class="ops" @click.stop>
            <el-button
              size="small"
              type="primary"
              text
              @click="openDetail(row)"
            >详情</el-button>
            <el-button
              v-if="(row.status || row.lifecycle) !== 'ENABLED'"
              size="small"
              text
              type="success"
              @click="onAction('enable', row)"
            >启用</el-button>
            <el-button
              v-else
              size="small"
              text
              type="warning"
              @click="onAction('disable', row)"
            >停用</el-button>
            <el-dropdown
              trigger="click"
              @command="(c) => onAction(c, row)"
            >
              <el-button size="small" text>
                <el-icon><MoreFilled /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="test">试调用</el-dropdown-item>
                  <el-dropdown-item command="clone">克隆为草稿</el-dropdown-item>
                  <el-dropdown-item command="deprecate">标记废弃</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>下线</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">
          <div class="empty-icon">🧰</div>
          <div class="empty-title">暂无工具</div>
          <div class="empty-desc">点击「接入工具」登记 HTTP / MCP，或调整筛选条件</div>
          <el-button type="primary" style="margin-top: 12px" @click="router.push('/governance/tools/register')">
            接入工具
          </el-button>
        </div>
      </template>
    </el-table>
    <div class="table-foot">
      <span class="t-faint">共 {{ rows.length }} 条</span>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="rows.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="sizes, prev, pager, next"
        small
      />
    </div>
  </div>

  <!-- MCP 服务器：与业务工具同一套布局 -->
  <template v-if="view === 'mcp'">
    <div class="card filter-bar">
      <div class="filter-left">
        <el-input
          v-model="mcpQuery"
          placeholder="搜索服务器 / 地址 / 启用工具"
          clearable
          class="search-input"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>
      <div class="filter-right">
        <span class="count-pill">{{ filteredMcps.length }} / {{ mcps.length }}</span>
      </div>
    </div>

    <div class="card" v-loading="loading" style="margin-top: 12px">
      <el-table :data="pagedMcps" class="tool-table">
        <el-table-column label="服务器" min-width="280">
          <template #default="{ row }">
            <div class="t-name">
              <span
                class="dot"
                :style="{
                  background:
                    row.status === 'REGISTERED' || row.status === 'READY'
                      ? 'var(--ok)'
                      : 'var(--warn)',
                }"
              />
              <span class="name">{{ row.name }}</span>
              <span class="kind-chip">{{ row.transport || 'mcp' }}</span>
            </div>
            <div class="t-desc">{{ row.enableTools ? '已声明 enableTools 白名单' : '未声明 enableTools（不会注入 Toolkit）' }}</div>
            <div class="t-meta">{{ row.url || row.command || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <div class="st">
              <span
                class="dot"
                :style="{
                  background:
                    row.status === 'REGISTERED' || row.status === 'READY'
                      ? 'var(--ok)'
                      : 'var(--warn)',
                }"
              />
              <span>{{ row.status === 'REGISTERED' || row.status === 'READY' ? '可用' : row.status || '—' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="启用工具" min-width="200">
          <template #default="{ row }">
            <div class="tool-chips" v-if="row.enableTools">
              <span
                v-for="t in splitTools(row.enableTools).slice(0, 6)"
                :key="t"
                class="kind-chip"
              >{{ t }}</span>
              <span v-if="splitTools(row.enableTools).length > 6" class="t-meta">
                +{{ splitTools(row.enableTools).length - 6 }}
              </span>
            </div>
            <span v-else class="t-meta-block">—</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="160" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <div class="ops" @click.stop>
              <el-button size="small" type="primary" text @click="testMcp(row)">连通测试</el-button>
              <el-button
                size="small"
                text
                @click="router.push('/governance/tools/register')"
              >改配置</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="empty-icon">🔌</div>
            <div class="empty-title">暂无 MCP 服务器</div>
            <div class="empty-desc">登记后须显式 enableTools 才会注入 Agent Toolkit</div>
            <el-button type="primary" style="margin-top: 12px" @click="router.push('/governance/tools/register')">
              登记 MCP
            </el-button>
          </div>
        </template>
      </el-table>
      <div class="table-foot">
        <span class="t-faint">共 {{ filteredMcps.length }} 条</span>
        <el-pagination
          v-model:current-page="mcpPage"
          v-model:page-size="mcpPageSize"
          :total="filteredMcps.length"
          :page-sizes="[10, 20, 50, 100]"
          layout="sizes, prev, pager, next"
          small
        />
      </div>
      <div class="mcp-tip">
        未在 <b>enableTools</b> 中列出的工具不会挂到 Agent；建议先「连通测试」再授权。
      </div>
    </div>
  </template>

  <!-- 详情抽屉 -->
  <el-drawer v-model="detailVisible" size="720px" :title="detail?.toolName || '工具详情'">
    <template v-if="detail">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="overview">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="工具名">
              <b>{{ detail.toolName }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ kindLabel(detail.kind) }}</el-descriptions-item>
            <el-descriptions-item label="生命周期">
              <span class="st">
                <span class="dot" :style="{ background: statusDot(detail.status || detail.lifecycle) }" />
                {{ statusLabel(detail.status || detail.lifecycle) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="版本 / 环境">
              v{{ detail.version || 1 }} · {{ detail.env || 'prod' }}
            </el-descriptions-item>
            <el-descriptions-item label="风险">
              <span class="st">
                <span class="dot" :style="{ background: riskDot(detail.risk) }" />
                {{ detail.risk || 'medium' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="鉴权">{{ authLabel(detail.authMode) }}</el-descriptions-item>
            <el-descriptions-item label="只读">{{ detail.readOnly ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="权限码">{{ detail.permCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="责任人">
              {{ detail.owner || detail.ownerSystem || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="超时 / 重试">
              {{ detail.timeoutMs || 30000 }}ms / {{ detail.maxRetries ?? 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="密钥引用">{{ detail.secretRef || '—' }}</el-descriptions-item>
            <el-descriptions-item label="标签">{{ detail.tags || '—' }}</el-descriptions-item>
            <el-descriptions-item label="连接" :span="2">
              <span class="mono">{{ detail.method }} {{ detail.url || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="描述" :span="2">
              {{ detail.description || '—' }}
            </el-descriptions-item>
          </el-descriptions>
          <div class="drawer-actions">
            <el-button type="primary" :loading="testing" @click="runTest">试调用 / 连通</el-button>
            <el-button @click="setLifecycle(detail, 'ENABLED')">启用</el-button>
            <el-button @click="setLifecycle(detail, 'DISABLED')">停用</el-button>
            <el-button @click="setLifecycle(detail, 'DEPRECATED', 'console deprecate')">废弃</el-button>
            <el-button @click="cloneTool(detail)">克隆</el-button>
            <el-button type="danger" text @click="removeTool(detail)">下线</el-button>
          </div>
          <el-alert
            v-if="testResult"
            :type="testResult.ok ? 'success' : 'error'"
            :closable="false"
            style="margin-top: 12px"
            :title="
              testResult.ok
                ? `连通成功 · HTTP ${testResult.httpStatus ?? '—'} · ${testResult.durationMs}ms`
                : `失败：${testResult.error || testResult.note || ''}`
            "
          >
            <pre v-if="testResult.bodyPreview" class="mono pre-wrap">{{ testResult.bodyPreview }}</pre>
          </el-alert>
        </el-tab-pane>

        <el-tab-pane label="配置 JSON" name="config">
          <pre class="mono pre-wrap cfg">{{ prettyJson(detail.config) }}</pre>
        </el-tab-pane>

        <el-tab-pane label="Agent 装配" name="bindings">
          <el-alert
            type="info"
            :closable="false"
            title="有绑定行时仅绑定工具挂到对应 Agent；无绑定行兼容旧模型（挂载全部启用工具）。"
            style="margin-bottom: 12px"
          />
          <el-table :data="bindings" size="small">
            <el-table-column prop="agentId" label="Agent" width="140" />
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column label="挂载" width="100">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  @change="(v) => toggleBinding(row.agentId, detail.toolName, v)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="policy" label="策略覆盖" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="最近调用" name="audit">
          <el-table :data="toolAudit" size="small">
            <el-table-column prop="createdAt" label="时间" width="170" />
            <el-table-column prop="action" label="动作" width="140" />
            <el-table-column prop="resource" label="资源" min-width="140" />
            <el-table-column prop="actor" label="操作者" width="120" />
          </el-table>
          <el-empty v-if="!toolAudit.length" description="暂无审计记录" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-drawer>
</template>

<style scoped>
/* —— 工具 / MCP 同级切换 —— */
.view-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 12px;
}
.view-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  font-size: 14px;
  transition: all 0.15s;
}
.view-btn:hover {
  background: var(--bg-hover);
}
.view-btn.on {
  background: var(--brand-soft);
  color: var(--brand);
  border-color: color-mix(in srgb, var(--brand) 25%, transparent);
  font-weight: 600;
}
.view-label {
  line-height: 1;
}
.view-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  background: var(--bg-subtle);
  color: var(--ink-2);
}
.view-btn.on .view-count {
  background: var(--brand);
  color: #fff;
}
.view-spacer {
  flex: 1;
}
.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.mcp-tip {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--brand-softer);
  color: var(--ink-2);
  font-size: 12px;
  border-left: 3px solid var(--brand);
}
.mcp-tip b {
  color: var(--brand);
  font-weight: 600;
}

/* —— 指标 / 筛选 —— */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.filter-left {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.filter-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search-input {
  width: 280px;
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
}

/* —— 表格 —— */
.tool-table {
  cursor: pointer;
}
.tool-table :deep(.el-table__row) {
  transition: background 0.15s;
}
.tool-table :deep(.el-table__row:hover > td) {
  background: var(--brand-softer) !important;
}
.t-name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
}
.t-name .name {
  font-weight: 600;
  color: var(--ink-1);
  font-size: 14px;
}
.kind-chip {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-2);
  font-weight: 500;
}
.t-desc {
  font-size: 12px;
  color: var(--ink-2);
  margin: 0 0 2px 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 420px;
}
.t-meta {
  font-size: 11px;
  color: var(--ink-3);
  margin-left: 16px;
  font-family: var(--font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 420px;
}
.t-meta-block {
  font-size: 12px;
  color: var(--ink-2);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-1);
}
.ops {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

/* —— MCP / 区块 —— */
.section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.section-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--ink-1);
}
.mcp-table {
  --el-table-header-bg-color: var(--bg-subtle);
}

/* —— 分页 —— */
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.t-faint {
  font-size: 12px;
  color: var(--ink-3);
}

/* —— 空态 —— */
.empty {
  padding: 36px 16px 40px;
  text-align: center;
}
.empty-icon {
  font-size: 36px;
  margin-bottom: 8px;
}
.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-1);
}
.empty-desc {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 4px;
}

/* —— 抽屉 —— */
.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.pre-wrap {
  white-space: pre-wrap;
  word-break: break-all;
  margin: 8px 0 0;
  max-height: 280px;
  overflow: auto;
}
.cfg {
  background: var(--bg-subtle);
  padding: 12px;
  border-radius: 8px;
  color: var(--ink-1);
}
</style>
