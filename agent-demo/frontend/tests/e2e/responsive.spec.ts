import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
import { login, submit } from './helpers';
const directory = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots'); mkdirSync(directory, { recursive: true });
for (const width of [375, 768, 1440, 1920]) for (const theme of ['light', 'dark']) test(`responsive ${width} ${theme}`, async ({ page }) => {
  await page.setViewportSize({ width, height: width === 375 ? 812 : 1024 });
  await page.addInitScript(value => localStorage.setItem('hiagent.theme', value), theme); await login(page);
  await page.screenshot({ path: resolve(directory, `V02-${width}-${theme}.png`) });
  await submit(page, 'research'); await page.getByRole('button', { name: '查看来源 S1' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.locator('.run-inspector').evaluate(e => e.scrollWidth <= e.clientWidth + 1)).toBe(true);
  await page.screenshot({ path: resolve(directory, `V07-${width}-${theme}.png`) });
  await page.getByRole('tab', { name: '记忆', exact: true }).click();
  await page.getByLabel('偏好语言', { exact: true }).fill('中文 / English');
  await page.screenshot({ path: resolve(directory, `V08-${width}-${theme}.png`) });
});
test('200% effective zoom and reduced motion keep the composer reachable', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, viewport: { width: 720, height: 450 }, deviceScaleFactor: 2, reducedMotion: 'reduce' });
  try {
    const page = await context.newPage(); await login(page);
    expect(await page.evaluate(() => innerWidth)).toBe(720);
    await page.getByLabel('任务内容').scrollIntoViewIfNeeded();
    await expect(page.getByLabel('任务内容')).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByLabel('任务内容').fill('remember');
    await page.getByRole('button', { name: '发送任务', exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '发送任务', exact: true })).toBeInViewport();
    await page.screenshot({ path: resolve(directory, 'V12-zoom.png') });
    await page.getByLabel('任务内容').press('Enter'); await expect(page.getByText('Remembered conversation', { exact: true })).toBeVisible();
    await page.setViewportSize({ width: 375, height: 420 });
    await page.getByLabel('任务内容').fill('短窗口下的输入'); await expect(page.getByRole('button', { name: '发送任务', exact: true })).toBeInViewport();
  } finally { await context.close(); }
});
