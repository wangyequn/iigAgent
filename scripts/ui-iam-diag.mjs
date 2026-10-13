import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, '..', 'platform-console-ui', 'package.json'))
const { chromium } = uiRequire('playwright')

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
const log = (...a) => console.log(...a)

await page.goto('http://localhost:5173/workbench/chat', { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(1500)

const storage = await page.evaluate(() => {
  const keys = ['amp.console.jeegToken', 'Platform_Access-Token', 'ACCESS_TOKEN', 'Access-Token', 'X-Access-Token', 'amp.console.oidc', 'amp.console.auth']
  const out = {}
  for (const k of keys) {
    const v = localStorage.getItem(k)
    out[k] = v ? (v.length > 80 ? v.slice(0, 40) + '...' + v.slice(-20) + ` len=${v.length}` : v) : null
  }
  return out
})
log('storage', JSON.stringify(storage, null, 2))

const result = await page.evaluate(async () => {
  const token = localStorage.getItem('amp.console.jeegToken') || localStorage.getItem('Platform_Access-Token')
  if (!token) return { error: 'no token in browser' }
  const headers = {
    Authorization: `Bearer ${token}`,
    'X-Access-Token': token,
    'Content-Type': 'application/json',
  }
  const me = await fetch('/api/v1/auth/jeecg/me', { headers: { 'X-Access-Token': token } })
  const meBody = await me.text()
  const iam = await fetch('/api/v1/admin/iam/users', { headers })
  const iamBody = await iam.text()
  return {
    tokenPrefix: token.slice(0, 20),
    tokenParts: token.split('.').length,
    isEyj: /^eyJ/.test(token),
    meStatus: me.status,
    me: (() => { try { const j = JSON.parse(meBody); return { success: j.success, username: j.username, roles: j.roles, permCount: (j.perms || []).length, hasSetRoles: (j.perms || []).includes('platform:user:setroles'), hasRoleAdd: (j.perms || []).includes('system:role:add') } } catch { return meBody.slice(0, 200) } })(),
    iamStatus: iam.status,
    iamBody: iamBody.slice(0, 300),
  }
})
log('result', JSON.stringify(result, null, 2))

// also try after injecting known-good token
const tokenFile = await import('node:fs').then(fs => fs.readFileSync(path.join(root, 'jeecg_token.txt'), 'utf8').replace(/^﻿/, '').trim())
const result2 = await page.evaluate(async (t) => {
  localStorage.setItem('amp.console.jeegToken', t)
  localStorage.setItem('Platform_Access-Token', t)
  const headers = { Authorization: `Bearer ${t}`, 'X-Access-Token': t, 'Content-Type': 'application/json' }
  const iam = await fetch('/api/v1/admin/iam/users', { headers })
  return { status: iam.status, body: (await iam.text()).slice(0, 200) }
}, tokenFile)
log('with known-good token', result2)

await browser.close()
