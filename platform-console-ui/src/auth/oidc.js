/**
 * OIDC Authorization Code + PKCE（人轨 JWT）。
 * 双轨：未登录时继续用 API Key；登录后 Authorization 优先 Bearer access_token。
 */
const STORAGE_KEY = 'amp.console.oidc'
const AUTH_CFG_KEY = 'amp.console.oidcCfg'

function loadJson(key, fallback = {}) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null') || fallback
  } catch {
    return fallback
  }
}

function saveJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value || {}))
}

export function loadOidcConfig() {
  return {
    issuer: '',
    clientId: 'amp-console',
    scopes: 'openid profile email',
    ...loadJson(AUTH_CFG_KEY),
  }
}

export function saveOidcConfig(patch) {
  const next = { ...loadOidcConfig(), ...(patch || {}) }
  saveJson(AUTH_CFG_KEY, next)
  return next
}

export function loadOidcSession() {
  return loadJson(STORAGE_KEY, null)
}

export function saveOidcSession(session) {
  if (!session) {
    localStorage.removeItem(STORAGE_KEY)
    return
  }
  saveJson(STORAGE_KEY, session)
}

export function clearOidcSession() {
  localStorage.removeItem(STORAGE_KEY)
}

export function getAccessToken() {
  const s = loadOidcSession()
  if (!s?.access_token) return null
  if (s.expires_at && Date.now() >= s.expires_at - 15_000) {
    return null
  }
  return s.access_token
}

/** 从 id_token / access_token 解析 claims（不校验签名，仅展示；网关侧校验）。 */
export function parseJwtPayload(token) {
  if (!token) return null
  try {
    const part = token.split('.')[1] || ''
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/')
    const pad = b64.length % 4 ? '='.repeat(4 - (b64.length % 4)) : ''
    return JSON.parse(atob(b64 + pad))
  } catch {
    return null
  }
}

export function currentIdentity() {
  const s = loadOidcSession()
  if (!s?.access_token && !s?.id_token) return null
  const claims = parseJwtPayload(s.id_token) || parseJwtPayload(s.access_token) || {}
  const roles = extractRoles(claims)
  return {
    userId: claims.sub || claims.preferred_username || '',
    tenantId: claims.tenant || claims.tenantId || claims.azp || '',
    name: claims.name || claims.preferred_username || claims.sub || '',
    roles,
    expiresAt: s.expires_at || null,
  }
}

function extractRoles(claims) {
  const out = new Set()
  const add = (v) => {
    if (typeof v === 'string') v.split(',').forEach((x) => x.trim() && out.add(x.trim().toLowerCase()))
    else if (Array.isArray(v)) v.forEach((x) => x && out.add(String(x).toLowerCase()))
  }
  add(claims.roles)
  if (claims.realm_access?.roles) add(claims.realm_access.roles)
  if (claims.resource_access && typeof claims.resource_access === 'object') {
    Object.values(claims.resource_access).forEach((c) => c?.roles && add(c.roles))
  }
  return [...out]
}

