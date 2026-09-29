<script setup>
import { nextTick, ref, watch } from 'vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import ToolCallCard from './ToolCallCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  streaming: { type: Boolean, default: false },
  streamText: { type: String, default: '' },
  pendingCalls: { type: Array, default: () => [] },
  suggestions: { type: Array, default: () => [] },
})
const emit = defineEmits(['approve', 'reject', 'allow-always', 'pick-suggestion'])

const scroller = ref(null)

watch(
  () => [props.messages.length, props.streamText, props.pendingCalls.length],
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
  { flush: 'post' },
)
</script>

<template>
  <div ref="scroller" class="chat-stream">
    <EmptyState
      v-if="!messages.length && !streaming"
      title="开始一段智能对话"
      description="输入消息，或点选下方示例。触发工具时会在流内出现审批卡片。"
      icon="ChatDotRound"
    >
      <div class="suggestions">
        <button
          v-for="s in suggestions"
          :key="s"
          type="button"
          class="chip"
          @click="emit('pick-suggestion', s)"
        >
          {{ s }}
        </button>
      </div>
    </EmptyState>

    <template v-for="(m, i) in messages" :key="i">
      <!-- thinking -->
      <div v-if="m.role === 'thinking'" class="thinking-row">
        <el-icon><Sunny /></el-icon>
        <div class="thinking-body">
          <div class="t-faint">思考过程</div>
          <div class="thinking-text">{{ m.text }}</div>
        </div>
      </div>

      <!-- tool calls as system cards -->
      <div v-else-if="m.role === 'tool'" class="row left">
        <ToolCallCard
          :call="m.call || m"
          @approve="emit('approve', m.call || m)"
          @reject="emit('reject', m.call || m)"
          @allow-always="emit('allow-always', m.call || m)"
        />
      </div>

      <!-- user / assistant -->
      <div v-else class="row" :class="m.role">
        <div class="bubble" :class="m.role">
          <div class="meta">
            <span>{{ m.role === 'user' ? '你' : '助手' }}</span>
            <span v-if="m.ts" class="t-faint">{{ new Date(m.ts).toLocaleTimeString() }}</span>
          </div>
          <MarkdownView v-if="m.role === 'assistant'" :text="m.text || ''" />
          <div v-else class="plain">{{ m.text }}</div>

          <div v-if="m.citations?.length" class="citations">
            <div class="t-faint">依据</div>
            <el-tag v-for="c in m.citations" :key="c" size="small" effect="plain" class="mono">{{ c }}</el-tag>
          </div>
        </div>
      </div>
    </template>

    <!-- live pending tools -->
    <div v-for="c in pendingCalls" :key="c.toolCallId || c.id" class="row left">
      <ToolCallCard
        :call="{ ...c, pending: true }"
        @approve="emit('approve', c)"
        @reject="emit('reject', c)"
        @allow-always="emit('allow-always', c)"
      />
    </div>

    <!-- streaming -->
    <div v-if="streaming || streamText" class="row assistant">
      <div class="bubble assistant live">
        <div class="meta"><span>助手</span><span class="t-faint">生成中…</span></div>
        <MarkdownView :text="streamText || '…'" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-stream {
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: auto;
  padding: 4px 2px 12px;
  min-height: 0;
}
.row {
  display: flex;
  width: 100%;
}
.row.user { justify-content: flex-end; }
.row.assistant, .row.left { justify-content: flex-start; }

.bubble {
  max-width: min(760px, 88%);
  border-radius: 16px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
}
.bubble.user {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
  border-bottom-right-radius: 6px;
}
.bubble.assistant {
  border-bottom-left-radius: 6px;
}
.bubble.live {
  border-color: color-mix(in srgb, var(--brand) 30%, var(--line));
}
.meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 11px;
  opacity: 0.75;
  margin-bottom: 6px;
}
.plain {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.6;
}
.citations {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}

.thinking-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--bg-subtle);
  border: 1px dashed var(--line);
  color: var(--ink-2);
  max-width: 88%;
}
.thinking-text {
  font-size: 12px;
  line-height: 1.55;
  margin-top: 2px;
  white-space: pre-wrap;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}
.chip {
  border: 1px solid var(--line);
  background: var(--bg-surface);
  color: var(--ink-2);
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  cursor: pointer;
}
.chip:hover {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-softer);
}
</style>
