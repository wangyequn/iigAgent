<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { listKnowledgeDocs, uploadKnowledge, searchKnowledge, knowledgeStatus, uploadKnowledgeFiles } from '@/api'
import { ElMessage } from 'element-plus'

const docs = ref([])
const hits = ref([])
const query = ref('使用指南')
const topK = ref(5)
const title = ref('')
const content = ref('')
const loading = ref(false)
const searching = ref(false)
const tab = ref('docs')
const provider = ref('simple')
const fileList = ref([])
const uploading = ref(false)
const uploadMode = ref('file')

const acceptTypes = computed(() =>
  provider.value === 'dify'
    ? '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.html,.xml,.json,.yml,.yaml,.png,.jpg,.jpeg,.webp'
    : '.txt,.md,.markdown,.csv,.tsv,.json,.log,.yml,.yaml,.html,.htm,.xml',
)

const acceptHint = computed(() =>
  provider.value === 'dify'
    ? 'Dify 解析：PDF / Word / PPT / Excel / 图片 / 文本 等'
    : 'Simple 抽取：txt / md / csv / json / html / yaml 等文本类',
)

async function loadStatus() {
  try {
    const data = await knowledgeStatus()
    provider.value = data.provider || 'simple'
  } catch {
    provider.value = 'simple'
  }
}

async function load() {
  loading.value = true
  await loadStatus()
  try {
    const data = await listKnowledgeDocs()
    docs.value = data.documents || data.docs || data.items || (Array.isArray(data) ? data : [])
    if (data.provider) provider.value = data.provider
  } catch (e) {
    ElMessage.error(e.message)
    docs.value = []
  } finally {
    loading.value = false
  }
}

async function doUpload() {
  if (!title.value.trim() || !content.value.trim()) {
    ElMessage.warning('请填写标题与内容')
    return
  }
  try {
    await uploadKnowledge(title.value.trim(), content.value.trim())
    ElMessage.success('知识已入库')
    title.value = ''
    content.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onFileChange(file, list) {
  fileList.value = list || []
}

function onFileRemove(file, list) {
  fileList.value = list || []
}

async function doUploadFiles() {
  if (!fileList.value.length) {
    ElMessage.warning('请选择文件')
    return
  }
  uploading.value = true
  try {
    const files = fileList.value.map((x) => x.raw || x).filter(Boolean)
    const data = await uploadKnowledgeFiles(files)
    const ok = data.uploaded?.length || 0
    const bad = data.failed?.length || 0
    if (ok) ElMessage.success(`已上传 ${ok} 个文件（provider=${data.provider}）`)
    if (bad) {
      ElMessage.warning(`失败 ${bad} 个：${data.failed.map((x) => x.filename).join(', ')}`)
    }
    fileList.value = []
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
  }
}

async function doSearch() {
  searching.value = true
  try {
    const data = await searchKnowledge(query.value, topK.value)
    hits.value = data.hits || []
    tab.value = 'search'
  } catch (e) {
    ElMessage.error(e.message)
    hits.value = []
  } finally {
    searching.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    title="知识库 RAG"
    desc="管理知识文档、分块与语义检索。对话中的 knowledge_search 引用会以 docId#chunkId 形式出现。"
  >
    <template #actions>
      <el-tag :type="provider === 'dify' ? 'primary' : 'info'" effect="light">
        Provider · {{ provider === 'dify' ? 'Dify' : 'Simple' }}
      </el-tag>
      <el-button @click="load">刷新文档</el-button>
    </template>
  </PageHeader>

  <div class="grid-2">
    <div class="card">
      <div class="card-header">
        <div class="title">入库</div>
        <el-radio-group v-model="uploadMode" size="small">
          <el-radio-button value="file">上传文件</el-radio-button>
          <el-radio-button value="text">粘贴文本</el-radio-button>
        </el-radio-group>
      </div>
      <div class="card-pad">
        <template v-if="uploadMode === 'file'">
          <el-upload
            drag
            multiple
            :auto-upload="false"
            :limit="20"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :accept="acceptTypes"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em>（可多选）</div>
            <template #tip>
              <div class="t-faint" style="margin-top:6px">{{ acceptHint }}</div>
            </template>
          </el-upload>
          <el-button
            type="primary"
            style="margin-top:12px"
            :loading="uploading"
            :disabled="!fileList.length"
            @click="doUploadFiles"
          >
            上传 {{ fileList.length || '' }} 个文件
          </el-button>
        </template>
        <template v-else>
          <el-form label-position="top">
            <el-form-item label="标题">
              <el-input v-model="title" placeholder="例如：退货政策" />
            </el-form-item>
            <el-form-item label="内容">
              <el-input v-model="content" type="textarea" :rows="8" placeholder="粘贴知识原文…" />
            </el-form-item>
            <el-button type="primary" @click="doUpload">上传文档</el-button>
          </el-form>
        </template>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="title">语义检索</div>
        <el-radio-group v-model="tab" size="small">
          <el-radio-button value="docs">文档</el-radio-button>
          <el-radio-button value="search">命中</el-radio-button>
        </el-radio-group>
      </div>
      <div class="card-pad">
        <div class="search-row">
          <el-input
            v-model="query"
            placeholder="输入问题或关键词"
            clearable
            @keyup.enter="doSearch"
          />
          <el-input-number v-model="topK" :min="1" :max="20" />
          <el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button>
        </div>

        <template v-if="tab === 'docs'">
          <div v-loading="loading" class="doc-list">
            <EmptyState
              v-if="!docs.length"
              title="还没有知识文档"
              description="在左侧粘贴业务知识，让 Agent 具备可检索的领域事实。"
              icon="Collection"
            />
            <div v-for="d in docs" :key="d.id || d.docId || d.title" class="doc-card">
              <div class="doc-title">{{ d.title }}</div>
              <div class="t-faint mono">{{ d.id || d.docId }} · 分块 {{ d.chunkCount ?? d.chunks ?? '—' }}</div>
              <div class="t-muted clamp">{{ d.summary || d.content || d.text || '' }}</div>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="doc-list">
            <EmptyState
              v-if="!hits.length"
              title="无命中"
              description="换个说法再试试，或先入库相关文档。"
              icon="Search"
            />
            <div v-for="h in hits" :key="h.citation || h.chunkId || h.text" class="hit-card">
              <div class="mono cite">{{ h.citation || `${h.docId}#${h.chunkId}` }}</div>
              <div class="t-body">{{ h.text || h.content }}</div>
              <div class="t-faint">score {{ h.score ?? h.similarity ?? '—' }}</div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.search-row .el-input {
  flex: 1;
}
.doc-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 520px;
  overflow: auto;
}
.doc-card,
.hit-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  background: var(--bg-surface);
}
.doc-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 6px;
}
.cite {
  color: var(--brand);
  margin-bottom: 6px;
}
</style>
