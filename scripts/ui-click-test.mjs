import { createRequire } from 'module';
import { mkdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const require = createRequire(join(process.cwd(), 'package.json'));
const { chromium } = require('playwright');

const BASE = process.env.BASE_URL || 'http://localhost:5175';
const OUT = join(process.cwd(), '..', 'output', 'playwright');
mkdirSync(OUT, { recursive: true });

const pages = [
  { name: 'chat', path: '/workbench/chat' },
  { name: 'approvals', path: '/workbench/approvals' },
  { name: 'knowledge', path: '/workbench/knowledge' },
  { name: 'agents', path: '/agents' },
  { name: 'skills', path: '/skills' },
  { name: 'workflows', path: '/workflows' },
  { name: 'teams', path: '/teams' },
  { name: 'permissions', path: '/governance/permissions' },
  { name: 'tools', path: '/governance/tools' },
  { name: 'channels', path: '/governance/channels' },
  { name: 'iam', path: '/governance/iam' },
  { name: 'audit', path: '/ops/audit' },
  { name: 'billing', path: '/ops/billing' },
  { name: 'credentials', path: '/ops/credentials' },
  { name: 'ops', path: '/ops' },
  { name: 'health', path: '/ops/health' },
];

const results = [];
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => results.push({ page: 'pageerror', error: String(e).slice(0, 200) }));

try {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: join(OUT, 'home.png'), fullPage: true });
  results.push({ page: 'home', ok: true, url: page.url(), title: await page.title() });

  for (const p of pages) {
    try {
      await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded', timeout: 25000 });
      await page.waitForTimeout(1200);
      const body = await page.locator('body').innerText();
      await page.screenshot({ path: join(OUT, p.name + '.png'), fullPage: true });
      results.push({
        page: p.name,
        path: p.path,
        ok: body.length > 20,
        textLen: body.length,
        snippet: body.replace(/\s+/g, ' ').slice(0, 140),
      });
    } catch (e) {
      results.push({ page: p.name, path: p.path, ok: false, error: String(e).slice(0, 180) });
    }
  }

  // 侧边栏点击
  await page.goto(BASE + '/workbench/chat', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  for (const t of ['Agent 列表', '技能市场', '权限与模式', '审计', '知识库']) {
    try {
      const link = page.getByText(t, { exact: false }).first();
      if (await link.count()) {
        await link.click({ timeout: 4000 });
        await page.waitForTimeout(800);
        results.push({ nav: t, ok: true, url: page.url() });
      } else {
        results.push({ nav: t, ok: false, error: 'not found' });
      }
    } catch (e) {
      results.push({ nav: t, ok: false, error: String(e).slice(0, 120) });
    }
  }
} finally {
  await browser.close();
}

console.log(JSON.stringify(results, null, 2));
