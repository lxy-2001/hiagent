import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
import { login, submit, backend } from './helpers';
const screenshots = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots'); mkdirSync(screenshots, { recursive: true });
for (const decision of ['APPROVE', 'REJECT', 'CANCEL'] as const) test(`real MCP ${decision} respects execution count`, async ({ page, request }) => {
  const api = await backend(request), before = await api.writes(); await login(page); await submit(page, 'note');
  await expect(page.getByRole('button', { name: '批准本次操作' })).toBeVisible(); expect(await api.writes()).toBe(before);
  await page.screenshot({ path: resolve(screenshots, 'V05-approval.png'), fullPage: true });
  if (decision === 'APPROVE') { await page.getByRole('button', { name: '批准本次操作' }).click(); await expect(page.getByText('RECEIPT_CONFIRMED', { exact: true })).toBeVisible(); }
  else if (decision === 'REJECT') { await page.getByRole('button', { name: '拒绝', exact: true }).click(); await expect(page.getByText('你已拒绝本次操作。')).toBeVisible(); }
  else { await page.getByRole('button', { name: '取消当前任务' }).click(); await expect(page.locator('.message-list').getByText('已取消', { exact: true })).toBeVisible(); }
  expect(await api.writes()).toBe(before + (decision === 'APPROVE' ? 1 : 0));
});
test('lost decision response is checked by reads and never replayed', async ({ page, request }) => {
  const api = await backend(request), before = await api.writes(); let decisions = 0;
  await page.route('**/approvals/*/decision', async route => { decisions++; await route.fetch(); await route.abort('failed'); });
  await login(page); await submit(page, 'note'); await page.getByRole('button', { name: '批准本次操作' }).click();
  await expect(page.getByText('RECEIPT_CONFIRMED', { exact: true })).toBeVisible();
  expect(decisions).toBe(1); expect(await api.writes()).toBe(before + 1);
});

test('explicit synthetic UNKNOWN projection cannot offer a replay action', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 }); await page.addInitScript(() => localStorage.setItem('hiagent.theme', 'dark'));
  await page.route('**/api/agent/tasks/*/approvals', async route => {
    const response = await route.fetch(); const body = await response.json();
    body.items = body.items.map((item: Record<string, unknown>) => ({ ...item, status: 'APPROVED', outcome: 'UNKNOWN', dispatchCount: 1 }));
    await route.fulfill({ response, json: body });
  });
  await login(page); await submit(page, 'note');
  await expect(page.getByText('执行结果未知：操作可能已经发生，请先核对实际结果。')).toBeVisible();
  await expect(page.getByRole('button', { name: '批准本次操作' })).toHaveCount(0);
  await page.screenshot({ path: resolve(screenshots, 'V06-unknown-projection.png'), fullPage: true });
  await page.getByRole('button', { name: '取消当前任务' }).click();
});
