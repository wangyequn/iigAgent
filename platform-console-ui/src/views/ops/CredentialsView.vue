<script setup>
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { listModelCredentials, upsertModelCredential, deleteModelCredential } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const list = ref([])
const loading = ref(false)
const form = ref({ provider: 'deepseek', apiKey: '', baseUrl: '' })

const providers = ['deepseek', 'dashscope', 'openai', 'glm', 'kimi']

async function load() {
  loading.value = true
  try {
    const data = await listModelCredentials()
    list.value = data.credentials || data.items || (Array.isArray(data) ? data : [])
  } catch (e) {
    ElMessage.error(e.message)
    list.value = []
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value.apiKey) {
    ElMessage.warning('填写 API Key')
    return
  }
  try {
    await upsertModelCredential({ ...form.value })
    ElMessage.success('凭证已保存')
    form.value.apiKey = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function remove(provider) {
  try {
    await ElMessageBox.confirm(`删除 ${provider} 凭证？将回退环境变量。`, '确认', { type: 'warning' })
    await deleteModelCredential(provider)
    await load()
  } catch {
    /* cancel */
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="模型凭证" desc="按 provider 配置模型 API Key。展示打码；未配置时回退环境变量。">
    <template #actions>
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">添加 / 更新</div>
      </div>
      <div class="card-pad">
        <el-form label-position="top">
          <el-form-item label="provider">
            <el-select v-model="form.provider" style="width:100%">
              <el-option v-for="p in providers" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="form.apiKey" type="password" show-password />
          </el-form-item>
          <el-form-item label="baseUrl（可选）">
            <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
          </el-form-item>
          <el-button type="primary" @click="save">保存凭证</el-button>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">已配置</div>
      </div>
      <div class="card-pad" v-loading="loading">
        <EmptyState v-if="!list.length" title="暂无凭证" description="未配置时使用 DEEPSEEK_API_KEY 等环境变量。" icon="Key" />
        <el-table v-else :data="list" size="small">
          <el-table-column prop="provider" label="provider" width="110" />
          <el-table-column prop="apiKeyMasked" label="key" width="120">
            <template #default="{ row }"><span class="mono">{{ row.apiKeyMasked || '••••' }}</span></template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="baseUrl" show-overflow-tooltip />
          <el-table-column label="op" width="90">
            <template #default="{ row }">
              <el-button size="small" type="danger" text @click="remove(row.provider)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>
