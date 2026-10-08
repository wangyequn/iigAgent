<script setup>
import { computed, onMounted, reactive, ref, watch, markRaw } from 'vue'
import { useRoute } from 'vue-router'
import { VueFlow, useVueFlow, Position, Handle } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

import PageHeader from '@/components/ui/PageHeader.vue'
import FlowNode from '@/components/workflow/FlowNode.vue'
import { upsertWorkflow, publishWorkflowRevision, listWorkflows } from '@/api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const workflowId = route.params.workflowId

const name = ref('演示流程')
const description = ref('')
const dirty = ref(false)
const saving = ref(false)
const jsonOpen = ref(false)
const jsonDraft = ref('')
const selectedId = ref(null)

const nodeTypes = markRaw({
  echo: FlowNode,
  agent: FlowNode,
  gate: FlowNode,
})

const typeMeta = {
  ECHO: { key: 'echo', color: '#64748b', label: 'ECHO', desc: '回显输入，调试用' },
  AGENT: { key: 'agent', color: '#4f46e5', label: 'AGENT', desc: '调用 Agent 对话' },
  HUMAN_GATE: { key: 'gate', color: '#d97706', label: 'HUMAN_GATE', desc: '人工门禁确认' },
}

const {
  nodes,
  edges,
  addNodes,
  addEdges,
  removeNodes,
  findNode,
  setNodes,
  setEdges,
  onConnect,
  onNodeClick,
  onPaneReady,
  fitView,
} = useVueFlow({
  nodes: [],
  edges: [],
  defaultEdgeOptions: {
    type: 'smoothstep',
    animated: false,
    style: { stroke: '#94a3b8', strokeWidth: 1.8 },
  },
})

const selected = computed(() => {
  if (!selectedId.value) return null
  return nodes.value.find((n) => n.id === selectedId.value) || findNode(selectedId.value)
})

const selectedData = computed(() => selected.value?.data || null)

function slugType(t) {
  return typeMeta[t]?.key || 'echo'
}

function stepToNode(step, index) {
  return {
    id: step.id,
    type: slugType(step.type),
    position: { x: 80 + (index % 3) * 260, y: 100 + Math.floor(index / 3) * 140 },
    data: {
      type: step.type || 'ECHO',
      input: step.input ?? '',
      prompt: step.prompt ?? '',
      agentId: step.agentId ?? '',
      next: step.next ?? '',
    },
  }
}

function stepsToGraph(steps) {
  const ns = (steps || []).map(stepToNode)
  const es = []
  for (const s of steps || []) {
    if (s.next && (steps || []).some((t) => t.id === s.next)) {
      es.push({
        id: `e-${s.id}-${s.next}`,
        source: s.id,
        target: s.next,
        type: 'smoothstep',
        style: { stroke: '#94a3b8', strokeWidth: 1.8 },
      })
    }
  }
  return { ns, es }
}

function graphToSteps() {
  const out = nodes.value.map((n) => {
    const d = n.data || {}
    const step = { id: n.id, type: d.type || 'ECHO' }
    if (step.type === 'AGENT') {
      step.agentId = d.agentId || 'demo'
      step.input = d.input || ''
    } else if (step.type === 'HUMAN_GATE') {
      step.prompt = d.prompt || '请确认'
    } else {
      step.input = d.input || ''
    }
    const outEdge = edges.value.find((e) => e.source === n.id)
    step.next = outEdge?.target || ''
    return step
  })
  return out
}

const definitionJson = computed(() =>
  JSON.stringify({ steps: graphToSteps() }, null, 2),
)

watch(
  definitionJson,
  (v) => {
    if (!jsonOpen.value) jsonDraft.value = v
  },
  { immediate: true },
)

function addNodeByType(typeName) {
  const id = `${typeName.toLowerCase()}_${Date.now().toString(36).slice(-4)}`
  const count = nodes.value.length
  const data = {
    type: typeName,
    input: typeName === 'ECHO' ? 'echo' : typeName === 'AGENT' ? '新任务' : '',
    prompt: typeName === 'HUMAN_GATE' ? '请确认' : '',
    agentId: typeName === 'AGENT' ? 'demo' : '',
    next: '',
  }
  addNodes([
    {
      id,
      type: slugType(typeName),
      position: { x: 120 + (count % 3) * 240, y: 80 + Math.floor(count / 3) * 140 },
      data,
    },
  ])
  selectedId.value = id
  dirty.value = true
}

onConnect((params) => {
  addEdges([
    {
      id: `e-${params.source}-${params.target}`,
      source: params.source,
      target: params.target,
      type: 'smoothstep',
      style: { stroke: '#94a3b8', strokeWidth: 1.8 },
    },
  ])
  // 单出口：source 已有边则替换
  const extra = edges.value.filter(
    (e) => e.source === params.source && e.target !== params.target,
  )
  if (extra.length) {
    // keep only latest edge from source
    for (const e of extra) {
      edges.value = edges.value.filter((x) => x.id !== e.id)
    }
  }
  dirty.value = true
})

onNodeClick(({ node }) => {
  selectedId.value = node.id
})

