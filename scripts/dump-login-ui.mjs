import { createRequire } from "node:module"
import path from "node:path"
import { fileURLToPath } from "node:url"
const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, "..", "platform-console-ui", "package.json"))
const { chromium } = uiRequire("playwright")
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
await page.goto("http://127.0.0.1:3200/user/login?redirect=http%3A%2F%2Flocalhost%3A5173%2Foauth2%2Fcallback", { waitUntil: "networkidle" })
await page.waitForTimeout(1500)
const html = await page.content()
console.log(html.slice(0, 2500))
const buttons = await page.locator("button").allTextContents()
console.log("buttons", JSON.stringify(buttons))
const snap = await page.accessibility.snapshot()
console.log(JSON.stringify(snap).slice(0, 2000))
await browser.close()
