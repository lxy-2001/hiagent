import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
import { login, submit } from './helpers';
const directory = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots'); mkdirSync(directory, { recursive: true });
test('non-JSON list error is safe and recoverable', async ({ page }) => {
  await page.route('**/api/agent/sessions', route => route.fulfill({ status: 502, contentType: 'text/html', body: '<b>private-token-should-not-render</b>' }));
  await login(page); await expect(page.getByRole('alert')).toContainText('暂时无法读取会话列表');
  await expect(page.getByText('private-token-should-not-render')).toHaveCount(0);
  await page.screenshot({ path: resolve(directory, 'V09-unavailable.png') });
});
test('lost submission does not invent acceptance or permit automatic replay', async ({ page }) => {
  let posts = 0;
  await page.route('**/api/agent/tasks', async route => { posts++; await route.fetch(); await route.abort('failed'); });
  await login(page); await submit(page, 'remember'); await expect(page.getByText('提交结果尚未确认。', { exact: false })).toBeVisible();
  await expect(page.getByLabel('任务内容')).toHaveValue('remember'); await expect(page.getByRole('button', { name: '发送任务', exact: true })).toBeDisabled();
  expect(posts).toBe(1); await page.screenshot({ path: resolve(directory, 'V09-uncertain.png') });
});
test('64KiB markdown and256 steps remain bounded and operable', async ({ page }) => {
  const rich = '## 大内容演示\n\n| 模块 | 责任 |\n|---|---|\n| Core | Runtime |\n\n```java\nclass Agent {}\n```\n\n';
  const content = rich + 'x'.repeat(65536 - Buffer.byteLength(rich));
  await page.route('**/api/agent/tasks/*', async route => { const response = await route.fetch(); const value = await response.json(); if (value.status === 'SUCCEEDED') value.finalAnswer = content; await route.fulfill({ response, json: value }); });
  await page.route('**/api/agent/tasks/*/steps', route => route.fulfill({ json: Array.from({ length: 256 }, (_, n) => ({ stepNo: n + 1, stepType: 'TOOL_RESULT', toolName: 'test-tool', input: null, output: '合成步骤摘要', status: 'SUCCEEDED', latencyMs: 1, promptTokens: null, completionTokens: null, errorMessage: null, decisionId: null, callId: null, errorCode: null, terminal: false })) }));
  await login(page); await submit(page, 'remember'); await expect(page.getByRole('heading', { name: '大内容演示' })).toBeAttached();
  await page.getByRole('button', { name: '打开任务详情' }).click(); await expect(page.locator('.step-timeline li')).toHaveCount(256);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: resolve(directory, 'V03-large-markdown.png') });
  await page.getByRole('button', { name: '关闭详情' }).click(); await expect(page.getByLabel('任务内容')).toBeEnabled();
});
test('keyboard preferences dialog has a visible focus indicator', async ({ page }) => {
  await login(page); await page.getByRole('button', { name: '外观偏好' }).focus(); await page.keyboard.press('Enter'); await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Tab'); await page.screenshot({ path: resolve(directory, 'V11-keyboard-focus.png') }); await page.keyboard.press('Escape'); await expect(page.getByRole('button', { name: '外观偏好' })).toBeFocused();
});
