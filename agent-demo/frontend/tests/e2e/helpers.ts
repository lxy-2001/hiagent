import { expect, type Page, type APIRequestContext } from '@playwright/test';
export async function login(page: Page) {
  await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill('fixture-admin');
  await page.getByLabel('密码', { exact: true }).fill('fixture-only-password');
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByText('离线演示', { exact: true })).toBeAttached();
}
export async function submit(page: Page, input: string) { await page.getByLabel('任务内容').fill(input); await page.getByRole('button', { name: '发送任务', exact: true }).click(); }
export async function backend(request: APIRequestContext) {
  const response = await request.post('http://127.0.0.1:15180/api/auth/login', { data: { username: 'fixture-admin', password: 'fixture-only-password' } });
  const token = (await response.json()).accessToken as string;
  const headers = { Authorization: `Bearer ${token}` };
  return { async writes() { const value = await request.get('http://127.0.0.1:15180/api/demo/fixture-stats', { headers }); expect(value.ok()).toBe(true); return (await value.json()).writes as number; }, headers };
}
