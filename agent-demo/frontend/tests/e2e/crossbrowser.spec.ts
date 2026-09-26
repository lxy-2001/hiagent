import { test, expect, type Locator } from '@playwright/test';
import { login } from './helpers';
async function activate(control: Locator) { await expect(control).toBeEnabled(); await control.focus(); await control.press('Enter'); }
test('browser keyboard smoke: login conversation approval and stop observation', async ({ page }) => {
  await login(page, true); await page.getByLabel('任务内容').fill('note'); await page.getByLabel('任务内容').press('Enter');
  await activate(page.getByRole('button', { name: '批准本次操作' }));
  await expect(page.getByText('RECEIPT_CONFIRMED', { exact: true })).toBeVisible();
  await activate(page.getByRole('link', { name: /新任务/ })); await page.getByLabel('任务内容').fill('note'); await page.getByLabel('任务内容').press('Enter');
  await expect(page.getByRole('button', { name: '批准本次操作' })).toBeVisible();
  await activate(page.getByRole('button', { name: '打开任务详情' })); await activate(page.getByRole('button', { name: '停止观察' }));
  await expect(page.getByText('观察已暂停', { exact: true })).toBeVisible();
  await activate(page.getByRole('button', { name: '恢复观察' })); await activate(page.getByRole('button', { name: '取消当前任务' }));
  await expect(page.locator('.message-list').getByText('已取消', { exact: true })).toBeVisible();
});
