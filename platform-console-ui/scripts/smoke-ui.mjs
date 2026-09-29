import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

const base = 'http://localhost:5173'
const outDir = 'output/playwright'
await mkdir(outDir, { recursive: true })

const pages = [
  ['/workbench/chat', 'chat'],
  ['/workbench/approvals', 'approvals'],
  ['/agents', 'agents'],
  ['/workflows/wf-demo', 'workflow-editor'],
  ['/ops/overview', 'ops-overview'],
  ['/governance/permissions', 'permissions'],
]

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []

page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(msg.text())
})

for (const [path, name] of pages) {
  await page.goto(base + path, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${outDir}/${name}.png`, fullPage: false })
  console.log('shot', name)
}

// basic interaction: type in chat
await page.goto(base + '/workbench/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const composer = page.locator('textarea')
if (await composer.count()) {
  await composer.first().fill('你好')
  console.log('chat composer ok')
}

// command palette
await page.keyboard.press('Control+k')
await page.waitForTimeout(400)
const cmd = page.locator('.command-input')
console.log('command palette visible', await cmd.count())

await browser.close()
console.log('ERRORS', errors.slice(0, 10))
