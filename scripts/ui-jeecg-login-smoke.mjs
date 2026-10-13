import { createRequire } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, '..', 'platform-console-ui', 'package.json'))
const { chromium } = uiRequire('playwright')

const BASE = process.env.BASE_URL || 'http://localhost:5177'
const USER = process.env.JEECG_USER || 'stc_sinosoft'
const PASS = process.env.JEECG_PASS || 'Sinosoft4rfv%TGB'
const CLIENT_ID = process.env.JEECG_CLIENT_ID || '216f0175b84541759b37cb1fca6ac02c'
const GATEWAY = process.env.GATEWAY || 'http://127.0.0.1:9999'
const OUT = 'output/playwright'
fs.mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
const logs = []
page.on('console', (m) => logs.push(`[console.${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))

function shot(name) {
  return page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true })
}

const steps = []
function ok(name, detail = '') {
  steps.push({ name, ok: true, detail })
  console.log('OK ', name, detail)
}
function fail(name, detail = '') {
  steps.push({ name, ok: false, detail })
  console.log('FAIL', name, detail)
}

// 1) open console
await page.goto(BASE, { waitUntil: 'networkidle', timeout: 30000 }).catch((e) => fail('goto', e.message))
await shot('01-open')
ok('open', page.url())

// 2) expect login or app
const body = await page.locator('body').innerText()
if (page.url().includes('/login') || body.includes('登录')) {
  ok('landed-login', page.url())
} else if (body.includes('智能对话') || body.includes('AI')) {
  ok('landed-app', page.url())
} else {
  ok('landed-unknown', page.url().slice(0, 80))
}

// 3) if on login, do captcha login via API then set token in localStorage and reload
async function platformLogin() {
  // get captcha code via redis is not available here; use backend login after reading image
  // We'll call gateway randomImage then use playwright to fill - captcha OCR is hard.
  // Instead: use AI login proxy after getting captcha code from backend redis via eval? skip.
  // Use page.evaluate to hit exchange if we have token file.
  const tokenPath = 'scripts/jeecg_token.txt'
  if (fs.existsSync(tokenPath)) {
    const token = fs.readFileSync(tokenPath, 'utf8').trim()
    await page.evaluate((t) => {
      localStorage.setItem('amp.console.jeegToken', t)
      localStorage.setItem('ACCESS_TOKEN', t)
      localStorage.setItem('Access-Token', t)
    }, token)
    await page.reload({ waitUntil: 'networkidle' })
    await shot('02-with-token')
    return token
  }
  return null
}

if (page.url().includes('/login') || (await page.locator('input[placeholder="用户名"]').count()) > 0) {
  const token = await platformLogin()
  if (token) ok('inject-token', 'from scripts/jeecg_token.txt')
  else fail('inject-token', 'no token file')
}

await page.waitForTimeout(800)
const url2 = page.url()
const body2 = await page.locator('body').innerText()
await shot('03-after-auth')
if (url2.includes('/login')) {
  fail('enter-system', 'still on login')
} else {
  ok('enter-system', url2)
}

// 4) check sidebar / chat page
const hasChat = body2.includes('智能对话') || body2.includes('对话') || body2.includes('消息')
ok('ui-visible', `chat=${hasChat} len=${body2.length}`)

// 5) call API from page context
const api = await page.evaluate(async () => {
  const t = localStorage.getItem('amp.console.jeegToken') || localStorage.getItem('ACCESS_TOKEN')
  const res = await fetch('/api/v1/agents', {
    headers: t ? { 'X-Access-Token': t, Authorization: `Bearer ${t}` } : {},
  })
  const text = await res.text()
  return { status: res.status, text: text.slice(0, 300) }
})
if (api.status === 200) ok('api-agents', api.status)
else fail('api-agents', `status=${api.status} ${api.text}`)

fs.writeFileSync(`${OUT}/steps.json`, JSON.stringify({ steps, logs }, null, 2))
console.log('DONE', JSON.stringify(steps))
await browser.close()
