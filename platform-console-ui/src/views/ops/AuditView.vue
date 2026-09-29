<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { listAudit } from '@/api'
import { ElMessage } from 'element-plus'

const items = ref([])
const q = ref('')
const loading = ref(false)

const filtered = computed(() => {
  const s = q.value.trim().toLowerCase()
  if (!s) return items.value
  return items.value.filter(
    (x) =>
      String(x.action || '').toLowerCase().includes(s) ||
      String(x.path || '').toLowerCase().includes(s) ||
      String(x.actor || '').toLowerCase().includes(s),
  )
})

async function load() {
  loading.value = true
  try {
    const data = await listAudit(100)
    items.value = data.items || data.audit || data.logs || (Array.isArray(data) ? data : [])
  } catch (e) {
    ElMessage.error(e.message)
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="审计" desc="管理面写操作、HITL confirm、interrupt 等自动落审计日志。">
    <template #actions>
      <el-input v-model="q" placeholder="筛选 action / path / actor" clearable style="width:240px" />
      <el-button @click="load">刷新</el-button>
    </template>
  </PageHeader>

  <div class="card card-pad" v-loading="loading">
    <EmptyState v-if="!filtered.length" title="暂无审计记录" description="管理台写操作会出现在这里。" icon="Tickets" />
    <div v-else class="audit-list">
      <div v-for="(a, i) in filtered" :key="i" class="audit-item">
        <div class="left">
          <StatusTag :value="String(a.statusCode) === '200' || String(a.statusCode) === '201' ? 'OK' : a.statusCode >= 400 ? 'FAILED' : 'INFO'">
            HTTP {{ a.statusCode }}
          </StatusTag>
          <div>
            <div class="t-body mono">{{ a.action }}</div>
            <div class="t-faint mono">{{ a.path }}</div>
          </div>
        </div>
        <div class="right">
          <div class="t-muted mono">{{ a.actor }}</div>
          <div class="t-faint">{{ a.at || a.createdAt || a.time }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.audit-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.audit-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
}
.left {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.right {
  text-align: right;
}
</style>
