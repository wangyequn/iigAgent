<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import { upsertWorkflow, publishWorkflowRevision, listWorkflows } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const workflowId = route.params.workflowId

const name = ref('演示流程')
const zoom = ref(1)
const selectedId = ref(null)
const dirty = ref(false)
const saving = ref(false)
const jsonOpen = ref(false)

const nodeTypes = {
  ECHO: { label: 'ECHO', color: '#64748b', desc: '回显输入，调试用' },
  AGENT: { label: 'AGENT', color: '#4f46e5', desc: '调用 Agent 对话' },
  HUMAN_GATE: { label: 'HUMAN_GATE', color: '#d97706', desc: '人工门禁确认' },
}

const state = reactive({
  nodes: [
    {
      id: 's1',
      type: 'ECHO',
      x: 80,
      y: 160,
      data: { input: '准备排查', next: 'gate' },
    },
    {
      id: 'gate',
      type: 'HUMAN_GATE',
      x: 340,
      y: 160,
      data: { prompt: '是否继续调用 Agent？', next: 's2' },
    },
    {
      id: 's2',
      type: 'AGENT',
      x: 600,
      y: 160,
      data: { agentId: 'demo', input: '总结今日订单异常', next: '' },
    },
  ],
})

const nodes = computed(() => state.nodes)
const selected = computed(() => state.nodes.find((n) => n.id === selectedId.value))

const edges = computed(() => {
  const list = []
  for (const n of state.nodes) {
    const next = n.data?.next
    if (!next) continue
    const target = state.nodes.find((t) => t.id === next)
    if (!target) continue
    list.push({ from: n, to: target })
  }
  return list
})

function nodeSize(n) {
  return { w: 180, h: 72 }
}

function edgePath(e) {
  const a = nodeSize(e.from)
  const b = nodeSize(e.to)
  const x1 = e.from.x + a.w
  const y1 = e.from.y + a.h / 2
  const x2 = e.to.x
  const y2 = e.to.y + b.h / 2
  const dx = Math.max(40, Math.abs(x2 - x1) * 0.5)
  return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
}

let drag = null

