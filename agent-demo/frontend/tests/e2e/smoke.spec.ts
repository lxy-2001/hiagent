import { test, expect } from '@playwright/test';
test('serves login without remote assets or API reads before authentication', async ({ page, baseURL }) => {
  const external: string[] = [], reads: string[] = [];
  page.on('request', r => { if (!r.url().startsWith(baseURL! + '/')) external.push(r.url()); if (new URL(r.url()).pathname.startsWith('/api/')) reads.push(r.url()); });
  await page.goto('/'); await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
  expect(external).toEqual([]); expect(reads).toEqual([]);
});
