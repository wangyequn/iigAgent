<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { setJeecgToken, loadPlatformAuthCfg, getJeecgToken } from '@/auth/platformLogin'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captcha = ref('')
const captchaSrc = ref('')
const checkKey = ref('')
const clientId = ref('216f0175b84541759b37cb1fca6ac02c')
const loading = ref(false)
const cfg = loadPlatformAuthCfg()

// 与 pc_platform 网关同源/反代时用相对路径也可
const gateway = cfg.gatewayUrl || 'http://127.0.0.1:9999'

function refreshCaptcha() {
  checkKey.value = 'ck' + Date.now() + Math.floor(Math.random() * 1000)
  captchaSrc.value = `${gateway}/sys/randomImage/${checkKey.value}`
  captcha.value = ''
}

onMounted(() => {
  // 不强制登录：本页仅手动访问 /login 时显示
  if (getJeecgToken()) {
    router.replace(String(route.query.redirect || '/workbench/chat'))
    return
  }
  refreshCaptcha()
})

async function login() {
  if (!username.value || !password.value || !captcha.value) {
    ElMessage.warning('请填写账号、密码和验证码')
    return
  }
  loading.value = true
  try {
    const res = await fetch('/api/v1/auth/jeecg/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value,
        password: password.value,
        captcha: captcha.value,
        checkKey: checkKey.value,
        clientId: clientId.value,
      }),
    })
    const data = await res.json()
    if (data.success && data.result && data.result.token) {
      setJeecgToken(data.result.token)
      ElMessage.success('登录成功')
      const redirect = String(route.query.redirect || '/workbench/chat')
      router.replace(redirect)
    } else {
      ElMessage.error(data.message || '登录失败')
      refreshCaptcha()
    }
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

function goPlatform() {
  const back = location.href
  location.assign(`${cfg.loginUrl}?redirect=${encodeURIComponent(back)}`)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="mark">AI</div>
        <div>
          <h1>AI 中台登录</h1>
          <p>统一使用 pc_platform 账号</p>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="账号">
          <el-input v-model="username" placeholder="用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="login"
          />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="captcha-row">
            <el-input v-model="captcha" placeholder="验证码" maxlength="4" @keyup.enter="login" />
            <img
              class="captcha-img"
              :src="captchaSrc"
              alt="验证码"
              title="点击刷新"
              @click="refreshCaptcha"
            />
          </div>
        </el-form-item>
        <el-button type="primary" class="btn" :loading="loading" @click="login">
          登 录
        </el-button>
        <el-button text class="btn-link" @click="goPlatform">前往平台登录页</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: linear-gradient(160deg, #eef2ff 0%, #f8fafc 45%, #e0e7ff 100%);
  padding: 24px;
}
.login-card {
  width: min(400px, 100%);
  background: #fff;
  border-radius: 16px;
  padding: 32px 28px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.08);
}
.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 22px;
}
.mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2f54eb, #7c3aed);
  color: #fff;
  font-weight: 700;
  display: grid;
  place-items: center;
}
h1 {
  font-size: 18px;
  margin: 0;
}
.brand p {
  margin: 2px 0 0;
  color: #64748b;
  font-size: 12px;
}
.captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.captcha-img {
  width: 110px;
  height: 40px;
  cursor: pointer;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  object-fit: cover;
}
.btn {
  width: 100%;
  margin-top: 4px;
}
.btn-link {
  width: 100%;
  margin-top: 10px;
  color: #64748b;
}
</style>
