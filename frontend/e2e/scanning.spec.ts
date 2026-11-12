import { test, expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { writeQrVideo } from './fake-camera';

const password = 'Backroom browser test 42!';
const unique = () => crypto.randomUUID().slice(0, 12);
// The fake camera shows a QR code holding this product barcode; the test links it to an item first.
const cameraBarcode = '036000291452';
const videoDir = join(process.cwd(), 'test-results', 'fake-camera');
mkdirSync(videoDir, { recursive: true });
const video = join(videoDir, 'qr.y4m');
await writeQrVideo(video, cameraBarcode);

test.use({
  launchOptions: { args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--use-file-for-fake-video-capture=' + video] },
  permissions: ['camera'],
});

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
  return { email, call };
}

test('labels open items through sign-in, manual lookup and the camera', async ({ page, browser, baseURL }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const owner = await signUp(page, 'Cafe Owner');
  const org = await owner.call<{ id: string }>('POST', '/organizations', { name: 'Cafe ' + unique() });
  const base = '/organizations/' + org.id;
  const room = await owner.call<{ id: string }>('POST', base + '/locations', { name: 'Stockroom', type: 'ROOM', parentId: null });
  const shelf = await owner.call<{ id: string }>('POST', base + '/locations', { name: 'Shelf A', type: 'SHELF', parentId: room.id });
  const beans = await owner.call<{ id: string }>('POST', base + '/items', { name: 'Espresso beans', sku: 'BEAN-01', unit: 'KILOGRAM' });
  const syrup = await owner.call<{ id: string }>('POST', base + '/items', { name: 'Vanilla syrup', unit: 'MILLILITER' });
  const qr = await owner.call<{ display: string; url: string }>('POST', base + '/codes', { type: 'LOCATION', id: shelf.id });
  const token = qr.display.replaceAll('-', '');

  // A signed-out phone opens the printed link, signs in, and lands on the location.
  const visitor = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 } });
  const phone = await visitor.newPage();
  phone.on('pageerror', error => errors.push(error.message));
  await phone.goto('/s/' + token);
  await expect(phone).toHaveURL(/\/signin\?next=/);
  await phone.getByLabel('Email', { exact: true }).fill(owner.email);
  await phone.getByLabel('Password', { exact: true }).fill(password);
  await phone.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(phone.getByRole('heading', { name: 'Shelf A', exact: true })).toBeVisible();
  await expect(phone.getByRole('heading', { name: 'Labels and barcodes' })).toBeVisible();
  await visitor.close();

  // Manual lookup by the printed code, then a product barcode that isn't linked yet.
  await page.goto('/app/organizations/' + org.id + '/scan');
  await expect(page.getByRole('heading', { name: 'Scan', exact: true })).toBeVisible();
  const entry = page.getByLabel('Type a code or barcode', { exact: true });
  await entry.fill(qr.display.toLowerCase());
  await page.getByRole('button', { name: 'Look up', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shelf A', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open location', exact: true })).toBeVisible();

  await entry.fill('4006381333931');
  await page.getByRole('button', { name: 'Look up', exact: true }).click();
  await expect(page.getByRole('heading', { name: /isn’t linked yet/ })).toBeVisible();
  await page.getByRole('button', { name: 'Link to an item', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Link to Vanilla syrup' }).click();
  await expect(page.getByRole('heading', { name: 'Vanilla syrup', exact: true })).toBeVisible();

  await entry.fill('espresso');
  await page.getByRole('button', { name: 'Look up', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Espresso beans', exact: true })).toBeVisible();

  // The camera (Chromium's fake device showing a QR code) finds the barcode and pauses on the result.
  await owner.call('POST', base + '/items/' + beans.id + '/barcodes', { value: cameraBarcode });
  await page.getByRole('button', { name: 'Scan again', exact: true }).click();
  await page.getByRole('button', { name: 'Start camera', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Espresso beans', exact: true })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Record usage', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Record usage' })).toBeVisible();
  await expect(page).toHaveURL(new RegExp('/items/' + beans.id + '$'));
  expect(syrup.id).toBeTruthy();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/app/organizations/' + org.id + '/scan');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  expect(errors).toEqual([]);
});
