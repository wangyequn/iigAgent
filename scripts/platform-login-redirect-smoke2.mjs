import { createRequire } from "node:module"
import path from "node:path"
import { fileURLToPath } from "node:url"
import net from "node:net"
import fs from "node:fs"

const root = path.dirname(fileURLToPath(import.meta.url))
const uiRequire = createRequire(path.join(root, "..", "platform-console-ui", "package.json"))
const { chromium } = uiRequire("playwright")

function redis(cmd) {
  return new Promise((resolve, reject) => {
    const s = net.connect(6379, "127.0.0.1")
    let buf = ""
    s.on("data", (d) => { buf += d.toString("utf8") })
    s.on("error", reject)
    s.on("close", () => resolve(buf))
    s.write(cmd + "\r\n")
    setTimeout(() => s.end(), 250)
  })
}

const before = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const checkKey = "ck" + Date.now()
const r1 = await fetch(`http://127.0.0.1:9999/sys/randomImage/${checkKey}`)
await r1.json()
await new Promise((r) => setTimeout(r, 300))
const after = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const neu = after.find((k) => !before.includes(k))
const raw = await redis("GET " + neu)
const code = (raw.match(/\$[0-9]+\r\n"?([a-zA-Z0-9]{4})"?/) || [])[1]
console.log("code", code, "key", checkKey)

// direct API login
const loginRes = await fetch("http://127.0.0.1:9999/sys/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    username: "stc_sinosoft",
    password: "Sinosoft4rfv%TGB",
    captcha: code,
    checkKey,
    clientId: "216f0175b84541759b37cb1fca6ac02c",
  }),
})
const loginJson = await loginRes.json()
console.log("api login success", loginJson.success, loginJson.message, "token", !!loginJson.result?.token)

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
page.on("response", async (res) => {
  if (res.url().includes("/sys/login")) {
    try {
      console.log("login resp", res.status(), (await res.text()).slice(0, 300))
    } catch {}
  }
})
await page.goto(`http://127.0.0.1:3200/user/login?redirect=${encodeURIComponent("http://localhost:5173/oauth2/callback")}`, { waitUntil: "networkidle" })
// new captcha for UI - the previous was consumed. Use API token path instead:
// inject platform token then click? For UI test get new captcha
const before2 = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const checkKey2 = "ckui" + Date.now()
await (await fetch(`http://127.0.0.1:9999/sys/randomImage/${checkKey2}`)).json()
await new Promise((r) => setTimeout(r, 300))
const after2 = (await redis("KEYS *")).match(/[0-9a-f]{32}/g) || []
const neu2 = after2.find((k) => !before2.includes(k))
const raw2 = await redis("GET " + neu2)
const code2 = (raw2.match(/\$[0-9]+\r\n"?([a-zA-Z0-9]{4})"?/) || [])[1]
console.log("ui code", code2)

await page.locator("input").nth(0).fill("stc_sinosoft")
await page.locator("input").nth(1).fill("Sinosoft4rfv%TGB")
await page.locator("input").nth(2).fill(code2)
await page.screenshot({ path: "output/playwright/login-form.png", fullPage: true })
await page.locator("button:has-text(\"登录\")").first().click()
await page.waitForTimeout(5000)
console.log("final url", page.url())
await page.screenshot({ path: "output/playwright/login-after.png", fullPage: true })
await browser.close()
