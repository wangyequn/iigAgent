/** Unified HTTP helpers with platform error codes. */
const STORAGE_KEY = 'amp.console.auth'

export function loadAuth() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
  } catch {
    return {}
  }
}

export function saveAuth(auth) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(auth || {}))
}

export function getOpenKey() {
  return loadAuth().openKey || 's0-demo-key'
}

export function getAdminKey() {
  return loadAuth().adminKey || 's0-admin-key'
}

export function headers(apiKey) {
  return {
    Authorization: `Bearer ${apiKey}`,
    'Content-Type': 'application/json',
  }
}

function buildError(res, text, path) {
  let code = ''
  try {
    const body = JSON.parse(text || '{}')
    code = body.code || body.error || ''
  } catch {
    /* keep raw */
  }
  const err = new Error(code ? `${code} · ${path}` : `${res.status} · ${path}`)
  err.status = res.status
  err.code = code
  err.body = text
  err.path = path
  return err
}

async function request(method, path, { apiKey, body, query, raw } = {}) {
  const qs = query
    ? `?${new URLSearchParams(
        Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== ''),
      ).toString()}`
    : ''
  const url = path + qs
  const res = await fetch(url, {
    method,
    headers: headers(apiKey),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    throw buildError(res, await res.text(), path)
  }
  if (raw) return res
  if (res.status === 204) return {}
  const text = await res.text()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return { raw: text }
  }
}

export const http = {
  get: (path, opts) => request('GET', path, opts),
  post: (path, body, opts) => request('POST', path, { ...opts, body }),
  put: (path, body, opts) => request('PUT', path, { ...opts, body }),
  patch: (path, body, opts) => request('PATCH', path, { ...opts, body }),
  del: (path, opts) => request('DELETE', path, opts),
}

/** OpenAI-compatible error shape for HITL etc. */
export function parseApiError(e) {
  return {
    status: e?.status,
    code: e?.code || '',
    message: e?.message || String(e),
  }
}
