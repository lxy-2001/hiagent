import { test, expect } from '@playwright/test';
test('bounded stream failure falls back to reads and route change stops reconnects', async ({ page }) => {
  let events = 0;
  await page.route('**/api/agent/tasks/*/events', async route => { events++; await route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"SERVICE_RECOVERING"}' }); });
  await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill('fixture-admin'); await page.getByLabel('密码', { exact: true }).fill('fixture-only-password'); await page.getByRole('button', { name: '进入工作台' }).click();
  await page.getByLabel('任务内容').fill('note'); await page.getByRole('button', { name: '发送任务', exact: true }).click();
  await expect(page.getByText('等待你的确认', { exact: true })).toBeVisible();
  await expect.poll(() => events, { timeout: 12000 }).toBe(4);
  await page.getByRole('button', { name: '打开任务详情' }).click();
  await expect(page.getByText('polling', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '取消当前任务' }).click();
  await expect(page.locator('.message-list').getByText('已取消', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: /新任务/ }).click(); const stopped = events;
  await page.waitForTimeout(1200); expect(events).toBe(stopped);
  await expect(page.getByRole('heading', { name: '今天想完成什么？' })).toBeVisible();
});
