import { test, expect } from '@playwright/test';
import type { Page, APIRequestContext } from '@playwright/test';

const password = 'Backroom browser test 42!';
const unique = () => crypto.randomUUID().slice(0, 12);
async function register(page: Page, name: string) {
  const email = unique() + '@example.test';
  await page.getByLabel('Your name', { exact: true }).fill(name);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  return email;
}
async function createOrganization(page: Page, name: string) {
  const form = page.getByLabel('Business name', { exact: true });
  const newButton = page.getByRole('button', { name: 'New organization', exact: true });
  await expect(form.or(newButton)).toBeVisible();
  if (!await form.isVisible()) {
    await page.getByRole('button', { name: 'New organization', exact: true }).click();
  }
  await page.getByLabel('Business name', { exact: true }).fill(name);
  await page.getByRole('button', { name: 'Create organization', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Set up ' + name, exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Inventory', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible();
}
async function csrf(request: APIRequestContext) {
  const response = await request.get('/api/v1/auth/csrf');
  expect(response.ok()).toBeTruthy();
  return await response.json() as { token: string; headerName: string };
}

test('owner onboarding, placements, invitation acceptance and employee restrictions', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/register');
  await register(page, 'Cafe Owner');
  await expect(page.getByRole('heading', { name: 'Your businesses', exact: true })).toBeVisible();
  const business = 'Cafe ' + unique();
  await createOrganization(page, business);
  const orgId = /organizations\/([^/]+)/.exec(page.url())![1];

  await page.getByRole('link', { name: /Locations/ }).click();
  await page.getByRole('button', { name: 'Add location', exact: true }).click();
  await page.getByLabel('Location name', { exact: true }).fill('Stockroom');
  await page.getByRole('button', { name: 'Create location', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Stockroom', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Add child location', exact: true }).click();
  await page.getByLabel('Location name', { exact: true }).fill('Shelf A');
  await page.getByRole('button', { name: 'Create location', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shelf A', exact: true })).toBeVisible();

  await page.getByRole('link', { name: 'Inventory', exact: true }).click();
  await page.getByRole('button', { name: 'Add item', exact: true }).click();
  await page.getByLabel('Item name', { exact: true }).fill('Coffee beans');
  // Creating asks for name and unit; SKU waits under Optional details.
  await expect(page.getByLabel('SKU (optional)', { exact: true })).toBeHidden();
  await page.getByText('Optional details', { exact: true }).click();
  await page.getByLabel('SKU (optional)', { exact: true }).fill(' beans-01 ');
  await page.getByLabel('Base unit', { exact: true }).selectOption('KILOGRAM');
  await page.getByRole('button', { name: 'Create item', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Add item' })).toHaveCount(0);
  await expect(page).toHaveURL(/\/items\/[0-9a-f-]{36}/);
  await expect(page.getByRole('heading', { name: 'Coffee beans', exact: true })).toBeVisible();
  await expect(page.getByText('BEANS-01', { exact: true })).toBeVisible();
  const itemUrl = page.url();
  await page.getByRole('button', { name: 'Add an expected location', exact: true }).click();
  await page.getByLabel('Storage location', { exact: true }).selectOption({ label: 'Stockroom › Shelf A' });
  await page.getByRole('button', { name: 'Add placement', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Shelf A', exact: true })).toBeVisible();

  await page.getByRole('link', { name: /People/ }).click();
  await page.getByRole('button', { name: 'Invite staff', exact: true }).click();
  await page.getByRole('button', { name: 'Create invitation', exact: true }).click();
  const invitation = await page.getByLabel('Invitation link', { exact: true }).inputValue();
  expect(invitation).toContain('/invite#token=');
  await page.getByRole('button', { name: 'Close dialog', exact: true }).click();

  const employeeContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const employee = await employeeContext.newPage();
  employee.on('pageerror', error => errors.push(error.message));
  await employee.goto(invitation);
  await employee.getByRole('button', { name: 'Create an account', exact: true }).click();
  const employeeEmail = await register(employee, 'Cafe Employee');
  await expect(employee.getByRole('heading', { name: business, exact: true })).toBeVisible();
  await employee.getByRole('button', { name: 'Accept invitation', exact: true }).click();
  await expect(employee.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await expect(employee.getByRole('heading', { name: /^Set up/ })).toHaveCount(0);
  await employee.getByRole('link', { name: 'Inventory', exact: true }).click();
  await expect(employee.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible();
  await expect(employee.getByRole('button', { name: 'Add item', exact: true })).toHaveCount(0);
  await expect(employee.getByRole('link', { name: /People/ })).toHaveCount(0);
  await employee.getByRole('link', { name: 'Coffee beans', exact: true }).click();
  await expect(employee.getByRole('link', { name: 'Shelf A', exact: true })).toBeVisible();
  await expect(employee.getByRole('button', { name: 'Edit item', exact: true })).toHaveCount(0);

  const employeeCsrf = await csrf(employeeContext.request);
  const denied = await employeeContext.request.post('/api/v1/organizations/' + orgId + '/items', {
    headers: { [employeeCsrf.headerName]: employeeCsrf.token }, data: { name: 'Forbidden item', unit: 'EACH' },
  });
  expect(denied.status()).toBe(403);
  expect((await denied.json()).errorCode).toBe('FORBIDDEN');
  await employee.reload();
  await expect(employee.getByRole('heading', { name: 'Coffee beans', exact: true })).toBeVisible();
  expect(await employee.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await employee.screenshot({ path: test.info().outputPath('employee-mobile.png'), fullPage: true });

  await page.reload();
  const employeeRow = page.getByRole('row').filter({ hasText: employeeEmail });
  await expect(employeeRow).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('owner-people.png'), fullPage: true });
  await employeeRow.getByRole('button', { name: 'Remove access', exact: true }).click();
  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(employeeRow.getByText('Inactive', { exact: true })).toBeVisible();
  await employee.goto(itemUrl);
  await expect(employee.getByRole('alert')).toContainText('unavailable');

  await page.getByRole('link', { name: /Activity/ }).click();
  await expect(page.getByText('membership deactivated', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
  await employeeContext.close();
});

test('organization switching, metadata editing, archival and expired sessions', async ({ page, context }) => {
  await page.goto('/register');
  await register(page, 'Retail Owner');
  await expect(page.getByRole('heading', { name: 'Your businesses', exact: true })).toBeVisible();
  const first = 'Shop ' + unique();
  const second = 'Studio ' + unique();
  await createOrganization(page, first);
  await page.getByRole('button', { name: 'Add item', exact: true }).click();
  await page.getByLabel('Item name', { exact: true }).fill('Display bottles');
  await page.getByLabel('Base unit', { exact: true }).selectOption('EACH');
  await page.getByRole('button', { name: 'Create item', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Display bottles', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Edit item', exact: true }).click();
  await page.getByLabel('Item name', { exact: true }).fill('Bottle display');
  await page.getByRole('button', { name: 'Save item', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Bottle display', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Bottle display', exact: true })).toBeVisible();

  await page.getByRole('link', { name: /Switch business/ }).click();
  await createOrganization(page, second);
  await expect(page.getByRole('link', { name: 'Bottle display', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No items yet', exact: true })).toBeVisible();
  await page.getByRole('link', { name: /Switch business/ }).click();
  await page.getByRole('link').filter({ has: page.getByRole('heading', { name: first, exact: true }) }).click();
  await expect(page.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Inventory', exact: true }).click();
  await page.getByRole('link', { name: 'Bottle display', exact: true }).click();
  await page.getByRole('button', { name: 'Archive item', exact: true }).click();
  await page.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No items yet', exact: true })).toBeVisible();
  // Controlled URL-backed checkbox: Playwright's check() can race the React/searchParams update.
  const includeArchived = page.getByLabel('Include archived', { exact: true });
  await includeArchived.click();
  await expect(includeArchived).toBeChecked();
  await expect(page).toHaveURL(/[?&]archived=1(?:&|$)/);
  await expect(page.getByRole('link', { name: 'Bottle display', exact: true })).toBeVisible();

  const token = await csrf(context.request);
  const signedOut = await context.request.post('/api/v1/auth/logout', { headers: { [token.headerName]: token.token } });
  expect(signedOut.status()).toBe(204);
  await page.getByRole('link', { name: /Locations/ }).click();
  await expect(page).toHaveURL(/\/signin(\?|$)/);
  await expect(page.getByRole('heading', { name: 'Sign in to Backroom', exact: true })).toBeVisible();
});
