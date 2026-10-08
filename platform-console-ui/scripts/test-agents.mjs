import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'

const base = 'http://localhost:5173'
const out = 'output/playwright'
await mkdir(out, { recursive: true })

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
const logs = []
page.on('pageerror', (e) => errors.push('PAGE: ' + String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') logs.push('CON: ' + m.text())
  if (m.type() === 'warning') logs.push('WARN: ' + m.text().slice(0, 120))
})

async function shot(name) {
  await page.screenshot({ path: `${out}/agent-${name}.png`, fullPage: false })
  console.log('shot', name)
}

async function textAll(sel) {
  return page.locator(sel).allTextContents()
}

// 1. Agent list
await page.goto(base + '/agents', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await shot('list')
console.log('LIST_H1', await page.locator('h1').first().textContent().catch(() => ''))
console.log('LIST_EMPTY', await page.locator('.empty-state').count())
console.log('LIST_CARDS', await page.locator('.agent-card').count())
console.log('LIST_BTN', (await textAll('button')).map((t) => t.trim()).filter(Boolean).slice(0, 20))

// 2. Open create dialog
const createBtn = page.locator('button', { hasText: '新建 Agent' }).first()
if (await createBtn.count()) {
  await createBtn.click()
  await page.waitForTimeout(500)
  await shot('create-dialog')
  console.log('DIALOG', await page.locator('.el-dialog').count(), await page.locator('.el-dialog__title').allTextContents())
  const uid = 'ui-' + Date.now().toString(36)
  await page.locator('.el-dialog input').first().fill(uid)
  await page.locator('.el-dialog input').nth(1).fill('UI测试智能体')
  // markdown editor textarea
  await page.locator('.el-dialog textarea').first().fill('你是测试助手，回答简洁。')
  await shot('create-filled')
  const submit = page.locator('.el-dialog button', { hasText: '创建' }).last()
  await submit.click()
  await page.waitForTimeout(1800)
  await shot('create-after')
  console.log('CREATE_TOAST', await textAll('.el-message'))
  // force close dialog if still open
  const closeBtn = page.locator('.el-dialog button', { hasText: '取消' }).first()
  if (await closeBtn.count()) {
    try { await closeBtn.click({ timeout: 2000 }) } catch {}
    await page.waitForTimeout(400)
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

// 3. Click first agent detail if any
const detailBtn = page.locator('button', { hasText: '详情' }).first()
if (await detailBtn.count()) {
  await detailBtn.click()
  await page.waitForTimeout(1200)
  await shot('detail')
  console.log('DETAIL_URL', page.url())
  console.log('DETAIL_H1', await page.locator('h1').first().textContent().catch(() => ''))
  console.log('DETAIL_TABS', await textAll('.el-tabs__item'))
  // click tabs
  for (const tabName of ['版本', '权限', '原始']) {
    const tab = page.locator('.el-tabs__item', { hasText: tabName }).first()
    if (await tab.count()) {
      await tab.click()
      await page.waitForTimeout(400)
      await shot('detail-' + tabName)
    }
  }
}

// 4. Versions page
await page.goto(base + '/agents', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
const verBtn = page.locator('button', { hasText: '版本' }).first()
if (await verBtn.count()) {
  await verBtn.click()
  await page.waitForTimeout(1200)
  await shot('versions')
  console.log('VER_H1', await page.locator('h1').first().textContent().catch(() => ''))
  console.log('VER_EMPTY', await page.locator('.empty-state').count())
  console.log('VER_BTN', (await textAll('button')).map((t) => t.trim()).filter(Boolean).slice(0, 15))
  // open create version
  const newVer = page.locator('button', { hasText: '新建版本' }).first()
  if (await newVer.count()) {
    await newVer.click()
    await page.waitForTimeout(400)
    await shot('versions-create')
    console.log('VER_DIALOG_INPUTS', await page.locator('.el-dialog input, .el-dialog textarea').count())
    console.log('VER_DIALOG', await page.locator('.el-dialog__title').allTextContents())
  }
}

// 5. Direct URLs
for (const path of ['/agents/ui-test-agent', '/agents/ui-test-agent/versions', '/agents/demo', '/agents/demo/versions']) {
  await page.goto(base + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(700)
  const name = path.replaceAll('/', '_')
  await shot('route' + name)
  console.log('ROUTE', path, 'H1=', await page.locator('h1').first().textContent().catch(() => ''), 'ERR_TOAST=', await textAll('.el-message'))
}

console.log('ERRORS', errors)
console.log('LOGS', logs.slice(0, 15))
await browser.close()
