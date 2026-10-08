<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { listPermissions, upsertPermission, setPermissionMode } from '@/api'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'

const store = useAppStore()
const permissions = ref([])
const mode = ref('DEFAULT')
const form = ref({
  agentId: 'demo',
  toolName: 'dangerous_write',
  behavior: 'ASK',
  ruleContent: '下单需确认',
})
const loading = ref(false)

const modes = [
  { value: 'DEFAULT', label: 'DEFAULT', desc: '按规则逐条判定' },
  { value: 'ALLOW_ALL', label: 'ALLOW_ALL', desc: '全部放行' },
  { value: 'ASK_ALL', label: 'ASK_ALL', desc: '每次询问' },
  { value: 'DENY_ALL', label: 'DENY_ALL', desc: '全部拒绝' },
]

async function load() {
  loading.value = true
  try {
    const data = await listPermissions()
    const list = Array.isArray(data)
      ? data
      : data.rules || data.permissions || data.items || []
    permissions.value = list
  } catch (e) {
    ElMessage.error(e.message)
    permissions.value = []
  } finally {
    loading.value = false
  }
}

async function saveRule() {
  try {
    await upsertPermission({ ...form.value, agentId: form.value.agentId || store.agentId })
    ElMessage.success('规则已保存并重同步会话状态')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveMode() {
  try {
    await setPermissionMode({ agentId: store.agentId, mode: mode.value })
    ElMessage.success('Mode 已设置')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="权限与模式"
    desc="工具权限矩阵：ALLOW / ASK / DENY。变更会落库 + 驱逐实例 + 重同步 Redis 会话状态。"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="card card-pad">
    <div class="t-section">模式（Mode）</div>
    <div class="t-muted" style="margin:6px 0 12px">
      当前 Agent <b class="mono">{{ store.agentId }}</b> · mode 会覆盖/合并到权限上下文
    </div>
    <div class="mode-grid">
      <button
        v-for="m in modes"
        :key="m.value"
        type="button"
        class="mode-card"
        :class="{ active: mode === m.value }"
        @click="mode = m.value"
      >
        <div class="mono">{{ m.label }}</div>
        <div class="t-faint">{{ m.desc }}</div>
      </button>
    </div>
    <el-button type="primary" style="margin-top:12px" @click="saveMode">保存 Mode</el-button>
  </div>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">新增 / 更新规则</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="agentId">
            <el-input v-model="form.agentId" />
          </el-form-item>
          <el-form-item label="toolName">
            <el-input v-model="form.toolName" placeholder="dangerous_write" />
          </el-form-item>
          <el-form-item label="behavior">
            <el-radio-group v-model="form.behavior">
              <el-radio-button value="ALLOW">ALLOW</el-radio-button>
              <el-radio-button value="ASK">ASK</el-radio-button>
              <el-radio-button value="DENY">DENY</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="ruleContent">
            <el-input v-model="form.ruleContent" placeholder="* 表示不限内容" />
            <div class="t-faint" style="margin-top:6px">
              注意：引擎匹配非文件工具时 <span class="mono">*</span> 会映射为 null（通配）。不要把 * 原样传入底层引擎。
            </div>
          </el-form-item>
          <el-button type="primary" @click="saveRule">保存规则</el-button>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">权限矩阵</div>
        <span class="t-faint">{{ permissions.length }} 条</span>
      </div>
      <div class="card-pad" v-loading="loading">
        <EmptyState v-if="!permissions.length" title="暂无权限规则" description="为敏感工具配置 ASK / DENY。" icon="Lock" />
        <div v-else class="matrix">
          <div class="matrix-head">
            <span>工具</span>
            <span>ALLOW</span>
            <span>ASK</span>
            <span>DENY</span>
            <span>规则</span>
          </div>
          <div v-for="p in permissions" :key="p.id || p.toolName + p.behavior" class="matrix-row">
            <span class="mono">{{ p.toolName }}</span>
            <span><el-radio :model-value="p.behavior === 'ALLOW'" disabled /></span>
            <span><el-radio :model-value="p.behavior === 'ASK'" disabled /></span>
            <span><el-radio :model-value="p.behavior === 'DENY'" disabled /></span>
            <span class="t-faint">{{ p.ruleContent || '*' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mode-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.mode-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px;
  text-align: left;
  background: var(--bg-surface);
  cursor: pointer;
}
.mode-card.active {
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand);
}
.matrix {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.matrix-head,
.matrix-row {
  display: grid;
  grid-template-columns: 1.2fr 0.6fr 0.6fr 0.6fr 1.4fr;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}
.matrix-head {
  color: var(--ink-3);
  font-weight: 600;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--line);
}
.matrix-row {
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
}

@media (max-width: 900px) {
  .mode-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
