<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import { listSkills, uploadSkill, approveSkill, rejectSkill, archiveSkill, setSkillVisible } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const skills = ref([])
const loading = ref(false)
const q = ref('')
const createOpen = ref(false)
const detail = ref(null)
const detailOpen = ref(false)
const form = ref({ name: '', version: '1.0.0', description: '', skillContent: '# skill\n回答时简洁。' })
const reviewNote = ref('')

const filtered = computed(() => {
  const s = q.value.trim().toLowerCase()
  if (!s) return skills.value
  return skills.value.filter(
    (x) =>
      String(x.name || '').toLowerCase().includes(s) ||
      String(x.description || '').toLowerCase().includes(s),
  )
})

async function load() {
  loading.value = true
  try {
    const data = await listSkills()
    skills.value = data.skills || data.items || []
  } catch (e) {
    ElMessage.error(e.message)
    skills.value = []
  } finally {
    loading.value = false
  }
}

async function create() {
  try {
    await uploadSkill({
      name: form.value.name || 'demo-skill',
      version: form.value.version,
      description: form.value.description || form.value.name,
      skillContent: form.value.skillContent,
    })
    ElMessage.success('技能草稿已上传')
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function approve(row) {
  try {
    await approveSkill(row.id, reviewNote.value || 'console approve')
    ElMessage.success('已通过审核')
    detail.value = null
    detailOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reject(row) {
  try {
    await rejectSkill(row.id, reviewNote.value || 'console reject')
    ElMessage.success('已驳回')
    detail.value = null
    detailOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function archive(row) {
  try {
    await ElMessageBox.confirm(`归档技能 ${row.name}？`, '确认', { type: 'warning' })
    await archiveSkill(row.id)
    ElMessage.success('已归档')
    await load()
  } catch {
    /* cancel */
  }
}

async function toggleVisible(row) {
  try {
    await setSkillVisible(row.id, !row.visible)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="技能市场" desc="上传技能草稿，经审核后对租户可见。审核通过会驱逐实例缓存，下轮生效。">
    <template #actions>
      <el-input v-model="q" placeholder="搜索技能" clearable style="width:220px" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="createOpen = true">上传技能</el-button>
    </template>
  </PageHeader>

  <div v-loading="loading" class="card card-pad">
    <EmptyState
      v-if="!filtered.length"
      title="没有技能"
      description="上传 Markdown 技能说明，审核通过后 Agent 可加载。"
      icon="Grid"
    >
      <el-button type="primary" @click="createOpen = true">上传技能</el-button>
    </EmptyState>

    <div v-else class="skill-grid">
      <div
        v-for="s in filtered"
        :key="s.id"
        class="skill-card"
        @click="detail = s; detailOpen = true"
      >
        <div class="skill-head">
          <div class="icon">S</div>
          <div>
            <div class="t-section">{{ s.name }}</div>
            <div class="t-faint mono">v{{ s.version }}</div>
          </div>
          <StatusTag :value="s.status || 'DRAFT'" />
        </div>
        <div class="t-muted clamp">{{ s.description }}</div>
        <div class="skill-actions" @click.stop>
          <el-button size="small" type="success" @click="approve(s)">通过</el-button>
          <el-button size="small" type="warning" plain @click="reject(s)">驳回</el-button>
          <el-button size="small" text type="info" @click="archive(s)">归档</el-button>
          <el-button size="small" text @click="toggleVisible(s)">
            {{ s.visible === false ? '已隐藏' : '可见' }}
          </el-button>
        </div>
      </div>
    </div>
  </div>

  <el-drawer v-model="createOpen" title="上传技能" size="520px">
    <el-form label-position="top">
      <el-form-item label="名称">
        <el-input v-model="form.name" placeholder="hello-skill" />
      </el-form-item>
      <el-form-item label="版本">
        <el-input v-model="form.version" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" />
      </el-form-item>
      <el-form-item label="技能内容（Markdown）">
        <el-input v-model="form.skillContent" type="textarea" :rows="12" class="mono" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createOpen = false">取消</el-button>
      <el-button type="primary" @click="create">上传草稿</el-button>
    </template>
  </el-drawer>

  <el-drawer v-model="detailOpen" title="技能详情 / 审核" size="560px" @close="detail = null">
    <template v-if="detail">
      <div class="t-page" style="font-size:20px">{{ detail.name }}</div>
      <div class="t-faint mono" style="margin:6px 0 12px">
        v{{ detail.version }} · {{ detail.status }}
      </div>
      <MarkdownView :text="detail.skillContent || detail.content || ''" />
      <el-divider />
      <el-input v-model="reviewNote" type="textarea" :rows="2" placeholder="审核意见" />
      <div style="display:flex;gap:8px;margin-top:12px">
        <el-button type="success" @click="approve(detail)">通过</el-button>
        <el-button type="warning" @click="reject(detail)">驳回</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.skill-card {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 14px;
  cursor: pointer;
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.skill-card:hover {
  border-color: color-mix(in srgb, var(--brand) 35%, var(--line));
  box-shadow: var(--shadow-md);
}
.skill-head {
  display: flex;
  gap: 10px;
  align-items: center;
}
.skill-head .icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-weight: 700;
}
.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.skill-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
