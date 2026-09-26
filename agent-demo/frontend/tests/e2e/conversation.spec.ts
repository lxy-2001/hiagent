import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
const screenshots = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots');
mkdirSync(screenshots, { recursive: true });
import { test, expect } from '@playwright/test';
async function login(page: import('@playwright/test').Page) {
  await page.getByLabel('账号', { exact: true }).fill('fixture-admin'); await page.getByLabel('密码', { exact: true }).fill('fixture-only-password');
  await page.getByRole('button', { name: '进入工作台' }).click(); await expect(page.getByText('离线演示', { exact: true })).toBeAttached();
}
test('two real sessions, accepted-only navigation, completion and refresh recovery', async ({ page }) => {
  await page.goto('/'); await login(page);
  await page.getByLabel('任务内容').fill('remember'); await page.getByRole('button', { name: '发送任务', exact: true }).click();
  await expect(page).toHaveURL(/sessions\//); const first = page.url();
  await expect(page.getByText('Remembered conversation', { exact: true })).toBeVisible();
  await page.screenshot({ path: resolve(screenshots, 'V03-conversation.png'), fullPage: true });
  await page.getByRole('link', { name: /新任务/ }).click();
  await page.getByLabel('任务内容').fill('research'); await page.getByLabel('需要文档依据').check(); await page.getByRole('button', { name: '发送任务', exact: true }).click();
  await expect(page.getByText('Java interfaces define module contracts [S1].', { exact: true })).toBeVisible();
  expect(page.url()).not.toBe(first); await page.reload(); await login(page);
  await expect(page.getByText('Java interfaces define module contracts [prior-run-source:1].', { exact: true })).toBeVisible();
  await page.goto(first); await expect(page.getByText('Remembered conversation', { exact: true })).toBeVisible();
});
test('pending run stays observable and cancelling uses server terminal fact', async ({ page }) => {
  await page.goto('/'); await login(page); await page.getByLabel('任务内容').fill('note'); await page.getByRole('button', { name: '发送任务', exact: true }).click();
  await expect(page.getByText('等待你的确认', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '打开任务详情' }).click();
  await expect(page.getByRole('complementary', { name: '任务执行详情' })).toBeVisible();
  await page.screenshot({ path: resolve(screenshots, 'V04-running.png'), fullPage: true });
  await page.getByRole('button', { name: '停止观察' }).click(); await expect(page.getByText('当前状态可能不是最新。', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '恢复观察' }).click(); await page.getByRole('button', { name: '取消当前任务' }).click();
  await expect(page.locator('.message-list').getByText('已取消', { exact: true })).toBeVisible();
});
