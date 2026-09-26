import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
test('served entry and assets match the verified production build', async ({ request }) => {
  const info = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../target/workbench/build-info.json'), 'utf8')) as { files: Record<string, string> };
  for (const [name, digest] of Object.entries(info.files)) {
    if (name === 'asset-manifest.json') continue;
    const response = await request.get('/' + name);
    expect(response.ok(), name).toBe(true);
    expect(createHash('sha256').update(await response.body()).digest('hex'), name).toBe(digest);
  }
});
