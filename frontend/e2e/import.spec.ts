import { test, expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const password = 'Backroom browser test 42!';
const unique = () => crypto.randomUUID().slice(0, 12);

async function client(request: APIRequestContext) {
  return async <T>(method: string, path: string, data?: unknown): Promise<T> => {
    const csrf = await (await request.get('/api/v1/auth/csrf')).json() as { token: string; headerName: string };
    const response = await request.fetch('/api/v1' + path, { method, headers: { [csrf.headerName]: csrf.token }, data });
    expect(response.ok(), method + ' ' + path + ' ' + await response.text()).toBeTruthy();
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  };
}
async function signUp(page: Page, name: string) {
  const email = unique() + '@example.test';
  const call = await client(page.request);
  await call('POST', '/auth/register', { email, password, displayName: name });
  const csrf = await (await page.request.get('/api/v1/auth/csrf')).json() as { token: string; headerName: string };
  expect((await page.request.post('/api/v1/auth/login', { headers: { [csrf.headerName]: csrf.token }, form: { email, password } })).ok()).toBeTruthy();
  return call;
}

const catalog = [
  'Item,SKU,Unit,Location,Starting quantity,Minimum',
  'House espresso,COF-101,kg,Stockroom > Coffee shelf,12,4',
  '"Paper cups, 12 oz",CUP-12,each,Stockroom > Dry goods > Cups bin,240,100',
  'Oat milk,,L,Coffee bar > Fridge,6,',
  'Napkins,,each,,,5',
  'Brew scale,EQ-1,each,Stockroom > Coffee shelf,1,',
].join('\r\n') + '\r\n';

test('an owner on a phone imports a spreadsheet after reviewing duplicates and problems', async ({ browser, baseURL }) => {
  const errors: string[] = [];
  const context = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, acceptDownloads: true });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const owner = await signUp(page, 'Cafe Owner');
  const org = await owner<{ id: string }>('POST', '/organizations', { name: 'Cafe ' + unique() });
  const base = '/organizations/' + org.id;
  const root = '/app/organizations/' + org.id;
  // Already in the business: one SKU from the file, and an item named like a row without SKU.
  await owner('POST', base + '/items', { name: 'Brew scale', sku: 'EQ-1', unit: 'EACH' });
  await owner('POST', base + '/items', { name: 'Oat milk', unit: 'LITER' });

  await page.goto(root + '/items');
  await page.getByRole('link', { name: 'Import items', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Import items', exact: true })).toBeVisible();

  const downloading = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download template', exact: true }).click();
  const template = await downloading;
  expect(template.suggestedFilename()).toBe('backroom-import-template.csv');
  expect((await readFile(await template.path(), 'utf8')).startsWith('﻿Item,SKU,Unit,Location,Starting quantity,Minimum')).toBeTruthy();

  await page.getByLabel('Choose a CSV file').setInputFiles({ name: 'catalog.csv', mimeType: 'text/csv', buffer: Buffer.from(catalog, 'utf8') });
  await expect(page.getByRole('heading', { name: 'Review catalog.csv', exact: true })).toBeVisible();
  const counts = page.getByRole('list', { name: 'What this file contains' });
  await expect(counts.getByText('2 new items')).toBeVisible();
  await expect(counts.getByText('1 already exists')).toBeVisible();
  await expect(counts.getByText('1 possible duplicate')).toBeVisible();
  await expect(counts.getByText('1 with problems')).toBeVisible();
  await expect(counts.getByText('4 new locations')).toBeVisible();
  await expect(page.getByText('A minimum needs a Location.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Import 2 rows', exact: true })).toBeVisible();

  // Include the possible duplicate: it is a different oat milk kept in the bar fridge.
  await page.getByRole('checkbox', { name: /Include line 4/ }).check();
  await page.getByRole('button', { name: 'Import 3 rows', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Import finished', exact: true })).toBeVisible();
  await expect(page.getByText('3 items created')).toBeVisible();
  // Four from the reviewed rows plus Coffee bar and its Fridge for the included oat milk.
  await expect(page.getByText('6 locations created')).toBeVisible();
  await expect(page.getByText('3 starting quantities recorded')).toBeVisible();
  await expect(page.getByText('1 row skipped as duplicates')).toBeVisible();
  await expect(page.getByText('1 row not imported because of problems')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();

  // The new items are in Inventory, and their new shelf can be walked to.
  await page.getByRole('link', { name: 'Open Inventory', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Paper cups, 12 oz', exact: true })).toBeVisible();
  await expect(page.getByText('240 on hand')).toBeVisible();
  await page.goto(root + '/locations');
  await page.getByRole('link', { name: 'Stockroom', exact: true }).click();
  await page.getByRole('link', { name: 'Dry goods', exact: true }).click();
  await page.getByRole('link', { name: 'Cups bin', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Dry goods', exact: true })).toBeVisible();
  await expect(page.getByText('240 here')).toBeVisible();

  expect(errors).toEqual([]);
  await context.close();
});
