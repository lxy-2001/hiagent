import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';
const packaged = process.env.WORKBENCH_PACKAGED === '1';
export default defineConfig({
  testDir: './tests/e2e', outputDir: resolve(import.meta.dirname, '../../.ua/009-workbench/browser-results'),
  fullyParallel: false, workers: 1, retries: 0, reporter: 'list',
  use: { baseURL: packaged ? 'http://127.0.0.1:15180' : 'http://127.0.0.1:15179', trace: 'retain-on-failure' },
  webServer: [
    { command: 'node scripts/fixture.mjs', url: 'http://127.0.0.1:15180/index.html', reuseExistingServer: false, timeout: 90000 },
    ...(packaged ? [] : [{ command: 'node scripts/preview.mjs', url: 'http://127.0.0.1:15179', reuseExistingServer: false, env: { WORKBENCH_BACKEND_URL: 'http://127.0.0.1:15180' } }]),
  ],
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }, { name: 'firefox', testMatch: '**/crossbrowser.spec.ts', use: { ...devices['Desktop Firefox'] } }, { name: 'webkit', timeout: 60000, testMatch: '**/crossbrowser.spec.ts', use: { ...devices['Desktop Safari'] } }],
});
