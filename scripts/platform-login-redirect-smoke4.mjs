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
let captchaKey = null
page.on("request", (req) => {
  const u = req.url()
  if (u.includes("randomImage")) {
    captchaKey = u.split("randomImage/")[1]
    console.log("page captcha key", captchaKey)
  }
})
await page.goto("http://127.0.0.1:3200/user/login?redirect=http%3A%2F%2Flocalhost%3A5173%2Foauth2%2Fcallback", { waitUntil: "networkidle" })
await page.waitForTimeout(1500)
console.log("key after load", captchaKey)
const keys = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
// get all short values as captcha candidates
let code = null
for (const k of keys) {
  const raw = await redis("GET " + k)
  const m = raw.match(/\$4\r\n"?([a-zA-Z0-9]{4})"?/)
  if (m) { code = m[1]; console.log("found code", code, "for", k); break }
}
if (!code) {
  // try 5-char quoted
  for (const k of keys) {
    const raw = await redis("GET " + k)
    const m = raw.match(/\$\d+\r\n"?([a-zA-Z0-9]{4})"?\r\n/)
    if (m) { code = m[1]; console.log("found code2", code); break }
  }
}
await page.locator("input").nth(0).fill("stc_sinosoft")
await page.locator("input").nth(1).fill("Sinosoft4rfv%TGB")
await page.locator("input").nth(2).fill(code || "xxxx")
await page.locator("button").filter({ hasText: "登" }).first().click()
await page.waitForTimeout(5000)
console.log("final url", page.url())
console.log("callback?", page.url().includes("oauth2/callback"))
console.log("token param?", page.url().includes("token="))
await browser.close()
