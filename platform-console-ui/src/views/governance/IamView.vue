<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import JsonView from '@/components/ui/JsonView.vue'
import { listIamRoles, listIamUsers, assignIamRole, revokeIamRole } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const roles = ref(null)
const users = ref([])
const form = ref({ userId: '', roleCode: 'developer' })
const loading = ref(false)

const roleOptions = [
  { value: 'developer', label: 'developer · 开发' },
  { value: 'ops', label: 'ops · 运维' },
  { value: 'auditor', label: 'auditor · 审计' },
  { value: 'skill_reviewer', label: 'skill_reviewer · 技能审核' },
  { value: 'tenant_admin', label: 'tenant_admin · 租户管理' },
  { value: 'agent_caller', label: 'agent_caller · 调用' },
]

async function load() {
  loading.value = true
  try {
    roles.value = await listIamRoles()
  } catch (e) {
    roles.value = { error: e.message }
  }
  try {
    users.value = (await listIamUsers()).users || (await listIamUsers())
    if (!Array.isArray(users.value)) users.value = users.value.users || users.value.items || []
  } catch (e) {
    ElMessage.error(e.message)
    users.value = []
  } finally {
    loading.value = false
  }
}

async function assign() {
  if (!form.value.userId) {
    ElMessage.warning('填写 userId')
    return
  }
  try {
    await assignIamRole(form.value.userId, form.value.roleCode)
    ElMessage.success('角色已授予')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function revoke(userId, roleCode) {
  try {
    await ElMessageBox.confirm(`撤销 ${userId} 的 ${roleCode}？`, '确认', { type: 'warning' })
    await revokeIamRole(userId, roleCode)
    await load()
  } catch {
    /* cancel */
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="IAM / RBAC" desc="管理用户角色。admin scope 的 Key 直接放行；业务请求头 X-User-Id / X-User-Roles。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">授予角色</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="userId">
            <el-input v-model="form.userId" placeholder="alice" />
          </el-form-item>
          <el-form-item label="roleCode">
            <el-select v-model="form.roleCode" style="width:100%">
              <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
          </el-form-item>
          <el-button type="primary" @click="assign">授予</el-button>
        </el-form>

        <div class="t-section" style="margin:18px 0 8px">角色定义</div>
        <JsonView :data="roles" label="roles" max-height="240px" />
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">用户与角色</div>
        <span class="t-faint">{{ users.length }} 人</span>
      </div>
      <div class="card-pad" v-loading="loading">
        <EmptyState v-if="!users.length" title="暂无用户" description="授予角色后出现在这里。" icon="Avatar" />
        <div v-else class="user-list">
          <div v-for="u in users" :key="u.userId" class="user-card">
            <div>
              <div class="t-section mono">{{ u.userId }}</div>
              <div class="chips">
                <el-tag
                  v-for="r in u.roles || []"
                  :key="r"
                  size="small"
                  closable
                  @close="revoke(u.userId, r)"
                >
                  {{ r }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.user-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
</style>
