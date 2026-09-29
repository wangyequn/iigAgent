<script setup>
defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  icon: { type: String, default: 'Box' },
})
defineEmits(['action'])
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon">
      <el-icon :size="28"><component :is="icon" /></el-icon>
    </div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc">{{ description }}</div>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 36px 20px;
  text-align: center;
}
.empty-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
}
.empty-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-1);
}
.empty-desc {
  font-size: 12px;
  color: var(--ink-2);
  max-width: 360px;
}
.empty-action {
  margin-top: 8px;
}
</style>
