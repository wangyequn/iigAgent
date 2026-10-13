import { createRequire } from "node:module"
import path from "node:path"
import { fileURLToPath } from "node:url"
import net from "node:net"
const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, "..", "platform-console-ui", "package.json"))
const { chromium } = uiRequire("playwright")
function redis(cmd) {
  return new Promise((resolve) => {
    const s = net.connect(6379, "127.0.0.1")
    let buf = ""
    s.on("data", (d) => { buf += d.toString("utf8") })
    s.on("close", () => resolve(buf))
    s.write(cmd + "\r\n")
    setTimeout(() => s.end(), 300)
  })
}
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
page.on("framenavigated", (f) => {
  if (f === page.mainFrame()) console.log("NAV", f.url().slice(0, 120))
})
await page.goto("http://127.0.0.1:3200/user/login?redirect=http%3A%2F%2Flocalhost%3A5173%2Foauth2%2Fcallback", { waitUntil: "networkidle" })
await page.waitForTimeout(1200)
const keys = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
let code = null
for (const k of keys) {
  const raw = await redis("GET " + k)
  const m = raw.match(/\$\d+\r\n"?([a-zA-Z0-9]{4})"?/)
  if (m) { code = m[1]; break }
}
await page.locator("input").nth(0).fill("stc_sinosoft")
await page.locator("input").nth(1).fill("Sinosoft4rfv%TGB")
await page.locator("input").nth(2).fill(code)
await page.locator("button").filter({ hasText: "登" }).first().click()
await page.waitForTimeout(4000)
const token = await page.evaluate(() => ({
  j: localStorage.getItem("amp.console.jeegToken"),
  p: localStorage.getItem("Platform_Access-Token"),
}))
console.log("final", page.url())
console.log("localStorage", JSON.stringify(token).slice(0, 80))
// open business page again without new login
await page.goto("http://localhost:5173/workbench/chat", { waitUntil: "networkidle" })
await page.waitForTimeout(800)
console.log("reopen", page.url())
console.log("reopen has chat", (await page.locator("body").innerText()).includes("对话"))
await browser.close()