function onNodeDown(e, n) {
  e.stopPropagation()
  selectedId.value = n.id
  drag = {
    id: n.id,
    startX: e.clientX,
    startY: e.clientY,
    origX: n.x,
    origY: n.y,
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function onMove(e) {
  if (!drag) return
  const n = state.nodes.find((x) => x.id === drag.id)
  if (!n) return
  n.x = Math.max(0, drag.origX + (e.clientX - drag.startX) / zoom.value)
  n.y = Math.max(0, drag.origY + (e.clientY - drag.startY) / zoom.value)
  dirty.value = true
}

function onUp() {
  drag = null
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseup', onUp)
}

function addNode(type) {
  const id = `${type.toLowerCase()}_${Date.now().toString(36).slice(-4)}`
  state.nodes.push({
    id,
    type,
    x: 120 + (state.nodes.length % 3) * 220,
    y: 80 + Math.floor(state.nodes.length / 3) * 120,
    data:
      type === 'AGENT'
        ? { agentId: 'demo', input: '新任务', next: '' }
        : type === 'HUMAN_GATE'
          ? { prompt: '请确认', next: '' }
          : { input: 'echo', next: '' },
  })
  selectedId.value = id
  dirty.value = true
}

function removeSelected() {
  if (!selectedId.value) return
  state.nodes = state.nodes.filter((n) => n.id !== selectedId.value)
  for (const n of state.nodes) {
    if (n.data?.next === selectedId.value) n.data.next = ''
  }
  selectedId.value = null
  dirty.value = true
}

function setNext(n, next) {
  n.data.next = next
  dirty.value = true
}

const definitionJson = computed(() =>
  JSON.stringify(
    {
      steps: state.nodes.map((n) => ({
        id: n.id,
        type: n.type,
        ...n.data,
      })),
    },
    null,
    2,
  ),
)

function loadFromJson() {
  try {
    const obj = JSON.parse(jsonDraft.value)
    const steps = obj.steps || []
    state.nodes = steps.map((s, i) => ({
      id: s.id,
      type: s.type,
      x: 80 + (i % 3) * 260,
      y: 120 + Math.floor(i / 3) * 120,
      data: {
        input: s.input ?? '',
        prompt: s.prompt ?? '',
        agentId: s.agentId ?? '',
        next: s.next ?? '',
      },
    }))
    jsonOpen.value = false
    dirty.value = true
    ElMessage.success('已从 JSON 同步画布')
  } catch {
    ElMessage.error('JSON 不合法')
  }
}

const jsonDraft = ref(definitionJson.value)

watch(
  () => definitionJson.value,
  (v) => {
    if (!jsonOpen.value) jsonDraft.value = v
  },
)

async function save() {
  saving.value = true
  try {
    await upsertWorkflow(workflowId, {
      name: name.value,
      definition: JSON.parse(definitionJson.value),
    })
    dirty.value = false
    ElMessage.success('Workflow 已保存')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function publish() {
  try {
    await save()
    await publishWorkflowRevision(workflowId)
    ElMessage.success('Revision 已发布')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  try {
    const data = await listWorkflows()
    const list = data.workflows || data.items || []
    const found = list.find((w) => (w.workflowId || w.id) === workflowId)
    if (found?.name) name.value = found.name
    const def = found?.definition || found?.definitionJson
    if (def) {
      const obj = typeof def === 'string' ? JSON.parse(def) : def
      if (obj.steps?.length) {
        state.nodes = obj.steps.map((s, i) => ({
          id: s.id,
          type: s.type,
          x: 80 + (i % 3) * 260,
          y: 120 + Math.floor(i / 3) * 120,
          data: {
            input: s.input ?? '',
            prompt: s.prompt ?? '',
            agentId: s.agentId ?? '',
            next: s.next ?? '',
          },
        }))
      }
    }
  } catch {
    /* keep default demo graph */
  }
})
</script>

<template>
  <PageHeader
    :title="`Workflow 编辑器 · ${workflowId}`"
    desc="拖拽节点、连线下一步、配置属性。支持 ECHO / AGENT / HUMAN_GATE。"
  >
    <template #actions>
      <el-tag v-if="dirty" type="warning" size="small" effect="light">未保存</el-tag>
      <el-button @click="jsonOpen = true">JSON 模式</el-button>
      <el-button :loading="saving" @click="save">保存</el-button>
      <el-button type="primary" :loading="saving" @click="publish">保存并发布</el-button>
    </template>
  </PageHeader>

  <div class="editor-shell card">
    <aside class="palette">
      <div class="t-faint" style="margin-bottom:8px">节点库</div>
      <button
        v-for="(meta, type) in nodeTypes"
        :key="type"
        type="button"
        class="palette-item"
        @click="addNode(type)"
      >
        <span class="dot" :style="{ background: meta.color }" />
        <div>
          <div class="mono" style="font-weight:600">{{ meta.label }}</div>
          <div class="t-faint">{{ meta.desc }}</div>
        </div>
      </button>

      <div class="palette-divider" />
      <div class="t-faint" style="margin-bottom:8px">画布</div>
      <div class="zoom-row">
        <el-button size="small" @click="zoom = Math.max(0.5, zoom - 0.1)">−</el-button>
        <span class="t-faint">{{ Math.round(zoom * 100) }}%</span>
        <el-button size="small" @click="zoom = Math.min(1.6, zoom + 0.1)">+</el-button>
      </div>
      <el-button
        v-if="selected"
        size="small"
        type="danger"
        plain
        style="width:100%;margin-top:10px"
        @click="removeSelected"
      >
        删除节点
      </el-button>
    </aside>

    <div class="canvas-wrap" @click="selectedId = null">
      <div class="canvas" :style="{ transform: `scale(${zoom})` }">
        <svg class="edges" width="100%" height="100%">
          <defs>
            <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
              <path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8" />
            </marker>
          </defs>
          <path
            v-for="(e, i) in edges"
            :key="i"
            :d="edgePath(e)"
            fill="none"
            stroke="#94a3b8"
            stroke-width="1.8"
            marker-end="url(#arrow)"
          />
        </svg>

        <div
          v-for="n in nodes"
          :key="n.id"
          class="node"
          :class="{ active: selectedId === n.id }"
          :style="{
            left: n.x + 'px',
            top: n.y + 'px',
            borderColor: nodeTypes[n.type]?.color,
          }"
          @mousedown="onNodeDown($event, n)"
        >
          <div class="node-type mono" :style="{ color: nodeTypes[n.type]?.color }">
            {{ nodeTypes[n.type]?.label }}
          </div>
          <div class="node-id mono">{{ n.id }}</div>
          <div class="node-preview t-faint">
            {{ n.type === 'AGENT' ? n.data.agentId : n.type === 'HUMAN_GATE' ? n.data.prompt : n.data.input }}
          </div>
          <div class="port out" title="下一步" />
        </div>
      </div>
    </div>

    <aside class="inspector">
      <div class="t-faint" style="margin-bottom:10px">属性</div>
      <template v-if="selected">
        <el-form label-position="top" size="small">
          <el-form-item label="节点 ID">
            <el-input v-model="selected.id" disabled />
          </el-form-item>
          <el-form-item label="类型">
            <el-input :model-value="selected.type" disabled />
          </el-form-item>
          <el-form-item v-if="selected.type === 'ECHO'" label="input">
            <el-input v-model="selected.data.input" @input="dirty = true" />
          </el-form-item>
          <el-form-item v-if="selected.type === 'HUMAN_GATE'" label="prompt">
            <el-input v-model="selected.data.prompt" type="textarea" :rows="3" @input="dirty = true" />
          </el-form-item>
          <template v-if="selected.type === 'AGENT'">
            <el-form-item label="agentId">
              <el-input v-model="selected.data.agentId" @input="dirty = true" />
            </el-form-item>
            <el-form-item label="input">
              <el-input v-model="selected.data.input" type="textarea" :rows="3" @input="dirty = true" />
            </el-form-item>
          </template>
          <el-form-item label="下一步节点">
            <el-select
              :model-value="selected.data.next || ''"
              clearable
              placeholder="无（结束）"
              style="width:100%"
              @change="(v) => setNext(selected, v || '')"
            >
              <el-option
                v-for="n in nodes.filter((x) => x.id !== selected.id)"
                :key="n.id"
                :label="`${n.id} · ${n.type}`"
                :value="n.id"
              />
            </el-select>
          </el-form-item>
        </el-form>
      </template>
      <el-empty v-else description="点击画布中的节点进行配置" :image-size="64" />
    </aside>
  </div>

  <el-drawer v-model="jsonOpen" title="JSON 定义" size="560px">
    <el-input v-model="jsonDraft" type="textarea" :rows="22" class="mono" />
    <div style="margin-top:12px;display:flex;gap:8px">
      <el-button @click="jsonDraft = definitionJson">同步画布</el-button>
      <el-button type="primary" @click="loadFromJson">应用到画布</el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.editor-shell {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 280px;
  min-height: 560px;
  overflow: hidden;
}
.palette,
.inspector {
  padding: 14px;
  background: var(--bg-surface);
}
.palette {
  border-right: 1px solid var(--line);
}
.inspector {
  border-left: 1px solid var(--line);
}
.palette-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  width: 100%;
  text-align: left;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 8px;
  cursor: pointer;
}
.palette-item:hover {
  border-color: var(--brand);
  background: var(--brand-softer);
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
}
.palette-divider {
  height: 1px;
  background: var(--line);
  margin: 14px 0;
}
.zoom-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.canvas-wrap {
  position: relative;
  overflow: auto;
  background:
    radial-gradient(circle, var(--line) 1px, transparent 1px) 0 0 / 18px 18px,
    var(--bg-app);
  min-height: 560px;
}
.canvas {
  position: relative;
  width: 1100px;
  height: 640px;
  transform-origin: 0 0;
}
.edges {
  position: absolute;
  inset: 0;
  overflow: visible;
  pointer-events: none;
}
.node {
  position: absolute;
  width: 180px;
  height: 72px;
  border-radius: 12px;
  border: 1.5px solid var(--line);
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
  padding: 10px 12px;
  cursor: grab;
  user-select: none;
}
.node.active {
  box-shadow: var(--shadow-focus), var(--shadow-md);
  border-color: var(--brand);
}
.node-type {
  font-size: 11px;
  font-weight: 700;
}
.node-id {
  font-size: 12px;
  margin-top: 2px;
}
.node-preview {
  font-size: 11px;
  margin-top: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.port.out {
  position: absolute;
  right: -7px;
  top: 50%;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #94a3b8;
  transform: translateY(-50%);
  border: 2px solid var(--bg-surface);
}

@media (max-width: 1100px) {
  .editor-shell {
    grid-template-columns: 1fr;
  }
  .palette,
  .inspector {
    border: 0;
    border-top: 1px solid var(--line);
  }
}
</style>
