import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';
export default defineConfig({testDir:'./tests/e2e',outputDir:resolve(import.meta.dirname,'../../.ua/009-workbench/browser-results'),fullyParallel:false,retries:0,reporter:'list',use:{baseURL:'http://127.0.0.1:15179',trace:'retain-on-failure'},webServer:{command:'npm run dev -- --port 15179',url:'http://127.0.0.1:15179',reuseExistingServer:false},projects:[{name:'chromium',use:{...devices['Desktop Chrome']}}]});
