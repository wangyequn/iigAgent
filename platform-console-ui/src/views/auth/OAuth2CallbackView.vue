<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  clearAccessToken,
  exchangeCodeForToken,
  getAccessToken,
  parseCallbackParams,
  setAccessToken,
  takeReturnTo,
  startAuthorize,
} from '@/auth/sso'

const router = useRouter()
const error = ref('')
const status = ref('正在完成单点登录…')

onMounted(async () => {
  const { token, code } = parseCallbackParams()
  try {
    // 平台 /user/authorize 回跳 redirect_uri?token=<code>（getCode 授权码）
    // 标准 OAuth2 也可能是 ?code=
    const pending = sessionStorage.getItem('amp.sso.pendingCode')
    const authCode = code || (token && token.split('.').length !== 3 ? token : null) || pending
    const isJwt = token && /^eyJ/.test(token) && token.split('.').length === 3
    if (authCode) {
      status.value = '正在用授权码换取访问令牌…'
      sessionStorage.removeItem('amp.sso.pendingCode')
      await exchangeCodeForToken(authCode)
    } else if (isJwt) {
      setAccessToken(token)
    }
    if (!getAccessToken() || !/^eyJ/.test(getAccessToken() || '')) {
      status.value = '未获取到有效访问令牌，重新发起授权…'
      clearAccessToken()
      startAuthorize(takeReturnTo())
      return
    }
    const to = takeReturnTo()
    router.replace(to)
  } catch (e) {
    error.value = e.message || String(e)
  }
})
</script>

<template>
  <div class="wrap">
    <div class="card">
      <template v-if="!error">
        <div class="title">{{ status }}</div>
        <div class="t-faint">OAuth2 单点登录</div>
      </template>
      <template v-else>
        <div class="title">登录失败</div>
        <div class="err">{{ error }}</div>
        <el-button type="primary" @click="startAuthorize('/workbench/chat')">重新登录</el-button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: #f5f6f8;
}
.card {
  background: #fff;
  padding: 28px 32px;
  border-radius: 12px;
  min-width: 320px;
  border: 1px solid #e2e8f0;
}
.title {
  font-weight: 600;
  margin-bottom: 8px;
}
.err {
  color: #b91c1c;
  margin: 8px 0 12px;
}
.t-faint {
  color: #64748b;
  font-size: 13px;
}
</style>
