import { createRequire } from 'module';
import { mkdirSync } from 'fs';
import { join } from 'path';

const require = createRequire(join(process.cwd(), 'package.json'));
const { chromium } = require('playwright');

const BASE = process.env.BASE_URL || 'http://localhost:5175';
const OUT = join(process.cwd(), '..', 'output', 'playwright');
mkdirSync(OUT, { recursive: true });

const results = [];
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

try {
  await page.goto(BASE + '/workbench/chat', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: join(OUT, 'chat-before.png'), fullPage: true });

  // 找输入框并发送消息
  const inputs = page.locator('textarea, input[type="text"]');
  const n = await inputs.count();
  results.push({ step: 'find-inputs', count: n });

  let sent = false;
  for (let i = 0; i < n; i++) {
    const el = inputs.nth(i);
    if (await el.isVisible()) {
      try {
        await el.fill('你好，请用一句话介绍你自己');
        await el.press('Enter');
        sent = true;
        results.push({ step: 'send', ok: true, via: 'input#' + i });
        break;
      } catch (e) {
        results.push({ step: 'send-try', i, error: String(e).slice(0, 100) });
      }
    }
  }
  if (!sent) {
    // 尝试点击发送按钮前先找 placeholder
    const byPh = page.locator('[placeholder*="输入"], [placeholder*="消息"], [placeholder*="发送"]').first();
    if (await byPh.count()) {
      await byPh.fill('你好，请用一句话介绍你自己');
      await page.keyboard.press('Enter');
      sent = true;
      results.push({ step: 'send', ok: true, via: 'placeholder' });
    }
  }

  await page.waitForTimeout(8000);
  await page.screenshot({ path: join(OUT, 'chat-after-send.png'), fullPage: true });
  const body = await page.locator('body').innerText();
  results.push({
    step: 'after-send',
    textLen: body.length,
    hasReply: /你好|助手|介绍|demo|智能|我是/.test(body),
    snippet: body.replace(/\s+/g, ' ').slice(-300),
  });

  // 打开 Agent 列表并点第一行
  await page.goto(BASE + '/agents', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: join(OUT, 'agents-list.png'), fullPage: true });
  const rows = page.locator('table tr, .el-table__row');
  results.push({ step: 'agents-rows', count: await rows.count() });

  // 打开小助（若存在）
  try {
    const bot = page.getByText('问小助', { exact: false }).first();
    if (await bot.count()) {
      await bot.click({ timeout: 3000 });
      await page.waitForTimeout(1000);
      await page.screenshot({ path: join(OUT, 'bot-open.png'), fullPage: true });
      results.push({ step: 'bot', ok: true });
    } else {
      results.push({ step: 'bot', ok: false, error: '问小助 not found' });
    }
  } catch (e) {
    results.push({ step: 'bot', ok: false, error: String(e).slice(0, 120) });
  }
} finally {
  await browser.close();
}

console.log(JSON.stringify(results, null, 2));
