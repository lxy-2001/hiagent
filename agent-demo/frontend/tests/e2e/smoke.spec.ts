import { test,expect } from '@playwright/test';
test('serves the local workbench entry without remote assets',async({page})=>{const external:string[]=[];page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:15179/'))external.push(r.url())});await page.goto('/');await expect(page.getByRole('heading',{name:'HiAgent'})).toBeVisible();expect(external).toEqual([])});
