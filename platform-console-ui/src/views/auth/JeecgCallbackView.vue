<script setup>
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { absorbReturnParams, exchangeCode, getJeecgToken, redirectIfUnauthenticated } from '@/auth/platformLogin'

const router = useRouter()
const route = useRoute()
const error = ref('')
const status = ref('正在处理平台登录…')

onMounted(async () => {
  const { token, code } = absorbReturnParams()
  if (token) {
    status.value = '登录成功，正在进入…'
    router.replace(String(route.query.redirect || '/workbench/chat'))
    return
  }
  if (code) {
    try {
      status.value = '正在换取平台令牌…'
      await exchangeCode(code)
      router.replace(String(route.query.redirect || '/workbench/chat'))
    } catch (e) {
      error.value = e.message || String(e)
    }
    return
  }
  if (getJeecgToken()) {
    router.replace(String(route.query.redirect || '/workbench/chat'))
    return
  }
  // 仍无票 → 再跳平台登录
  redirectIfUnauthenticated()
})
</script>

<template>
  <div class="wrap">
    <div v-if="!error" class="card">
      <div class="title">{{ status }}</div>
      <div class="t-faint">身份由 pc_platform 统一认证</div>
    </div>
    <div v-else class="card">
      <div class="title">登录失败</div>
      <div class="msg">{{ error }}</div>
      <el-button type="primary" @click="redirectIfUnauthenticated()">重新登录</el-button>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--bg, #f5f6f8);
}
.card {
  background: #fff;
  padding: 28px 32px;
  border-radius: 12px;
  min-width: 320px;
  border: 1px solid var(--line, #e2e8f0);
}
.title {
  font-weight: 600;
  margin-bottom: 8px;
}
.msg {
  margin: 8px 0 12px;
  color: #b91c1c;
}
.t-faint {
  color: var(--muted, #64748b);
  font-size: 13px;
}
</style>
