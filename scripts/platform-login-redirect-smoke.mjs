import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, '..', 'platform-console-ui', 'package.json'))
const { chromium } = uiRequire('playwright')

function redis(cmd) {
  return new Promise((resolve, reject) => {
    const s = net.connect(6379, '127.0.0.1')
    let buf = ''
    s.on('data', (d) => {
      buf += d.toString('utf8')
    })
    s.on('error', reject)
    s.on('close', () => resolve(buf))
    s.write(cmd + '\r\n')
    setTimeout(() => s.end(), 200)
  })
}

async function getCaptcha() {
  const before = (await redis('KEYS *')).match(/[0-9a-f]{32}/g) || []
  const checkKey = 'ck' + Date.now()
  const res = await fetch(`http://127.0.0.1:9999/sys/randomImage/${checkKey}`)
  await res.json()
  await new Promise((r) => setTimeout(r, 250))
  const after = (await redis('KEYS *')).match(/[0-9a-f]{32}/g) || []
  const neu = after.find((k) => !before.includes(k))
  const raw = await redis('GET ' + neu)
  const m = raw.match(/\$[0-9]+\r\n"?([a-zA-Z0-9]{4})"?/)
  return { checkKey, code: m && m[1] }
}

const { checkKey, code } = await getCaptcha()
console.log('captcha', checkKey, code)

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
page.on('framenavigated', (f) => {
  if (f === page.mainFrame()) console.log('nav', f.url())
})

const redirect = 'http://localhost:5173/oauth2/callback'
const loginUrl = `http://127.0.0.1:3200/user/login?redirect=${encodeURIComponent(redirect)}`
console.log('open', loginUrl)
await page.goto(loginUrl, { waitUntil: 'networkidle', timeout: 60000 })
console.log('landed', page.url())

// fill form - find username/password/captcha inputs
await page.waitForTimeout(1000)
const inputs = page.locator('input')
const n = await inputs.count()
console.log('inputs', n)
for (let i = 0; i < n; i++) {
  const t = await inputs.nth(i).getAttribute('placeholder')
  console.log(i, t)
}

// typical jeecg login: username, password, captcha
try {
  await page.locator('input').nth(0).fill('stc_sinosoft')
  await page.locator('input').nth(1).fill('Sinosoft4rfv%TGB')
  if (n > 2) await page.locator('input').nth(2).fill(code || '')
} catch (e) {
  console.log('fill err', e.message)
}

// click login button
const btn = page.locator('button:has-text("登 录"), button:has-text("登录")').first()
console.log('login btn', await btn.count())
await btn.click()
await page.waitForTimeout(4000)
console.log('after login url', page.url())
console.log('has token param', page.url().includes('token='))
console.log('is callback', page.url().includes('oauth2/callback'))
await browser.close()