function removeSelected() {
  if (!selectedId.value) return
  removeNodes([selectedId.value])
  selectedId.value = null
  dirty.value = true
}

function onFieldChange() {
  dirty.value = true
}

function loadFromJson() {
  try {
    const obj = JSON.parse(jsonDraft.value)
    const { ns, es } = stepsToGraph(obj.steps || [])
    setNodes(ns)
    setEdges(es)
    jsonOpen.value = false
    dirty.value = true
    requestAnimationFrame(() => fitView?.())
    ElMessage.success('已从 JSON 同步画布')
  } catch {
    ElMessage.error('JSON 不合法')
  }
}

async function loadWorkflow() {
  try {
    const data = await listWorkflows()
    const list = data.workflows || data.items || (Array.isArray(data) ? data : [])
    const found = list.find((w) => (w.workflowId || w.id) === workflowId)
    if (found?.name) name.value = found.name
    description.value = found?.description || ''
    const def = found?.definition || found?.definitionJson
    if (def) {
      const obj = typeof def === 'string' ? JSON.parse(def) : def
      if (obj.steps?.length) {
        const { ns, es } = stepsToGraph(obj.steps)
        setNodes(ns)
        setEdges(es)
        requestAnimationFrame(() => fitView?.())
      }
    }
  } catch {
    /* default demo graph */
    const demo = {
      steps: [
        { id: 's1', type: 'ECHO', input: '准备排查', next: 'gate' },
        { id: 'gate', type: 'HUMAN_GATE', prompt: '是否继续调用 Agent？', next: 's2' },
        { id: 's2', type: 'AGENT', agentId: 'demo', input: '总结今日运行摘要', next: '' },
      ],
    }
    const { ns, es } = stepsToGraph(demo.steps)
    setNodes(ns)
    setEdges(es)
    requestAnimationFrame(() => fitView?.())
  }
}

async function save() {
  saving.value = true
  try {
    await upsertWorkflow(workflowId, {
      name: name.value,
      description: description.value,
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

onMounted(loadWorkflow)
</script>

<template>
  <PageHeader
    :title="`Workflow 编辑器 · ${workflowId}`"
    back="/workflows"
    back-label="Workflow 列表"
  >
    <template #actions>
      <el-tag v-if="dirty" type="warning" size="small" effect="light">未保存</el-tag>
      <el-button @click="jsonOpen = true">JSON</el-button>
      <el-button :loading="saving" @click="save">保存</el-button>
      <el-button type="primary" :loading="saving" @click="publish">保存并发布</el-button>
    </template>
  </PageHeader>

  <div class="editor-shell card">
    <aside class="palette">
      <div class="t-faint" style="margin-bottom:8px">节点库</div>
      <button
        v-for="(meta, typeName) in typeMeta"
        :key="typeName"
        type="button"
        class="palette-item"
        @click="addNodeByType(typeName)"
      >
        <span class="dot" :style="{ background: meta.color }" />
        <div>
          <div class="mono" style="font-weight:600">{{ meta.label }}</div>
          <div class="t-faint">{{ meta.desc }}</div>
        </div>
      </button>
      <div class="hint t-faint">
        点击添加节点；从右侧圆点拖到目标节点左侧圆点可连线。单节点仅保留一条出边。
      </div>
    </aside>

    <div class="canvas-wrap">
      <VueFlow
        :node-types="nodeTypes"
        :default-edge-options="{ type: 'smoothstep' }"
        fit-view-on-init
        :min-zoom="0.2"
        :max-zoom="2"
      >
        <Background pattern-color="#c7d2fe" :gap="18" />
        <Controls />
        <MiniMap pannable zoomable />
      </VueFlow>
    </div>

    <aside class="inspector">
      <div class="t-faint" style="margin-bottom:10px">属性</div>
      <template v-if="selected && selectedData">
        <el-form label-position="top" size="small">
          <el-form-item label="节点 ID">
            <el-input :model-value="selectedId" disabled />
          </el-form-item>
          <el-form-item label="类型">
            <el-input :model-value="selectedData.type" disabled />
          </el-form-item>
          <el-form-item v-if="selectedData.type === 'ECHO'" label="input">
            <el-input v-model="selectedData.input" @input="onFieldChange" />
          </el-form-item>
          <el-form-item v-if="selectedData.type === 'HUMAN_GATE'" label="prompt">
            <el-input v-model="selectedData.prompt" type="textarea" :rows="3" @input="onFieldChange" />
          </el-form-item>
          <template v-if="selectedData.type === 'AGENT'">
            <el-form-item label="agentId">
              <el-input v-model="selectedData.agentId" @input="onFieldChange" />
            </el-form-item>
            <el-form-item label="input">
              <el-input v-model="selectedData.input" type="textarea" :rows="3" @input="onFieldChange" />
            </el-form-item>
          </template>
          <el-button size="small" type="danger" plain style="width:100%" @click="removeSelected">
            删除节点
          </el-button>
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
.hint {
  margin-top: 12px;
  line-height: 1.5;
}
.canvas-wrap {
  position: relative;
  min-height: 560px;
  height: 560px;
  background: var(--bg-app);
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
