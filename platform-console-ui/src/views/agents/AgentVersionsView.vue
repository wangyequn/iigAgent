<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { listVersions, createVersion, publishVersion, runtimeInstances } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const agentId = route.params.agentId

const versions = ref([])
const release = ref(null)
const instances = ref([])
const createOpen = ref(false)
const publishOpen = ref(false)
const targetVersion = ref(null)
const form = ref({
  modelId: 'deepseek:deepseek-flash',
  sysPrompt: '你是智能助手',
  workspace: '',
})
const publishing = ref(false)

async function load() {
  try {
    const data = await listVersions(agentId)
    versions.value = data.versions || []
    release.value = data.releaseVersion ?? data.release?.releaseVersion ?? null
  } catch (e) {
    ElMessage.error(e.message)
    versions.value = []
  }
  try {
    const inst = await runtimeInstances()
    instances.value = inst.instances || inst.items || []
  } catch {
    instances.value = []
  }
}

async function doCreate() {
  try {
    const body = {
      modelId: form.value.modelId,
      sysPrompt: form.value.sysPrompt,
    }
    if (form.value.workspace) body.workspace = form.value.workspace
    await createVersion(agentId, body)
    ElMessage.success('版本已创建')
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function askPublish(v) {
  targetVersion.value = v
  publishOpen.value = true
}

async function doPublish() {
  if (!targetVersion.value && targetVersion.value !== 0) return
  publishing.value = true
  try {
    await publishVersion(agentId, targetVersion.value)
    ElMessage.success(`已发布 v${targetVersion.value}，旧版本将 drain 后驱逐`)
    publishOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    publishing.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    :title="`版本与发布 · ${agentId}`"
    desc="建版后发布切流：build 成功才改 release 指针；旧版本 drain → 归零 → evict。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createOpen = true">新建版本</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">版本时间轴</div>
        <el-tag size="small" type="success">release v{{ release ?? '—' }}</el-tag>
      </div>
      <div class="card-pad">
        <EmptyState v-if="!versions.length" title="暂无版本" description="创建第一个版本并发布。" icon="Box" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="v in versions"
            :key="v.version"
            :timestamp="`v${v.version}`"
            :type="v.version === release ? 'success' : 'info'"
            placement="top"
          >
            <div class="ver-card">
              <div class="ver-head">
                <b>模型 {{ v.modelId || '—' }}</b>
                <StatusTag v-if="v.version === release" value="RELEASE" />
                <StatusTag v-else :value="v.status || 'IDLE'" />
              </div>
              <div class="t-muted clamp">{{ v.sysPrompt || '—' }}</div>
              <div class="actions">
                <el-button
                  v-if="v.version !== release"
                  size="small"
                  type="primary"
                  @click="askPublish(v.version)"
                >
                  发布切流
                </el-button>
                <el-button size="small" text @click="targetVersion = v.version">查看</el-button>
              </div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>

    <div class="space-col">
      <div class="card">
        <div class="card-header">
          <div class="title">版本详情</div>
        </div>
        <div class="card-pad">
          <JsonView
            :data="versions.find((v) => v.version === targetVersion) || versions[0] || null"
            label="version"
            max-height="280px"
          />
        </div>
      </div>

      <div class="card">
        <div class="card-header">
          <div class="title">运行时实例</div>
        </div>
        <div class="card-pad">
          <EmptyState v-if="!instances.length" title="无实例数据" description="进程内缓存实例在对话后出现。" icon="Monitor" />
          <el-table v-else :data="instances" size="small">
            <el-table-column prop="agentId" label="agent" />
            <el-table-column prop="version" label="v" width="70" />
            <el-table-column prop="status" label="status">
              <template #default="{ row }"><StatusTag :value="row.status || 'RUNNING'" /></template>
            </el-table-column>
            <el-table-column prop="inFlight" label="in-flight" width="90" />
          </el-table>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="createOpen" title="新建版本" width="520px">
    <el-form label-position="top">
      <el-form-item label="modelId">
        <el-input v-model="form.modelId" />
      </el-form-item>
      <el-form-item label="系统提示词">
        <el-input v-model="form.sysPrompt" type="textarea" :rows="5" />
      </el-form-item>
      <el-form-item label="workspace（可选）">
        <el-input v-model="form.workspace" placeholder="留空则 {AMP_WORKSPACE}/{tenant}/v{n}" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" @click="doCreate">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="publishOpen" title="确认发布切流" width="420px">
    <p>将把 <b class="mono">v{{ targetVersion }}</b> 设为 release。旧版本进入 drain，in-flight 归零后驱逐。</p>
    <p class="t-muted">此操作影响线上对话路由，请确认目标版本配置无误。</p>
    <template #footer>
      <el-button @click="publishOpen = false">取消</el-button>
      <el-button type="primary" :loading="publishing" @click="doPublish">确认发布</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.space-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ver-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px;
  background: var(--bg-surface);
}
.ver-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
</style>
