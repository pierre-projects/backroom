import { test, expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';

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

test('an employee requests stock, the owner approves less, and two deliveries complete it', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const owner = await signUp(page, 'Cafe Owner');
  const org = await owner<{ id: string }>('POST', '/organizations', { name: 'Cafe ' + unique() });
  const base = '/organizations/' + org.id;
  const root = '/app/organizations/' + org.id;
  const room = await owner<{ id: string }>('POST', base + '/locations', { name: 'Stockroom', type: 'ROOM', parentId: null });
  const shelf = await owner<{ id: string }>('POST', base + '/locations', { name: 'Shelf A', type: 'SHELF', parentId: room.id });
  const milk = await owner<{ id: string }>('POST', base + '/items', { name: 'Oat milk', unit: 'LITER' });
  const invite = await owner<{ invitePath: string }>('POST', base + '/invitations', { role: 'EMPLOYEE' });

  const phoneContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const phone = await phoneContext.newPage();
  phone.on('pageerror', error => errors.push(error.message));
  const employee = await signUp(phone, 'Cafe Employee');
  await employee('POST', '/invitations/accept', { token: invite.invitePath.split('token=')[1] });

  // Employee asks for 10 L from the item page on a phone.
  await phone.goto(root + '/items/' + milk.id);
  await phone.getByRole('button', { name: 'Request more', exact: true }).click();
  const ask = phone.getByRole('dialog', { name: 'Request more stock' });
  await ask.getByLabel('Deliver to', { exact: true }).selectOption({ label: 'Stockroom › Shelf A' });
  await ask.getByLabel('Quantity (L)', { exact: true }).fill('10');
  await ask.getByRole('button', { name: 'Submit request', exact: true }).click();
  await expect(phone.getByRole('heading', { name: 'R-1 · Oat milk', exact: true })).toBeVisible();

  // A second request for the same shelf warns about the open one.
  await phone.goto(root + '/items/' + milk.id);
  await phone.getByRole('button', { name: 'Request more', exact: true }).click();
  const again = phone.getByRole('dialog', { name: 'Request more stock' });
  await again.getByLabel('Deliver to', { exact: true }).selectOption({ label: 'Stockroom › Shelf A' });
  await expect(again.getByText('There is already an open request here')).toBeVisible();
  await expect(again.getByRole('button', { name: 'Submit anyway', exact: true })).toBeVisible();
  await again.getByRole('button', { name: 'Cancel', exact: true }).click();

  // Owner sees it waiting for review and approves 6 L.
  await page.goto(root + '/requests');
  await expect(page.getByRole('button', { name: 'Needs review', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('link', { name: /R-1/ }).click();
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  const approve = page.getByRole('dialog', { name: 'Approve R-1?' });
  await approve.getByLabel('Approved quantity (L)', { exact: true }).fill('6');
  await approve.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(page.getByText('Approved 6 L. Anyone can now record the delivery.')).toBeVisible();

  // Employee records two deliveries.
  await phone.goto(root + '/requests');
  await expect(phone.getByRole('button', { name: 'To receive', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await phone.getByRole('link', { name: /R-1/ }).click();
  for (const amount of ['4', '2']) {
    await phone.getByRole('button', { name: 'Record delivery', exact: true }).click();
    const delivery = phone.getByRole('dialog', { name: 'Record a delivery for R-1' });
    await delivery.getByLabel('Quantity delivered (L)', { exact: true }).fill(amount);
    await delivery.getByRole('button', { name: 'Record delivery', exact: true }).click();
    await expect(phone.getByText('Recorded ' + amount + ' L.')).toBeVisible();
  }
  await expect(phone.locator('.badge', { hasText: 'Received' })).toBeVisible();
  await expect(phone.getByRole('button', { name: 'Record delivery', exact: true })).toHaveCount(0);
  expect(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await phone.goto(root + '/items/' + milk.id);
  await expect(phone.getByRole('definition').filter({ hasText: '6 L' }).first()).toBeVisible();

  // Owner closes a second, partly delivered request short with a reason.
  const second = await owner<{ id: string; version: number }>('POST', base + '/requests', { itemId: milk.id, locationId: shelf.id, quantity: '5' });
  await owner('POST', base + '/requests/' + second.id + '/approve', { expectedVersion: second.version });
  await page.goto(root + '/requests/' + second.id);
  await page.getByRole('button', { name: 'Close short', exact: true }).click();
  const close = page.getByRole('dialog', { name: 'Close R-2 short?' });
  await close.getByLabel('Reason', { exact: true }).fill('Supplier is out of oat milk');
  await close.getByRole('button', { name: 'Close short', exact: true }).click();
  await expect(page.getByText('Supplier is out of oat milk')).toBeVisible();
  await expect(page.getByText('Closed short', { exact: true }).first()).toBeVisible();
  expect(errors).toEqual([]);
  await phoneContext.close();
});
