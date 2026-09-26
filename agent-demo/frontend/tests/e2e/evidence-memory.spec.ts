import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
import { login, submit } from './helpers';
const screenshots = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots'); mkdirSync(screenshots, { recursive: true });
test('research binds sources and memory updates preserve the returned version', async ({ page }) => {
  await login(page); await submit(page, 'research');
  await page.getByRole('button', { name: '查看来源 S1' }).click();
  await expect(page.getByRole('region', { name: '引用来源' })).toBeVisible();
  await page.screenshot({ path: resolve(screenshots, 'V07-sources.png'), fullPage: true });
  await page.getByRole('tab', { name: '记忆', exact: true }).click();
  await page.getByLabel('偏好语言', { exact: true }).fill('中文');
  await page.locator('.memory-panel article').filter({ hasText: '偏好语言' }).getByRole('button', { name: '保存偏好' }).click();
  await expect(page.getByText('已保存你确认的偏好。')).toBeVisible();
  await page.screenshot({ path: resolve(screenshots, 'V08-memory.png'), fullPage: true });
  const row = page.locator('.memory-panel article').filter({ hasText: '偏好语言' });
  await row.getByRole('button', { name: '清除', exact: true }).click(); await row.getByRole('button', { name: '确认清除', exact: true }).click();
  await expect(page.getByText('记忆已清除，版本已更新。')).toBeVisible(); await expect(row).toContainText('版本 2');
});

test('two real pages preserve a conflicting memory draft until explicit reconciliation', async ({ page, context }) => {
  await login(page); await submit(page, 'remember'); await expect(page.getByText('Remembered conversation', { exact: true })).toBeVisible();
  const url = page.url(); const second = await context.newPage(); await login(second); await second.goto(url);
  for (const target of [page, second]) { await target.getByRole('button', { name: '打开任务详情' }).click(); await target.getByRole('tab', { name: '记忆', exact: true }).click(); await expect(target.getByLabel('偏好语言', { exact: true })).toBeVisible(); }
  await second.getByLabel('偏好语言', { exact: true }).fill('English');
  await page.getByLabel('偏好语言', { exact: true }).fill('中文'); await page.locator('.memory-panel article').first().getByRole('button', { name: '保存偏好' }).click(); await expect(page.getByText('已保存你确认的偏好。')).toBeVisible();
  const row = second.locator('.memory-panel article').first(); await row.getByRole('button', { name: '保存偏好' }).click();
  await expect(second.getByRole('alert')).toContainText('草稿已保留'); await expect(second.getByLabel('偏好语言', { exact: true })).toHaveValue('English');
  await expect(row).toContainText('当前值：中文'); await expect(row.getByRole('button', { name: '保存偏好' })).toBeDisabled();
  await second.getByRole('button', { name: '我已核对，使用最新版本继续编辑' }).click(); await row.getByRole('button', { name: '保存偏好' }).click();
  await expect(row).toContainText('版本 2'); await second.close();
});
for (const kind of ['none', 'synthetic-empty', 'synthetic-failure'] as const) test(`source panel ${kind} distinguishes evidence availability`, async ({ page }) => {
  if (kind !== 'none') await page.route('**/api/agent/tasks/*/steps', route => route.fulfill({ json: [{ stepNo: 1, stepType: 'TOOL_RESULT', toolName: 'knowledge.search', input: null, output: kind === 'synthetic-empty' ? 'hits=0 kept=0 empty=NO_MATCH' : null, status: kind === 'synthetic-empty' ? 'SUCCESS' : 'FAILED', latencyMs: 1, promptTokens: null, completionTokens: null, errorMessage: null, decisionId: null, callId: null, errorCode: kind === 'synthetic-failure' ? 'RAG_VECTOR_UNAVAILABLE' : null, terminal: false }] }));
  await login(page); await submit(page, 'remember'); await expect(page.getByText('Remembered conversation', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '打开任务详情' }).click(); await page.getByRole('tab', { name: '来源', exact: true }).click();
  await expect(page.locator('.source-panel')).toContainText(kind === 'none' ? '没有可核对的引用来源' : kind === 'synthetic-empty' ? '未检索到可用文档' : '检索服务暂不可用');
  await page.screenshot({ path: resolve(screenshots, `source-${kind}.png`), fullPage: true });
});
