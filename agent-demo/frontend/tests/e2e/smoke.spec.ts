import { test, expect } from '@playwright/test';
test('serves login without remote assets or API reads before authentication', async ({ page }) => {
  const external: string[] = [], reads: string[] = [];
  page.on('request', r => { if (!r.url().startsWith('http://127.0.0.1:15179/')) external.push(r.url()); if (new URL(r.url()).pathname.startsWith('/api/')) reads.push(r.url()); });
  await page.goto('/'); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
  expect(external).toEqual([]); expect(reads).toEqual([]);
});
