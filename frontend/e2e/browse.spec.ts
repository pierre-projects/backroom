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
/** A real JPEG drawn in the browser, as a photo picker would supply it. */
async function photoFile(page: Page) {
  const base64 = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 1600; canvas.height = 1200;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#0e6b73'; context.fillRect(0, 0, 1600, 1200);
    context.fillStyle = '#e7af48'; context.fillRect(400, 300, 800, 600);
    return canvas.toDataURL('image/jpeg', 0.9).split(',')[1]!;
  });
  return { name: 'syrup.jpg', mimeType: 'image/jpeg', buffer: Buffer.from(base64, 'base64') };
}

test('browse by grid or list, walk spaces, add an item on a shelf, sell it and give it a photo', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const owner = await signUp(page, 'Cafe Owner');
  const org = await owner<{ id: string }>('POST', '/organizations', { name: 'Cafe ' + unique() });
  const base = '/organizations/' + org.id;
  const root = '/app/organizations/' + org.id;
  const room = await owner<{ id: string }>('POST', base + '/locations', { name: 'Stockroom', type: 'ROOM', parentId: null });
  await owner('POST', base + '/locations', { name: 'Shelf A', type: 'SHELF', parentId: room.id });
  await owner('POST', base + '/items', { name: 'House espresso', sku: 'COF-101', unit: 'KILOGRAM' });
  await owner('POST', base + '/items', { name: 'Paper cups', unit: 'EACH' });

  // Desktop starts in list; Grid is one tap and is remembered; filters survive the switch.
  await page.goto(root + '/items?low=1');
  await page.getByLabel('Low stock only').click();
  await expect(page.getByLabel('Low stock only')).not.toBeChecked();
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByRole('button', { name: 'Grid', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Grid', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const grid = page.getByRole('list', { name: 'Items' });
  await expect(grid.getByRole('link', { name: 'House espresso', exact: true })).toBeVisible();
  await page.getByLabel('Search inventory').fill('cof-101');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page).toHaveURL(/q=cof-101/);
  await expect(grid.getByRole('link')).toHaveCount(1);
  await page.reload();
  await expect(page.getByRole('list', { name: 'Items' })).toBeVisible();

  // Returning from an item card restores focus to that card's link.
  await grid.getByRole('link', { name: 'House espresso', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'House espresso', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Back to Inventory', exact: true }).click();
  await expect(page.getByRole('link', { name: 'House espresso', exact: true })).toBeFocused();

  // A phone starts in grid (same session, but none of the desktop's saved layout).
  const cookies = (await page.context().storageState()).cookies;
  const phoneContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, storageState: { cookies, origins: [] } });
  const phone = await phoneContext.newPage();
  phone.on('pageerror', error => errors.push(error.message));
  await phone.goto(root + '/items');
  await expect(phone.getByRole('list', { name: 'Items' })).toBeVisible();
  await expect(phone.getByText('0 kg on hand')).toBeVisible();
  expect(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();

  // Walk the spaces with the breadcrumb trail.
  await page.goto(root + '/locations');
  await page.getByRole('link', { name: 'Stockroom', exact: true }).click();
  const trail = page.getByRole('navigation', { name: 'Breadcrumb' });
  await expect(trail.getByRole('link', { name: 'All locations', exact: true })).toBeVisible();
  await expect(trail.getByText('Stockroom', { exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('link', { name: 'Shelf A', exact: true }).click();
  await expect(trail.getByRole('link', { name: 'Stockroom', exact: true })).toBeVisible();
  await expect(page.getByText('No storage inside Shelf A.')).toBeVisible();

  // Add item here: only name and unit are asked; it lands ready to receive onto this shelf.
  await page.getByRole('button', { name: 'Add item here', exact: true }).click();
  const add = page.getByRole('dialog', { name: 'Add item to Shelf A' });
  await expect(add.getByLabel('SKU (optional)', { exact: true })).toBeHidden();
  await add.getByLabel('Item name', { exact: true }).fill('Vanilla syrup');
  await add.getByLabel('Base unit', { exact: true }).selectOption('LITER');
  await add.getByRole('button', { name: 'Create item', exact: true }).click();
  const receive = page.getByRole('dialog', { name: 'Receive stock' });
  await expect(receive.getByLabel('Location', { exact: true })).toHaveValue(/.+/);
  await expect(receive.getByLabel('Location', { exact: true }).locator('option:checked')).toHaveText('Stockroom › Shelf A');
  await receive.getByLabel('Quantity (L)', { exact: true }).fill('4');
  await receive.getByRole('button', { name: 'Receive stock', exact: true }).click();
  await expect(page.getByText('Received 4 L at Stockroom › Shelf A')).toBeVisible();

  // Record a sale from More actions; history says Sold.
  await page.getByRole('button', { name: 'More actions Stockroom › Shelf A' }).click();
  await page.getByRole('dialog', { name: 'Stockroom › Shelf A' }).getByRole('button', { name: /^Record sale/ }).click();
  const sale = page.getByRole('dialog', { name: 'Record sale' });
  await sale.getByLabel('Quantity (L)', { exact: true }).fill('1');
  await sale.getByRole('button', { name: 'Record sale', exact: true }).click();
  await expect(page.getByText('Sold 1 L from Stockroom › Shelf A. 3 L on hand there.')).toBeVisible();
  await expect(page.getByRole('region', { name: 'History' }).getByText('Sold', { exact: true })).toBeVisible();

  // A photo, resized in the browser, shows in the hero and on the Inventory card.
  await page.getByLabel('Add photo').setInputFiles(await photoFile(page));
  await expect(page.getByText('Photo added')).toBeVisible();
  const hero = page.locator('.page-heading-media img');
  await expect(hero).toBeVisible();
  expect(await hero.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(1024);
  await page.goto(root + '/items');
  await expect(page.getByRole('list', { name: 'Items' }).locator('img')).toHaveCount(1);

  expect(errors).toEqual([]);
  await phoneContext.close();
});
