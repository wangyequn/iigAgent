<script setup>
import { onMounted, ref } from 'vue'
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
} from '@/api'
import { ElMessage } from 'element-plus'

const mcpList = ref([])
const bizTools = ref([])
const probe = ref(null)
const testing = ref(false)
const tab = ref('mcp')

const mcpForm = ref({
  name: 'demo-mcp',
  transport: 'stdio',
  command: 'python',
  args: 'scripts/mcp-demo-server.py',
  enableTools: 'echo_time',
})
const bizForm = ref({
  toolName: 'query_orders',
  displayName: '查询订单',
  description: '只读订单查询',
})

async function load() {
  try {
    mcpList.value = (await listMcpServers()).servers || []
    bizTools.value = (await listBusinessTools()).tools || []
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function registerMcp() {
  try {
    await registerMcpServer(mcpForm.value)
    ElMessage.success('MCP 已登记')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doTest() {
  testing.value = true
  try {
    probe.value = await mcpTest(mcpForm.value.name)
    ElMessage.success('探测完成')
  } catch (e) {
    ElMessage.error(e.message)
    probe.value = { error: e.message }
  } finally {
    testing.value = false
  }
}

async function registerBiz() {
  try {
    await registerBusinessTool(bizForm.value)
    ElMessage.success('业务工具已登记')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="MCP 与工具" desc="登记 MCP Server、业务工具；探测连通性并查看工具清单。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">MCP Server</div>
        <el-button size="small" :loading="testing" @click="doTest">探测连接</el-button>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="name">
            <el-input v-model="mcpForm.name" />
          </el-form-item>
          <el-form-item label="command">
            <el-input v-model="mcpForm.command" />
          </el-form-item>
          <el-form-item label="args">
            <el-input v-model="mcpForm.args" />
          </el-form-item>
          <el-form-item label="启用工具（逗号分隔）">
            <el-input v-model="mcpForm.enableTools" />
          </el-form-item>
          <el-button type="primary" @click="registerMcp">登记 MCP</el-button>
        </el-form>

        <JsonView v-if="probe" :data="probe" label="探测结果" max-height="180px" style="margin-top:12px" />

        <div class="t-section" style="margin:16px 0 8px">已登记</div>
        <EmptyState v-if="!mcpList.length" title="暂无 MCP" icon="Tools" />
        <el-table v-else :data="mcpList" size="small">
          <el-table-column prop="name" label="name" />
          <el-table-column prop="transport" label="transport" width="100" />
          <el-table-column prop="command" label="command" show-overflow-tooltip />
        </el-table>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">业务工具</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="toolName">
            <el-input v-model="bizForm.toolName" />
          </el-form-item>
          <el-form-item label="显示名">
            <el-input v-model="bizForm.displayName" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="bizForm.description" />
          </el-form-item>
          <el-button type="primary" @click="registerBiz">登记工具</el-button>
        </el-form>

        <div class="t-section" style="margin:16px 0 8px">工具清单</div>
        <EmptyState v-if="!bizTools.length" title="暂无业务工具" icon="Grid" />
        <div v-else class="tool-list">
          <div v-for="t in bizTools" :key="t.toolName" class="tool-item">
            <div>
              <div class="mono" style="font-weight:600">{{ t.toolName }}</div>
              <div class="t-faint">{{ t.displayName }} · {{ t.description }}</div>
            </div>
            <StatusTag value="ACTIVE" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
}
</style>
