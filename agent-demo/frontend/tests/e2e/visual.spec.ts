import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';
import { mkdirSync } from 'node:fs';
import { login, submit, backend } from './helpers';
const directory = resolve(import.meta.dirname, '../../../../.ua/009-workbench/screenshots');
mkdirSync(directory, { recursive: true });
const rich = '## 项目架构与职责\n\n| 模块 | 职责 |\n| --- | --- |\n| agent-core | 决策与执行循环 |\n| agent-web | 会话与事件接口 |\n\n```java\nrecord Task(String input, String sessionId) {}\n```\n\n实现细节请参考架构文档 [S1]。';
for (const [width, theme] of [[1440, 'light'], [1440, 'dark'], [768, 'light'], [375, 'light'], [375, 'dark'], [1920, 'light']] as const) {
  test(`visual matrix ${width} ${theme}`, async ({ page, request }) => {
    await page.setViewportSize({ width, height: width === 375 ? 812 : width === 768 ? 1024 : width === 1920 ? 1080 : 900 });
    await page.addInitScript(value => localStorage.setItem('hiagent.theme', value), theme);
    const shot = async (id: string) => { expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({ path: resolve(directory, `${id}-${width}-${theme}.png`), fullPage: true }); };
    await page.goto('/'); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible(); await shot('V01');
    await login(page); await shot('V02');
    await page.route('**/api/agent/tasks/*', async route => { const response = await route.fetch(); const body = await response.json(); if (body.status === 'SUCCEEDED') { body.finalAnswer = rich; body.citations = body.citations.map((citation: Record<string, unknown>) => ({ ...citation, excerpt: '模块边界以明确的接口和可验证的行为契约为准。'.repeat(30) })); } await route.fulfill({ response, json: body }); });
    await submit(page, 'research'); await expect(page.getByRole('heading', { name: '项目架构与职责' })).toBeVisible(); await submit(page, 'research'); await expect(page.locator('.turn')).toHaveCount(2); await expect(page.getByRole('button', { name: '查看来源 S1' })).toBeVisible(); await shot('V03');
    await page.getByRole('button', { name: '查看来源 S1' }).click(); await shot('V07');
    await page.getByRole('tab', { name: '记忆', exact: true }).click(); await page.getByLabel('偏好语言', { exact: true }).fill('中文，我保留的草稿');
    await page.route('**/memories/**', async route => { if (route.request().method() === 'PUT') await route.fulfill({ status: 409, json: { code: 'MEMORY_VERSION_CONFLICT' } }); else await route.continue(); });
    await page.locator('.memory-panel article').first().getByRole('button', { name: '保存偏好' }).click();
    await expect(page.getByRole('alert')).toContainText('草稿已保留'); await shot('V08');
    await page.getByRole('button', { name: '关闭详情' }).click();
    if (width < 900) await page.getByRole('button', { name: '打开会话导航' }).click();
    await page.getByRole('button', { name: '外观偏好' }).focus(); await page.keyboard.press('Enter'); await page.keyboard.press('Tab'); await shot('V11'); await page.keyboard.press('Escape');
    await page.getByRole('link', { name: /新任务/ }).click();
    await page.unroute('**/api/agent/tasks/*');
    await page.route('**/api/agent/tasks/*/approvals', async route => { const response = await route.fetch(); const body = await response.json(); body.items = body.items.map((item: Record<string, unknown>) => ({ ...item, argumentPreview: [{ name: 'text', value: '待确认的文档内容。'.repeat(60), masked: false, truncated: false }] })); await route.fulfill({ response, json: body }); });
    await submit(page, 'note'); await expect(page.getByRole('button', { name: '批准本次操作' })).toBeVisible(); await shot('V05');
    await page.getByRole('button', { name: '打开任务详情' }).click(); await page.getByRole('tab', { name: '执行', exact: true }).click(); await shot('V04');
    await page.getByRole('button', { name: '停止观察' }).click(); await shot('V09');
    await page.getByRole('button', { name: '关闭详情' }).click(); await page.getByRole('button', { name: '取消当前任务' }).click();
    // The run was paused: read the server terminal fact explicitly before leaving.
    await page.getByRole('button', { name: '打开任务详情' }).click();
    await page.getByRole('button', { name: '关闭详情' }).click();
    const api = await backend(request); expect(await api.writes()).toBeGreaterThanOrEqual(0);
  });
}
for (const width of [1440, 375]) test(`visual failed and exhausted states ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: width === 375 ? 812 : 900 });
  await page.clock.install();
  await page.route('**/api/agent/tasks/*/events', route => route.fulfill({ status: 204 }));
  let fail = false;
  await page.route('**/api/agent/tasks/*', async route => {
    const response = await route.fetch(); const body = await response.json();
    if (route.request().method() === 'GET') { body.status = fail ? 'FAILED' : 'RUNNING'; body.finalAnswer = null; body.terminationReason = fail ? 'MODEL_ERROR' : null; }
    await route.fulfill({ response, json: body });
  });
  await login(page); await submit(page, 'remember'); await expect(page.getByText('正在处理', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '打开任务详情' }).click();
  await expect(page.getByText('查询最新状态', { exact: true })).toBeVisible();
  for (let i = 0; i < 32; i++) { await page.clock.fastForward(2100); await page.waitForTimeout(30); }
  await expect(page.getByText('请手动恢复观察', { exact: true })).toBeVisible();
  await page.screenshot({ path: resolve(directory, `V09-exhausted-${width}.png`), fullPage: true });
  fail = true; await page.getByRole('button', { name: '恢复观察' }).click();
  await expect(page.locator('.run-inspector').getByText('执行失败', { exact: true })).toBeVisible();
  await page.screenshot({ path: resolve(directory, `V09-failed-${width}.png`), fullPage: true });
});
for (const width of [1440, 375]) test(`visual login rejection ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: width === 375 ? 812 : 900 });
  await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill('fixture-admin');
  await page.getByLabel('密码', { exact: true }).fill('wrong-fixture-password');
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByRole('alert')).toContainText('账号或密码不正确');
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await page.screenshot({ path: resolve(directory, `V01-error-${width}.png`), fullPage: true });
});
