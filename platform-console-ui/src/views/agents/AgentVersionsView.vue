<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import JsonView from '@/components/ui/JsonView.vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import { listVersions, createVersion, publishVersion, runtimeInstances } from '@/api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const agentId = route.params.agentId

const versions = ref([])
const release = ref(null)
const instances = ref([])
const loading = ref(false)
const createOpen = ref(false)
const publishOpen = ref(false)
const targetVersion = ref(null)
const previewVersion = ref(null)
const publishing = ref(false)
const creating = ref(false)
const form = ref({
  modelId: 'deepseek:deepseek-flash',
  sysPrompt: '你是智能助手，用中文简洁回答。',
  workspace: '',
})

function asArray(data, ...keys) {
  if (Array.isArray(data)) return data
  for (const k of keys) if (Array.isArray(data?.[k])) return data[k]
  return []
}

async function load() {
  loading.value = true
  try {
    const data = await listVersions(agentId)
    versions.value = asArray(data, 'versions')
    release.value = data.releaseVersion ?? data.release?.releaseVersion ?? null
    if (!previewVersion.value && versions.value.length) {
      previewVersion.value = versions.value[0]
    }
  } catch (e) {
    ElMessage.error(e.message)
    versions.value = []
  }
  try {
    const inst = await runtimeInstances()
    instances.value = asArray(inst, 'instances', 'items')
  } catch {
    instances.value = []
  } finally {
    loading.value = false
  }
}

async function doCreate() {
  creating.value = true
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
  } finally {
    creating.value = false
  }
}

function askPublish(v) {
  targetVersion.value = v
  publishOpen.value = true
}

async function doPublish() {
  if (targetVersion.value == null) return
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
    :back="`/agents/${agentId}`"
    back-label="Agent 详情"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createOpen = true">新建版本</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card" v-loading="loading">
      <div class="card-header">
        <div class="title">版本时间轴</div>
        <el-tag size="small" type="success">release v{{ release ?? '—' }}</el-tag>
      </div>
      <div class="card-pad">
        <EmptyState
          v-if="!versions.length"
          title="暂无版本"
          description="创建第一个版本并发布，即可在对话中使用该 Agent。"
          icon="Box"
        >
          <el-button type="primary" @click="createOpen = true">新建版本</el-button>
        </EmptyState>
        <el-timeline v-else>
          <el-timeline-item
            v-for="v in versions"
            :key="v.version"
            :timestamp="`v${v.version}`"
            :type="v.version === release ? 'success' : 'info'"
            placement="top"
          >
            <div
              class="ver-card"
              :class="{ active: previewVersion?.version === v.version }"
              @click="previewVersion = v"
            >
              <div class="ver-head">
                <b>模型 {{ v.modelId || '—' }}</b>
                <StatusTag v-if="v.version === release" value="RELEASE" />
                <StatusTag v-else :value="v.status || 'IDLE'" />
              </div>
              <div class="t-muted clamp">{{ v.sysPrompt || '—' }}</div>
              <div class="actions" @click.stop>
                <el-button v-if="v.version !== release" size="small" type="primary" @click="askPublish(v.version)">
                  发布切流
                </el-button>
                <el-button size="small" text @click="previewVersion = v">查看提示词</el-button>
              </div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>

    <div class="space-col">
      <div class="card">
        <div class="card-header">
          <div class="title">提示词预览</div>
          <span class="t-faint mono">{{ previewVersion ? `v${previewVersion.version}` : '—' }}</span>
        </div>
        <div class="card-pad">
          <MarkdownView :text="previewVersion?.sysPrompt || '点击左侧版本查看提示词。'" />
          <JsonView
            v-if="previewVersion"
            :data="previewVersion"
            label="version meta"
            max-height="180px"
            style="margin-top:12px"
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
            <el-table-column label="status" width="110">
              <template #default="{ row }"><StatusTag :value="row.status || 'RUNNING'" /></template>
            </el-table-column>
            <el-table-column prop="inFlight" label="in-flight" width="90" />
          </el-table>
        </div>
      </div>
    </div>
  </div>

  <el-dialog v-model="createOpen" title="新建版本" width="720px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="modelId">
        <el-input v-model="form.modelId" />
      </el-form-item>
      <el-form-item label="系统提示词（Markdown）">
        <MarkdownEditor v-model="form.sysPrompt" :rows="10" />
      </el-form-item>
      <el-form-item label="workspace（可选）">
        <el-input v-model="form.workspace" placeholder="留空则 {AMP_WORKSPACE}/{tenant}/v{n}" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
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
  cursor: pointer;
}
.ver-card:hover,
.ver-card.active {
  border-color: color-mix(in srgb, var(--brand) 40%, var(--line));
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
