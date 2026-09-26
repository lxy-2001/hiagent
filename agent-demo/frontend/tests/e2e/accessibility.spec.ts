import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { login, submit } from './helpers';
for (const theme of ['light', 'dark']) test(`keyboard and contrast ${theme}`, async ({ page }) => {
  await page.addInitScript(value => localStorage.setItem('hiagent.theme', value), theme);
  await page.goto('/'); expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations).toEqual([]);
  await login(page); await submit(page, 'research'); await page.getByRole('button', { name: '查看来源 S1' }).click();
  expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations).toEqual([]);
});
test('mobile drawers trap focus, Escape closes and restores trigger', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 }); await login(page);
  const trigger = page.getByRole('button', { name: '打开会话导航' }); await trigger.click();
  await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  expect(await page.evaluate(() => !!document.activeElement?.closest('.sidebar'))).toBe(true);
  await page.keyboard.press('Escape'); await expect(trigger).toHaveAttribute('aria-expanded', 'false'); await expect(trigger).toBeFocused();
  await expect(page.getByRole('button', { name: '外观偏好' })).not.toBeInViewport();
});
