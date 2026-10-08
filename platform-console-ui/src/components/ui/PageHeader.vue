<script setup>
import { useRouter, useRoute } from 'vue-router'
import { botAsk, botSetContext } from '@/stores/botBus'

const props = defineProps({
  title: { type: String, required: true },
  desc: { type: String, default: '' },
  /** 父级路径；传了则显示同行返回 */
  back: { type: String, default: '' },
  /** 返回文案（父级短名，如「Agent 列表」） */
  backLabel: { type: String, default: '' },
  /** 列表页紧凑标题 16px */
  compact: { type: Boolean, default: false },
  /** 注入给机器人的页面数据（可选） */
  botContext: { type: Object, default: null },
})
const router = useRouter()
const route = useRoute()

function goBack() {
  if (props.back) {
    router.push(props.back)
    return
  }
  if (window.history.length > 1) router.back()
  else router.push('/')
}

function askBot() {
  const context = {
    title: props.title,
    route: route.fullPath,
    data: props.botContext || undefined,
  }
  botSetContext(context)
  botAsk(`请帮我看看当前页面「${props.title}」：${props.desc || '解释一下这里能做什么，并给出下一步建议。'}`)
}
</script>

<template>
  <div class="page page-header-root">
    <div class="head-row" :class="{ compact }">
      <template v-if="back || backLabel">
        <button type="button" class="back-btn" @click="goBack">
          <el-icon :size="14"><ArrowLeft /></el-icon>
          <span>{{ backLabel || '返回' }}</span>
        </button>
        <span class="head-sep" aria-hidden="true" />
      </template>
      <h1 class="head-title" :class="{ compact }">{{ title }}</h1>
      <div class="head-actions">
        <button type="button" class="bot-ask-btn" title="问控制台机器人" @click="askBot">
          <el-icon :size="13"><MagicStick /></el-icon>
          <span>问小助</span>
        </button>
        <slot name="actions" />
      </div>
    </div>
    <p v-if="desc" class="head-desc">{{ desc }}</p>
    <slot />
  </div>
</template>

<style scoped>
.page-header-root {
  gap: 10px;
}
.head-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 40px;
  flex-wrap: wrap;
}
.head-title {
  margin: 0;
  font-size: var(--text-2xl);
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--ink-1);
  line-height: 1.25;
}
.head-title.compact {
  font-size: var(--text-lg);
  font-weight: 650;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px 0 4px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}
.back-btn:hover {
  color: var(--brand);
  background: var(--brand-softer);
}
.head-sep {
  width: 1px;
  height: 18px;
  background: var(--line);
  flex-shrink: 0;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-wrap: wrap;
}
.head-desc {
  margin: -2px 0 0;
  font-size: var(--text-sm);
  color: var(--ink-2);
  max-width: 720px;
  line-height: 1.5;
}
.bot-ask-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}
.bot-ask-btn:hover {
  color: var(--brand);
  border-color: var(--brand);
  background: var(--brand-softer);
}
</style>
