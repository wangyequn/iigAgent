import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, '..', 'platform-console-ui', 'package.json'))
const { chromium } = uiRequire('playwright')

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
page.on('pageerror', (e) => console.log('[err]', e.message))
await page.goto('http://localhost:5173/workbench/chat', { waitUntil: 'networkidle', timeout: 30000 })
await page.evaluate(() => {
  localStorage.setItem('amp.console.jeegToken', 'dummy-token-for-logout-test')
  localStorage.setItem('Platform_Access-Token', 'dummy-token-for-logout-test')
})
await page.reload({ waitUntil: 'networkidle' })
await page.click('button[title="身份与密钥"]')
await page.waitForTimeout(500)
const btn = page.locator('button:has-text("退出登录")')
console.log('btn count', await btn.count())
await btn.click()
await page.waitForTimeout(800)
const after = await page.evaluate(() => ({
  jeecg: localStorage.getItem('amp.console.jeegToken'),
  plat: localStorage.getItem('Platform_Access-Token'),
}))
console.log('after logout', JSON.stringify(after))
const toast = await page.locator('.el-message').count()
console.log('toast count', toast)
const body = await page.locator('body').innerText()
console.log('has 已退出登录', body.includes('已退出登录'))
await browser.close()
