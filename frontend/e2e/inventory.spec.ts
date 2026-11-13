import { test, expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';

const password = 'Backroom browser test 42!';
const unique = () => crypto.randomUUID().slice(0, 12);

/** Small JSON client that uses the page's own cookies and CSRF token, for fast setup through the public API. */
async function client(request: APIRequestContext) {
  const token = async () => (await (await request.get('/api/v1/auth/csrf')).json()) as { token: string; headerName: string };
  return async <T>(method: string, path: string, data?: unknown): Promise<T> => {
    const csrf = await token();
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
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as { token: string; headerName: string };
  const login = await page.request.post('/api/v1/auth/login', { headers: { [csrf.headerName]: csrf.token }, form: { email, password } });
  expect(login.ok()).toBeTruthy();
  return { email, call: await client(page.request) };
}

test('receive, transfer, count and reverse stock; an employee records usage on a phone', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const owner = await signUp(page, 'Cafe Owner');
  const org = await owner.call<{ id: string }>('POST', '/organizations', { name: 'Cafe ' + unique() });
  const base = '/organizations/' + org.id;
  const room = await owner.call<{ id: string }>('POST', base + '/locations', { name: 'Stockroom', type: 'ROOM', parentId: null });
  await owner.call('POST', base + '/locations', { name: 'Shelf A', type: 'SHELF', parentId: room.id });
  await owner.call('POST', base + '/locations', { name: 'Shelf B', type: 'SHELF', parentId: room.id });
  const item = await owner.call<{ id: string }>('POST', base + '/items', { name: 'Oat milk', sku: 'OAT-01', unit: 'LITER' });
  const itemUrl = '/app/organizations/' + org.id + '/items/' + item.id;

  await page.goto(itemUrl);
  await expect(page.getByRole('heading', { name: 'Oat milk', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click();
  const receive = page.getByRole('dialog', { name: 'Receive stock' });
  await receive.getByLabel('Location', { exact: true }).selectOption({ label: 'Stockroom › Shelf A' });
  await receive.getByLabel('Quantity (L)', { exact: true }).fill('10');
  await receive.getByLabel('Reference (optional)', { exact: true }).fill('Delivery 118');
  await receive.getByRole('button', { name: 'Receive stock', exact: true }).click();
  await expect(page.getByText('Received 10 L at Stockroom › Shelf A')).toBeVisible();
  await expect(page.getByRole('definition').filter({ hasText: '10 L' })).toBeVisible();

  await page.getByRole('button', { name: 'Transfer', exact: true }).click();
  const transfer = page.getByRole('dialog', { name: 'Transfer stock' });
  await transfer.getByLabel('To', { exact: true }).selectOption({ label: 'Stockroom › Shelf B' });
  await transfer.getByLabel('Quantity (L)', { exact: true }).fill('4');
  await transfer.getByRole('button', { name: 'Transfer stock', exact: true }).click();
  await expect(page.getByText('Moved 4 L from Stockroom › Shelf A to Stockroom › Shelf B')).toBeVisible();

  await page.getByRole('button', { name: 'More actions Stockroom › Shelf A' }).click();
  await page.getByRole('dialog', { name: 'Stockroom › Shelf A' }).getByRole('button', { name: /^Count/ }).click();
  const count = page.getByRole('dialog', { name: 'Count stock' });
  await count.getByLabel('Counted quantity (L)', { exact: true }).fill('5.5');
  await count.getByRole('button', { name: 'Count stock', exact: true }).click();
  await expect(page.getByText('Counted 5.5 L at Stockroom › Shelf A. Adjusted by −0.5 L.')).toBeVisible();

  await page.getByRole('listitem').filter({ hasText: 'Moved out' }).getByRole('button', { name: 'Reverse', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Reverse', exact: true }).click();
  await expect(page.getByText('Reversed the transfer of 4 L')).toBeVisible();
  await expect(page.getByRole('definition').filter({ hasText: '9.5 L' })).toBeVisible();

  const invite = await owner.call<{ invitePath: string }>('POST', base + '/invitations', { role: 'EMPLOYEE' });
  const phone = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const employeePage = await phone.newPage();
  employeePage.on('pageerror', error => errors.push(error.message));
  const employee = await signUp(employeePage, 'Cafe Employee');
  await employee.call('POST', '/invitations/accept', { token: invite.invitePath.split('token=')[1] });

  await employeePage.goto('/app/organizations/' + org.id + '/items');
  await expect(employeePage.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible();
  await expect(employeePage.getByText('9.5 L on hand')).toBeVisible();
  await employeePage.getByRole('link', { name: 'Oat milk', exact: true }).click();
  await expect(employeePage.getByRole('button', { name: 'Transfer', exact: true })).toHaveCount(0);
  // Managers' actions stay out of the employee's More actions sheet.
  await employeePage.getByRole('button', { name: 'More actions Stockroom › Shelf A' }).click();
  const sheet = employeePage.getByRole('dialog', { name: 'Stockroom › Shelf A' });
  await expect(sheet.getByRole('button', { name: /^Count/ })).toHaveCount(0);
  await expect(sheet.getByRole('button', { name: 'Record usage', exact: true })).toBeVisible();
  await employeePage.keyboard.press('Escape');
  await expect(sheet).toHaveCount(0);

  // Record usage straight from the stocked row: the shelf is known, so the quantity is focused.
  await employeePage.getByRole('button', { name: 'Record usage Stockroom › Shelf A' }).click();
  const usage = employeePage.getByRole('dialog', { name: 'Record usage' });
  await expect(usage.getByText('Stockroom › Shelf A', { exact: true })).toBeVisible();
  await expect(usage.getByLabel('Quantity (L)', { exact: true })).toBeFocused();
  await usage.getByLabel('Quantity (L)', { exact: true }).fill('2');
  await usage.getByRole('button', { name: 'Record usage', exact: true }).click();
  await expect(employeePage.getByText('Used 2 L from Stockroom › Shelf A. 7.5 L on hand there.')).toBeVisible();

  await employeePage.getByRole('button', { name: 'Record usage Stockroom › Shelf A' }).click();
  const tooMuch = employeePage.getByRole('dialog', { name: 'Record usage' });
  await tooMuch.getByLabel('Quantity (L)', { exact: true }).fill('100');
  await tooMuch.getByRole('button', { name: 'Record usage', exact: true }).click();
  await expect(tooMuch.getByRole('alert')).toContainText("There isn't enough stock");
  expect(await employeePage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await employeePage.screenshot({ path: test.info().outputPath('employee-usage.png'), fullPage: true });
  expect(errors).toEqual([]);
  await phone.close();
});
