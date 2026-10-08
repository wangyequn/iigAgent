<script setup>
import { computed, ref } from 'vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  rows: { type: Number, default: 10 },
  placeholder: { type: String, default: '支持 Markdown：# 标题、**加粗**、列表、代码块…' },
})
const emit = defineEmits(['update:modelValue'])

const mode = ref('write') // write | split | preview

const text = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

function wrap(before, after = before) {
  const el = document.getElementById('md-editor-area')
  if (!el) {
    text.value = text.value + before + after
    return
  }
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = text.value.slice(start, end) || '文本'
  const next =
    text.value.slice(0, start) + before + selected + after + text.value.slice(end)
  text.value = next
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + before.length, start + before.length + selected.length)
  })
}

function prefixLine(prefix) {
  text.value = (text.value ? text.value + '\n' : '') + prefix
}
</script>

<template>
  <div class="md-editor">
    <div class="md-bar">
      <div class="tools">
        <button type="button" title="标题" @click="prefixLine('## ')">H2</button>
        <button type="button" title="加粗" @click="wrap('**')">B</button>
        <button type="button" title="斜体" @click="wrap('*')">I</button>
        <button type="button" title="行内代码" @click="wrap('`')">
          <span class="mono">&lt;/&gt;</span>
        </button>
        <button type="button" title="代码块" @click="wrap('```\n', '\n```')">{ }</button>
        <button type="button" title="列表" @click="prefixLine('- ')">•</button>
        <button type="button" title="引用" @click="prefixLine('> ')">❯</button>
      </div>
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="write">编辑</el-radio-button>
        <el-radio-button value="split">分屏</el-radio-button>
        <el-radio-button value="preview">预览</el-radio-button>
      </el-radio-group>
    </div>
    <div class="md-body-wrap" :class="mode">
      <textarea
        v-if="mode !== 'preview'"
        id="md-editor-area"
        v-model="text"
        class="md-textarea"
        :rows="rows"
        :placeholder="placeholder"
      />
      <div v-if="mode !== 'write'" class="md-preview">
        <MarkdownView :text="text || '*（空）*'" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.md-editor {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-surface);
}
.md-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line);
}
.tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.tools button {
  min-width: 32px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.tools button:hover {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-softer);
}
.md-body-wrap {
  display: grid;
  grid-template-columns: 1fr;
}
.md-body-wrap.split {
  grid-template-columns: 1fr 1fr;
}
.md-textarea {
  width: 100%;
  border: 0;
  outline: none;
  resize: vertical;
  padding: 12px 14px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-1);
  background: var(--bg-surface);
  min-height: 180px;
}
.md-preview {
  padding: 12px 14px;
  border-left: 1px solid var(--line);
  background: var(--bg-app);
  min-height: 180px;
  max-height: 480px;
  overflow: auto;
}
.md-body-wrap:not(.split) .md-preview {
  border-left: 0;
}
</style>
