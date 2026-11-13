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
  return { call, email };
}

test('Home guides setup, flags low stock, searches, exports and recovers a password', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const { call: owner } = await signUp(page, 'Cafe Owner');
  const name = 'Cafe ' + unique();
  const org = await owner<{ id: string }>('POST', '/organizations', { name });
  const base = '/organizations/' + org.id;
  const root = '/app/organizations/' + org.id;

  // A new business opens on Home with the setup checklist.
  await page.goto(root);
  await expect(page).toHaveURL(new RegExp(root + '/home$'));
  const setup = page.getByRole('region', { name: 'Set up ' + name });
  await expect(setup.getByText('0 of 5 done', { exact: false })).toBeVisible();
  await expect(setup.getByRole('link', { name: 'Add a location', exact: true })).toBeVisible();

  const room = await owner<{ id: string }>('POST', base + '/locations', { name: 'Stockroom', type: 'ROOM', parentId: null });
  const shelf = await owner<{ id: string }>('POST', base + '/locations', { name: 'Shelf A', type: 'SHELF', parentId: room.id });
  const milk = await owner<{ id: string }>('POST', base + '/items', { name: 'Oat milk', sku: 'OAT-01', unit: 'LITER' });
  await owner('POST', base + '/inventory/receipts', { operationKey: crypto.randomUUID(), itemId: milk.id, locationId: shelf.id, quantity: '3' });
  await page.reload();
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await expect(setup.getByText('3 of 5 done', { exact: false })).toBeVisible();

  // Setting a minimum from the item page makes the slot low.
  await page.goto(root + '/items/' + milk.id);
  await page.getByRole('button', { name: 'More actions Stockroom › Shelf A' }).click();
  await page.getByRole('button', { name: /^Set minimum/ }).click();
  const minimum = page.getByRole('dialog', { name: 'Set a minimum' });
  await minimum.getByLabel('Keep at least (L)', { exact: true }).fill('5');
  await minimum.getByRole('button', { name: 'Save minimum', exact: true }).click();
  await expect(page.getByText('Minimum at Stockroom › Shelf A set to 5 L')).toBeVisible();
  await expect(page.locator('.minimum-note').getByText('Low', { exact: true })).toBeVisible();

  // Home lists it with the shortfall, and Request opens a prefilled request.
  await page.goto(root + '/home');
  const low = page.getByRole('region', { name: 'Low stock' });
  await expect(low.getByText('short 2 L')).toBeVisible();
  await low.getByRole('button', { name: /^Request Oat milk/ }).click();
  const ask = page.getByRole('dialog', { name: 'Request more stock' });
  await expect(ask.getByLabel('Quantity (L)', { exact: true })).toHaveValue('2');
  await ask.getByRole('button', { name: 'Submit request', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'R-1 · Oat milk', exact: true })).toBeVisible();
  await page.goto(root + '/home');
  await expect(low.getByRole('link', { name: 'R-1 open', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Requests' }).getByRole('link', { name: /R-1 · Oat milk/ })).toBeVisible();
  // The count tiles say what their lists show: one request to review, one low item.
  await expect(page.getByRole('link', { name: '1 Needs review', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '1 Low stock', exact: true })).toBeVisible();

  // The Inventory low-stock filter agrees.
  await low.getByRole('link', { name: /^See all/ }).click();
  await expect(page.getByLabel('Low stock only')).toBeChecked();
  await expect(page.getByRole('link', { name: 'Oat milk', exact: true })).toBeVisible();

  // Search finds the item by SKU.
  await page.goto(root + '/home');
  await page.getByRole('main').getByRole('searchbox').fill('oat-01');
  await page.getByRole('main').getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Search', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Items' }).getByRole('link', { name: 'Oat milk', exact: true })).toBeVisible();

  // Export data downloads a spreadsheet.
  await page.getByRole('link', { name: 'Export data', exact: true }).click();
  const downloading = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download CSV for Stock by location', exact: true }).click();
  const file = await downloading;
  expect(file.suggestedFilename()).toMatch(/^backroom-stock-\d{4}-\d{2}-\d{2}\.csv$/);
  const csv = await readFile(await file.path(), 'utf8');
  expect(csv.startsWith('﻿Item,SKU,Location,Unit,On hand,Minimum,Low')).toBeTruthy();
  expect(csv).toContain('Oat milk,OAT-01,Stockroom › Shelf A,L,3,5,Yes,');

  // An employee on a phone forgets their password; the owner issues a link; the old session ends.
  const invite = await owner<{ invitePath: string }>('POST', base + '/invitations', { role: 'EMPLOYEE' });
  const phoneContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const phone = await phoneContext.newPage();
  phone.on('pageerror', error => errors.push(error.message));
  const { call: employee, email } = await signUp(phone, 'Cafe Employee');
  await employee('POST', '/invitations/accept', { token: invite.invitePath.split('token=')[1] });
  await phone.goto(root + '/home');
  await expect(phone.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await expect(phone.getByRole('heading', { name: /^Set up/ })).toHaveCount(0);
  expect(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();

  await page.goto(root + '/people');
  await page.getByRole('button', { name: 'Reset password for Cafe Employee', exact: true }).click();
  const reset = page.getByRole('dialog', { name: 'Reset password for Cafe Employee?' });
  await reset.getByRole('button', { name: 'Create reset link', exact: true }).click();
  const link = await reset.getByLabel('Reset link', { exact: true }).inputValue();
  expect(link).toContain('/reset#token=');

  const resetContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const visitor = await resetContext.newPage();
  visitor.on('pageerror', error => errors.push(error.message));
  await visitor.goto(new URL(link).pathname + new URL(link).hash);
  await expect(visitor.getByText(email)).toBeVisible();
  const fresh = 'A new phone passphrase 2026';
  await visitor.getByLabel('New password', { exact: true }).fill(fresh);
  await visitor.getByLabel('Confirm new password', { exact: true }).fill(fresh);
  await visitor.getByRole('button', { name: 'Set password and sign in', exact: true }).click();
  await expect(visitor.getByRole('heading', { name: 'Your businesses', exact: true })).toBeVisible();

  await phone.goto(root + '/items');
  await expect(phone).toHaveURL(/\/signin(\?|$)/);
  await phone.getByLabel('Email', { exact: true }).fill(email);
  await phone.getByLabel('Password', { exact: true }).fill(fresh);
  await phone.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(phone.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible();

  expect(errors).toEqual([]);
  await phoneContext.close();
  await resetContext.close();
});
