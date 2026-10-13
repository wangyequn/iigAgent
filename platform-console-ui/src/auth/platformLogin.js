/**
 * pc_platform 登录集成：未登录跳转平台 /user/login，回跳后收 token。
 * 同域时优先读平台已写的 ACCESS_TOKEN（vue-ls）。
 */
const TOKEN_KEYS = [
  'amp.console.jeegToken',
  'Platform_Access-Token',
  'ACCESS_TOKEN',
  'Access-Token',
  'X-Access-Token',
  'jeecg_token',
]
const CFG_KEY = 'amp.console.jeegLogin'

export function loadPlatformAuthCfg() {
  try {
    return {
      loginUrl: 'http://127.0.0.1:3200/user/login',
      enabled: true,
      ...JSON.parse(localStorage.getItem(CFG_KEY) || '{}'),
    }
  } catch {
    return { loginUrl: 'http://127.0.0.1:3200/user/login', enabled: true }
  }
}

export function savePlatformAuthCfg(patch) {
  const next = { ...loadPlatformAuthCfg(), ...(patch || {}) }
  localStorage.setItem(CFG_KEY, JSON.stringify(next))
  return next
}

export function getJeecgToken() {
  for (const k of TOKEN_KEYS) {
    try {
      const v = localStorage.getItem(k)
      if (v && v.trim()) return v.trim()
    } catch {
      /* ignore */
    }
  }
  return null
}

export function setJeecgToken(token) {
  if (!token) return
  localStorage.setItem('amp.console.jeegToken', token)
  // 与平台 vue-ls 共用键，同域时业务请求可直接认
  try {
    localStorage.setItem('ACCESS_TOKEN', token)
    localStorage.setItem('Access-Token', token)
  } catch {
    /* ignore */
  }
}

export function clearJeecgToken() {
  TOKEN_KEYS.forEach((k) => {
    try {
      localStorage.removeItem(k)
    } catch {
      /* ignore */
    }
  })
}

/** 退出：清本机登录态（Jeecg / OIDC），不强制跳登录页。 */
export function logoutAll() {
  clearJeecgToken()
  try {
    localStorage.removeItem('amp.console.oidc')
  } catch {
    /* ignore */
  }
}

/** 从 URL 回调收 token/code（平台登录跳回或 OAuth2）。 */
export function absorbReturnParams() {
  const q = new URLSearchParams(location.search)
  const token =
    q.get('token') || q.get('access_token') || q.get('X-Access-Token') || q.get('code')
  // code 不是 JWT，交由后端 exchange；此处只收真正的 token
  const raw = q.get('token') || q.get('access_token') || q.get('X-Access-Token')
  if (raw) {
    setJeecgToken(raw)
    q.delete('token')
    q.delete('access_token')
    q.delete('X-Access-Token')
    const search = q.toString()
    history.replaceState(null, '', location.pathname + (search ? `?${search}` : '') + location.hash)
    return { token: raw, code: null }
  }
  const code = q.get('code')
  if (code) {
    return { token: null, code }
  }
  return { token: getJeecgToken(), code: null }
}

/** 未登录则跳转平台登录页（带 redirect 回跳）。 */
export function redirectIfUnauthenticated() {
  const cfg = loadPlatformAuthCfg()
  if (!cfg.enabled) return false
  if (getJeecgToken()) return false
  const back = location.href
  const url = new URL(cfg.loginUrl, location.href)
  url.searchParams.set('redirect', back)
  location.assign(url.toString())
  return true
}

export async function exchangeCode(code) {
  const res = await fetch('/api/v1/auth/jeecg/exchange', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      code,
      clientId: localStorage.getItem('amp.console.jeegClientId') || '216f0175b84541759b37cb1fca6ac02c'
    }),
  })
  const data = await res.json().catch(() => ({}))
  if (data.success && data.token) {
    setJeecgToken(data.token)
    return data.token
  }
  throw new Error(data.message || 'exchange failed')
}
