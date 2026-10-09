<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { completeLogin } from '@/auth/oidc'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const store = useAppStore()
const error = ref('')

onMounted(async () => {
  try {
    const returnTo = await completeLogin()
    await store.refreshOidc()
    store.notify({ type: 'success', title: 'OIDC 登录成功', message: '已切换到 JWT 人轨身份' })
    router.replace(returnTo || '/workbench/chat')
  } catch (e) {
    error.value = e.message || String(e)
  }
})
</script>

<template>
  <div class="auth-callback">
    <div v-if="!error" class="card pad">
      <div class="title">正在完成登录…</div>
      <div class="t-faint">校验授权码并获取 token</div>
    </div>
    <div v-else class="card pad">
      <div class="title">登录失败</div>
      <div class="t-body" style="margin:8px 0">{{ error }}</div>
      <el-button type="primary" @click="router.replace('/workbench/chat')">返回控制台</el-button>
    </div>
  </div>
</template>

<style scoped>
.auth-callback {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--bg, #f5f6f8);
}
.pad {
  padding: 28px 32px;
  min-width: 320px;
}
.title {
  font-weight: 600;
  font-size: 16px;
}
</style>
