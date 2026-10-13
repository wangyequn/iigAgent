/**
 * 标准 OAuth2 单点登录（pc_platform 作 IdP）。
 *
 * 链路：
 * 1) 应用检测未登录 → 跳本系统后端 /api/v1/auth/jeecg/authorize
 * 2) 后端拼授权地址 302 → 平台前端 /user/authorize?redirect_uri&client_id
 * 3) 平台 getCode → 回跳 redirect_uri?token=code
 * 4) 本系统 /oauth2/callback 用 code 换票 → 进业务
 * 5) 退出：清会话 → 回授权入口
 */
const TOKEN_KEY = 'amp.console.jeegToken'
const CFG_KEY = 'amp.console.jeegLogin'

export function loadSsoCfg() {
  try {
    return {
      loginUrl: 'http://127.0.0.1:3200/user/login',
      logoutUrl: 'http://127.0.0.1:3200/user/login',
      clientId: '216f0175b84541759b37cb1fca6ac02c',
      enabled: true,
      ...JSON.parse(localStorage.getItem(CFG_KEY) || '{}'),
    }
  } catch {
    return {
      loginUrl: 'http://127.0.0.1:3200/user/login',
      logoutUrl: 'http://127.0.0.1:3200/user/login',
      clientId: '216f0175b84541759b37cb1fca6ac02c',
      enabled: true,
    }
  }
}

export function saveSsoCfg(patch) {
  const next = { ...loadSsoCfg(), ...(patch || {}) }
  localStorage.setItem(CFG_KEY, JSON.stringify(next))
  return next
}

export function getAccessToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || localStorage.getItem('Platform_Access-Token')
  } catch {
    return null
  }
}

export function setAccessToken(token) {
  if (!token) return
  localStorage.setItem(TOKEN_KEY, token)
  try {
    localStorage.setItem('Platform_Access-Token', token)
    localStorage.setItem('ACCESS_TOKEN', token)
    localStorage.setItem('Access-Token', token)
  } catch {
    /* ignore */
  }
}

export function clearAccessToken() {
  const keys = [
    TOKEN_KEY,
    'Platform_Access-Token',
    'ACCESS_TOKEN',
    'Access-Token',
    'X-Access-Token',
    'jeecg_token',
    'amp.console.oidc',
  ]
  keys.forEach((k) => {
    try {
      localStorage.removeItem(k)
    } catch {
      /* ignore */
    }
  })
}

/** 跳转平台登录（IdP），带回跳地址。 */
export function redirectToIntegratedIdP(returnTo) {
  const cfg = loadSsoCfg()
  const back = returnTo || location.href
  const url = new URL(cfg.loginUrl, location.href)
  url.searchParams.set('redirect', back)
  location.assign(url.toString())
}

/**
 * 标准 OAuth2 authorize：无会话 → 本系统后端 /authorize → 平台 /user/authorize。
 * @returns {boolean} true=已跳转
 */
export function startAuthorize(returnTo) {
  if (getAccessToken()) return false
  const callback = `${location.origin}/oauth2/callback`
  const target = returnTo || location.pathname + location.search
  sessionStorage.setItem('amp.sso.returnTo', target)
  // 第 1→2 步：后端拼接标准授权 URL 并 302
  const authorize = `/api/v1/auth/jeecg/authorize?redirect_uri=${encodeURIComponent(callback)}`
  location.assign(authorize)
  return true
}

/** 解析 callback 上的 token/code。 */
export function parseCallbackParams() {
  const q = new URLSearchParams(location.search)
  return {
    token: q.get('token') || q.get('access_token') || q.get('id_token'),
    code: q.get('code'),
    state: q.get('state'),
  }
}

/** 任意入口带 ?token= 时收票；仅接受 JWT（eyJ...三段），授权码交给 exchange。 */
export function absorbTokenFromUrl() {
  const q = new URLSearchParams(location.search)
  const raw = q.get('token') || q.get('access_token') || q.get('id_token')
  if (!raw) return null
  const isJwt = /^eyJ/.test(raw) && raw.split('.').length === 3
  q.delete('token')
  q.delete('access_token')
  q.delete('id_token')
  const search = q.toString()
  history.replaceState(null, '', location.pathname + (search ? `?${search}` : '') + location.hash)
  if (isJwt) {
    setAccessToken(raw)
    return raw
  }
  // 非 JWT：视为授权码，留给 /oauth2/callback 换票
  sessionStorage.setItem('amp.sso.pendingCode', raw)
  return null
}

/** code → access_token（模式 B）。 */
export async function exchangeCodeForToken(code) {
  const cfg = loadSsoCfg()
  const res = await fetch('/api/v1/auth/jeecg/exchange', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, clientId: cfg.clientId }),
  })
  const data = await res.json().catch(() => ({}))
  if (data.success && data.token) {
    setAccessToken(data.token)
    return data.token
  }
  throw new Error(data.message || 'OAuth2 token exchange failed')
}

/** 退出：清会话并回到登录（标准 SSO logout）。 */
export function logoutAndRedirect() {
  clearAccessToken()
  sessionStorage.removeItem('amp.sso.returnTo')
  const cfg = loadSsoCfg()
  // 跳平台登录页作为统一登出入口（可选可配 logoutUrl）
  const url = new URL(cfg.logoutUrl || cfg.loginUrl, location.href)
  url.searchParams.set('redirect', `${location.origin}/oauth2/callback`)
  location.assign(url.toString())
}

export function takeReturnTo() {
  const v = sessionStorage.getItem('amp.sso.returnTo')
  sessionStorage.removeItem('amp.sso.returnTo')
  return v && v.startsWith('/') ? v : '/workbench/chat'
}