function base64Url(buf) {
  const bytes = buf instanceof ArrayBuffer ? new Uint8Array(buf) : buf
  let str = ''
  bytes.forEach((b) => {
    str += String.fromCharCode(b)
  })
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function sha256(plain) {
  const data = new TextEncoder().encode(plain)
  return crypto.subtle.digest('SHA-256', data)
}

function randomString(len = 64) {
  const arr = new Uint8Array(len)
  crypto.getRandomValues(arr)
  return base64Url(arr)
}

function redirectUri() {
  return `${location.origin}/auth/callback`
}

/** 构造 authorize 跳转（code_challenge）。 */
export async function beginLogin() {
  const cfg = loadOidcConfig()
  if (!cfg.issuer) {
    throw new Error('未配置 OIDC issuer')
  }
  const verifier = randomString(64)
  const challenge = base64Url(await sha256(verifier))
  const state = randomString(16)
  sessionStorage.setItem(
    'amp.oidc.pkce',
    JSON.stringify({ verifier, state, issuer: cfg.issuer, clientId: cfg.clientId, returnTo: location.pathname + location.search }),
  )
  const discovery = await fetchDiscovery(cfg.issuer)
  const authEndpoint = discovery.authorization_endpoint || `${trimSlash(cfg.issuer)}/protocol/openid-connect/auth`
  const url = new URL(authEndpoint)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', cfg.clientId)
  url.searchParams.set('redirect_uri', redirectUri())
  url.searchParams.set('scope', cfg.scopes || 'openid profile email')
  url.searchParams.set('state', state)
  url.searchParams.set('code_challenge', challenge)
  url.searchParams.set('code_challenge_method', 'S256')
  location.assign(url.toString())
}

async function fetchDiscovery(issuer) {
  const key = `amp.oidc.discovery:${issuer}`
  const cached = sessionStorage.getItem(key)
  if (cached) {
    try {
      return JSON.parse(cached)
    } catch {
      /* refetch */
    }
  }
  const res = await fetch(`${trimSlash(issuer)}/.well-known/openid-configuration`)
  if (!res.ok) throw new Error(`OIDC discovery 失败: ${res.status}`)
  const json = await res.json()
  sessionStorage.setItem(key, JSON.stringify(json))
  return json
}

function trimSlash(s) {
  return String(s || '').replace(/\/+$/, '')
}

/** 回调页调用：code → token。 */
export async function completeLogin() {
  const params = new URLSearchParams(location.search)
  const code = params.get('code')
  const state = params.get('state')
  const err = params.get('error')
  if (err) throw new Error(params.get('error_description') || err)
  if (!code) throw new Error('缺少 authorization code')
  const pkce = JSON.parse(sessionStorage.getItem('amp.oidc.pkce') || '{}')
  if (!pkce.verifier || (pkce.state && state && pkce.state !== state)) {
    throw new Error('PKCE state 不匹配，请重新登录')
  }
  const cfg = loadOidcConfig()
  const issuer = pkce.issuer || cfg.issuer
  const clientId = pkce.clientId || cfg.clientId
  const discovery = await fetchDiscovery(issuer)
  const tokenEndpoint = discovery.token_endpoint || `${trimSlash(issuer)}/protocol/openid-connect/token`
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri(),
    client_id: clientId,
    code_verifier: pkce.verifier,
  })
  const res = await fetch(tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    throw new Error(`token 端点返回异常: ${res.status}`)
  }
  if (!res.ok) throw new Error(json.error_description || json.error || `token 失败 ${res.status}`)
  saveOidcSession({
    access_token: json.access_token,
    refresh_token: json.refresh_token || null,
    id_token: json.id_token || null,
    expires_at: Date.now() + (Number(json.expires_in || 300) * 1000),
    obtained_at: Date.now(),
  })
  sessionStorage.removeItem('amp.oidc.pkce')
  return pkce.returnTo || '/'
}

/** 到期前用 refresh_token 续期；失败则清会话返回 false。 */
export async function ensureFreshToken() {
  const s = loadOidcSession()
  if (!s?.access_token) return false
  if (s.expires_at && Date.now() < s.expires_at - 30_000) return true
  if (!s.refresh_token) {
    clearOidcSession()
    return false
  }
  try {
    const cfg = loadOidcConfig()
    const issuer = cfg.issuer
    const discovery = await fetchDiscovery(issuer)
    const tokenEndpoint = discovery.token_endpoint || `${trimSlash(issuer)}/protocol/openid-connect/token`
    const res = await fetch(tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: s.refresh_token,
        client_id: cfg.clientId,
      }),
    })
    if (!res.ok) {
      clearOidcSession()
      return false
    }
    const json = await res.json()
    saveOidcSession({
      access_token: json.access_token,
      refresh_token: json.refresh_token || s.refresh_token,
      id_token: json.id_token || s.id_token,
      expires_at: Date.now() + (Number(json.expires_in || 300) * 1000),
      obtained_at: Date.now(),
    })
    return true
  } catch {
    return false
  }
}

export function logout() {
  clearOidcSession()
}

/** 统一 Bearer：JWT 优先，回退 API Key。 */
export function resolveBearer(preferApiKey) {
  const jwt = getAccessToken()
  if (jwt && !preferApiKey) return jwt
  return null
}
