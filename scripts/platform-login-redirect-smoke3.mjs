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
    setTimeout(() => s.end(), 250)
  })
}
const before = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const checkKey = "ck" + Date.now()
await (await fetch(`http://127.0.0.1:9999/sys/randomImage/${checkKey}`)).json()
await new Promise((r) => setTimeout(r, 300))
const after = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const neu = after.find((k) => !before.includes(k))
const raw = await redis("GET " + neu)
const code = (raw.match(/\$[0-9]+\r\n"?([a-zA-Z0-9]{4})"?/) || [])[1]
console.log("code", code)

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
page.on("response", async (res) => {
  if (res.url().includes("sys/login") || res.url().includes("oauth2")) {
    console.log("resp", res.status(), res.url().slice(0, 80))
  }
})
await page.goto("http://127.0.0.1:3200/user/login?redirect=http%3A%2F%2Flocalhost%3A5173%2Foauth2%2Fcallback", { waitUntil: "networkidle" })
await page.locator("input").nth(0).fill("stc_sinosoft")
await page.locator("input").nth(1).fill("Sinosoft4rfv%TGB")
await page.locator("input").nth(2).fill(code)
await page.locator("button").filter({ hasText: "登" }).first().click()
await page.waitForTimeout(5000)
console.log("final url", page.url())
console.log("body snippet", (await page.locator("body").innerText()).slice(0, 200))
await browser.close()
