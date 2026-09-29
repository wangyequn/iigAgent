<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { sandboxStatus, sandboxExec, fsStatus, fsItems } from '@/api'
import { ElMessage } from 'element-plus'

const sandbox = ref(null)
const fs = ref(null)
const items = ref([])
const command = ref('echo hello-from-sandbox')
const output = ref(null)
const running = ref(false)

async function load() {
  try {
    sandbox.value = await sandboxStatus()
  } catch (e) {
    sandbox.value = { enabled: false, error: e.message }
  }
  try {
    fs.value = await fsStatus()
  } catch {
    fs.value = null
  }
  try {
    const data = await fsItems(30)
    items.value = data.items || data.files || (Array.isArray(data) ? data : [])
  } catch {
    items.value = []
  }
}

async function exec() {
  running.value = true
  output.value = null
  try {
    output.value = await sandboxExec(command.value)
    ElMessage.success('执行完成')
  } catch (e) {
    output.value = { error: e.message, code: e.code }
    ElMessage.error(e.message)
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="沙箱 / FS" desc="Docker 沙箱命令执行与分布式文件系统浏览。默认沙箱关闭（SANDBOX_DISABLED）。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">沙箱执行</div>
        <StatusTag :value="sandbox?.enabled ? 'ACTIVE' : 'DISABLED'" />
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="command">
            <el-input v-model="command" placeholder="echo hello" />
          </el-form-item>
          <el-button type="primary" :loading="running" :disabled="!sandbox?.enabled" @click="exec">
            执行
          </el-button>
        </el-form>
        <div v-if="!sandbox?.enabled" class="t-faint" style="margin-top:8px">
          沙箱未启用（amp.sandbox.*）。启用后可用 sandbox_exec 工具在隔离环境执行命令。
        </div>
        <JsonView v-if="output" :data="output" label="执行结果" max-height="220px" style="margin-top:12px" />
        <JsonView :data="sandbox" label="沙箱状态" max-height="160px" style="margin-top:12px" />
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">FS 条目</div>
        <span class="t-faint">{{ fs?.mode || '—' }} · {{ fs?.itemCount ?? items.length }}</span>
      </div>
      <div class="card-pad">
        <EmptyState v-if="!items.length" title="暂无文件" description="workspace 文件会出现在这里。" icon="Folder" />
        <el-table v-else :data="items" size="small" max-height="420">
          <el-table-column prop="pathKey" label="pathKey" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.pathKey || row.path }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="name" />
          <el-table-column prop="version" label="ver" width="80" />
        </el-table>
      </div>
    </div>
  </div>
</template>
