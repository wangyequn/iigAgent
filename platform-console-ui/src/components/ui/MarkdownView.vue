<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

const props = defineProps({
  text: { type: String, default: '' },
})

marked.setOptions({
  gfm: true,
  breaks: true,
  highlight(code, lang) {
    try {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang }).value
      }
      return hljs.highlightAuto(code).value
    } catch {
      return code
    }
  },
})

const html = computed(() => {
  try {
    return marked.parse(props.text || '')
  } catch {
    return props.text
  }
})
</script>

<template>
  <div class="md-body" v-html="html" />
</template>
