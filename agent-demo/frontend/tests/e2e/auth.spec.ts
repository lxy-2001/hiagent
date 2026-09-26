import { test, expect, type Page } from '@playwright/test';
export async function login(page: Page) {
  await page.getByLabel('账号', { exact: true }).fill('fixture-admin');
  await page.getByLabel('密码', { exact: true }).fill('fixture-only-password');
  await page.getByRole('button', { name: '进入工作台' }).click();
  await expect(page.getByRole('heading', { name: '今天想完成什么？' })).toBeVisible();
}
test('real backend login, identity, refresh reauthentication and logout', async ({ page }) => {
  await page.goto('/#/new'); await login(page);
  await expect(page.getByText('离线演示', { exact: true })).toBeVisible();
  await expect(page.getByText('fixture-admin', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => ({ ...localStorage }))).toEqual({});
  await page.reload(); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
  await login(page); await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
});
test('theme dialog uses keyboard focus and restores trigger focus', async ({ page }) => {
  await page.goto('/'); await login(page);
  const trigger = page.getByRole('button', { name: '外观偏好' });
  await trigger.click(); await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByLabel('深色', { exact: false }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(trigger).toBeFocused();
});
for (const width of [1440, 375]) for (const theme of ['light', 'dark']) {
  test(`@visual V01 V02 ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(value => localStorage.setItem('hiagent.theme', value), theme);
    await page.goto('/'); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
    await page.screenshot({ path: info.outputPath(`V01-${width}-${theme}.png`), fullPage: true });
    await login(page); await expect(page.getByText('离线演示', { exact: true })).toBeAttached();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath(`V02-${width}-${theme}.png`), fullPage: true });
  });
}
