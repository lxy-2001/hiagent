import { test, expect } from '@playwright/test';
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';
import { cpus, platform, release } from 'node:os';
import { backend, login } from './helpers';
const evidence = resolve(import.meta.dirname, '../../../../.ua/009-workbench'); mkdirSync(evidence + '/screenshots', { recursive: true });
test('production asset budget and five cache-disabled cold navigations', async ({ browser, baseURL }) => {
  const assets = resolve(import.meta.dirname, '../../../target/workbench/assets');
  const files = readdirSync(assets).map(name => ({ name, gzip: gzipSync(readFileSync(resolve(assets, name))).length }));
  const js = files.filter(f => f.name.endsWith('.js')).reduce((n, f) => n + f.gzip, 0), css = files.filter(f => f.name.endsWith('.css')).reduce((n, f) => n + f.gzip, 0);
  expect(js).toBeLessThanOrEqual(250 * 1024); expect(css).toBeLessThanOrEqual(60 * 1024);
  const samples: { lcp: number; cls: number }[] = [];
  for (let i = 0; i < 5; i++) {
    const context = await browser.newContext(), page = await context.newPage(); const client = await context.newCDPSession(page); await client.send('Network.enable'); await client.send('Network.setCacheDisabled', { cacheDisabled: true });
    await page.addInitScript(() => {
      const metrics = { lcp: 0, cls: 0 }; Object.assign(window, { workbenchMetrics: metrics });
      new PerformanceObserver(list => { for (const entry of list.getEntries()) metrics.lcp = entry.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true });
      new PerformanceObserver(list => { for (const entry of list.getEntries()) { const shift = entry as PerformanceEntry & { hadRecentInput: boolean; value: number }; if (!shift.hadRecentInput) metrics.cls += shift.value; } }).observe({ type: 'layout-shift', buffered: true });
    });
    await page.goto(baseURL!); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible(); await page.waitForTimeout(300);
    samples.push(await page.evaluate(() => (window as unknown as { workbenchMetrics: { lcp: number; cls: number } }).workbenchMetrics)); await context.close();
  }
  const median = (key: 'lcp' | 'cls') => samples.map(s => s[key]).sort((a,b) => a-b)[2]!;
  const report = { samples, medianLcpMs: median('lcp'), medianCls: median('cls'), files, jsGzip: js, cssGzip: css, browser: browser.version(), machine: `${platform()} ${release()} ${cpus()[0]?.model}`, network: 'loopback, cache disabled, production assets' };
  writeFileSync(resolve(evidence, 'performance.json'), JSON.stringify(report, null, 2));
  expect(report.medianLcpMs).toBeGreaterThan(0); expect(report.medianLcpMs).toBeLessThanOrEqual(2500); expect(report.medianCls).toBeLessThanOrEqual(0.1);
});
test('100 actual history rows page from the newest20 without duplicates', async ({ page, request }) => {
  const api = await backend(request); const seed = await request.post('http://127.0.0.1:15180/api/demo/fixture-seed', { headers: api.headers }); expect(seed.ok()).toBe(true);
  const { sessionId } = await seed.json(); await login(page); await page.goto('/#/sessions/' + sessionId);
  await expect(page.locator('.turn')).toHaveCount(20);
  for (let i = 0; i < 4; i++) await page.getByRole('button', { name: '加载更早的记录' }).click();
  await expect(page.locator('.turn')).toHaveCount(100); const ids = await page.locator('.turn').evaluateAll(rows => rows.map(r => r.getAttribute('data-run-id'))); expect(new Set(ids).size).toBe(100);
  await expect(page.getByRole('button', { name: '加载更早的记录' })).toHaveCount(0);
  for (let i = 0; i < 6 && await page.locator('.session-link').count() < 100; i++) { const previous = await page.locator('.session-link').count(); await page.getByRole('button', { name: '加载更多会话' }).click(); await expect.poll(() => page.locator('.session-link').count()).toBeGreaterThan(previous); }
  expect(await page.locator('.session-link').count()).toBeGreaterThanOrEqual(100);
  await page.setViewportSize({ width: 768, height: 1024 }); await page.evaluate(() => document.documentElement.dataset.theme = 'dark');
  await page.screenshot({ path: resolve(evidence, 'screenshots/V10-history.png') });
});
