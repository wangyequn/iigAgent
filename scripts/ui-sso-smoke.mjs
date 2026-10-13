import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, '..', 'platform-console-ui', 'package.json'))
const { chromium } = uiRequire('playwright')

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
const log = (...a) => console.log(...a)

// 1) no token -> should hit platform login
await page.goto('http://localhost:5173/workbench/chat', { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(1500)
log('1 unauth url', page.url())
log('1 is platform login', page.url().includes('3200') || page.url().includes('/user/login'))

// 2) simulate OAuth2 callback with token
await page.goto('http://localhost:5173/oauth2/callback?token=dummy-sso-token-abc', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
log('2 after callback url', page.url())
log('2 token', await page.evaluate(() => localStorage.getItem('amp.console.jeegToken')))
log('2 not login', !page.url().includes('login'))

// 3) API without token after logout
await page.evaluate(() => {
  localStorage.removeItem('amp.console.jeegToken')
  localStorage.removeItem('Platform_Access-Token')
  localStorage.removeItem('ACCESS_TOKEN')
})
const apiNoToken = await page.evaluate(async () => {
  const res = await fetch('/api/v1/agents')
  return res.status
})
log('3 api after clear token (expect 401)', apiNoToken)

// 4) open app without token should not stay on chat
await page.goto('http://localhost:5173/workbench/chat', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1200)
log('4 re-enter without token url', page.url())
log('4 forced auth', page.url().includes('3200') || page.url().includes('login'))

await browser.close()
