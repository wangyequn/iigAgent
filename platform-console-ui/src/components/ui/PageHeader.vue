<script setup>
defineProps({
  title: { type: String, required: true },
  desc: { type: String, default: '' },
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="t-page">{{ title }}</h1>
        <p v-if="desc" class="desc">{{ desc }}</p>
      </div>
      <div class="page-actions">
        <slot name="actions" />
      </div>
    </div>
    <slot />
  </div>
</template>
