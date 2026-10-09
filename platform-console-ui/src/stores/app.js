import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { getOpenKey, getAdminKey, saveAuth, loadAuth } from '@/api/http'
import { healthReady, iamInfo } from '@/api'
import {
  beginLogin,
  currentIdentity,
  loadOidcConfig,
  logout as oidcLogout,
  saveOidcConfig,
} from '@/auth/oidc'

export const useAppStore = defineStore('app', () => {
  const auth = ref({
    openKey: loadAuth().openKey || 's0-demo-key',
    adminKey: loadAuth().adminKey || 's0-admin-key',
  })
  const theme = ref(localStorage.getItem('amp.theme') || 'light')
  const agentId = ref(localStorage.getItem('amp.agentId') || 'demo')
  const userId = ref(localStorage.getItem('amp.userId') || 'u1')
  const sessionId = ref(localStorage.getItem('amp.sessionId') || 'agt_ui_demo1')
  const ready = ref(null)
  const notifications = ref([])
  const commandOpen = ref(false)
  const oidcCfg = ref(loadOidcConfig())
  const oidcUser = ref(currentIdentity())
  const iamMode = ref(null)

  watch(
    auth,
    (v) => saveAuth(v),
    { deep: true },
  )
  watch(theme, (v) => {
    localStorage.setItem('amp.theme', v)
    document.documentElement.classList.toggle('theme-dark', v === 'dark')
  }, { immediate: true })
  watch(agentId, (v) => localStorage.setItem('amp.agentId', v))
  watch(userId, (v) => localStorage.setItem('amp.userId', v))
  watch(sessionId, (v) => localStorage.setItem('amp.sessionId', v))

  function setAuth(patch) {
    auth.value = { ...auth.value, ...patch }
  }

  async function refreshOidc() {
    oidcUser.value = currentIdentity()
    try {
      iamMode.value = await iamInfo()
    } catch {
      iamMode.value = null
    }
  }

  async function loginOidc() {
    saveOidcConfig(oidcCfg.value)
    await beginLogin()
  }

  function logoutOidc() {
    oidcLogout()
    oidcUser.value = null
    notify({ type: 'info', title: '已退出 OIDC', message: '将回退到 API Key 机器轨' })
  }

  function persistOidcCfg() {
    saveOidcConfig(oidcCfg.value)
  }

  async function refreshHealth() {
    try {
      ready.value = await healthReady()
    } catch {
      ready.value = null
    }
  }

  function notify({ type = 'info', title, message, code }) {
    const id = Date.now() + Math.random()
    notifications.value.unshift({
      id,
      type,
      title,
      message,
      code,
      at: new Date().toISOString(),
      read: false,
    })
    if (notifications.value.length > 50) notifications.value.pop()
    return id
  }

  function markAllRead() {
    notifications.value = notifications.value.map((n) => ({ ...n, read: true }))
  }

  function clearNotifications() {
    notifications.value = []
  }

  const unreadCount = () => notifications.value.filter((n) => !n.read).length

  return {
    auth,
    theme,
    agentId,
    userId,
    sessionId,
    ready,
    notifications,
    commandOpen,
    oidcCfg,
    oidcUser,
    iamMode,
    setAuth,
    refreshOidc,
    loginOidc,
    logoutOidc,
    persistOidcCfg,
    refreshHealth,
    notify,
    markAllRead,
    clearNotifications,
    unreadCount,
    openKey: () => getOpenKey(),
    adminKey: () => getAdminKey(),
  }
})
