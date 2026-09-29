<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  hint: { type: String, default: '' },
  tone: { type: String, default: 'default' }, // default | ok | warn | danger | brand
  icon: { type: String, default: '' },
})
</script>

<template>
  <div class="stat-card card card-pad" :class="`tone-${tone}`">
    <div class="stat-top">
      <span class="stat-label">{{ label }}</span>
      <el-icon v-if="icon" class="stat-icon"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-value">{{ value }}</div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
    <div v-if="$slots.default" class="stat-extra"><slot /></div>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 110px;
}
.stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.stat-label {
  font-size: 12px;
  color: var(--ink-2);
  font-weight: 500;
}
.stat-icon {
  color: var(--ink-3);
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--ink-1);
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}
.stat-hint {
  font-size: 12px;
  color: var(--ink-3);
}
.tone-brand .stat-value { color: var(--brand); }
.tone-ok .stat-value { color: var(--ok); }
.tone-warn .stat-value { color: var(--warn); }
.tone-danger .stat-value { color: var(--danger); }
</style>
