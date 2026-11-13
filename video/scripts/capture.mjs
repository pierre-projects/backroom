import { chromium } from '../../frontend/node_modules/playwright/index.mjs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { mkdir, mkdtemp, rm, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';

const baseURL = process.env.BACKROOM_CAPTURE_URL || 'http://127.0.0.1:5173';
const output = fileURLToPath(new URL('../public/', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'backroom-label-'));
const browser = await chromium.launch({ executablePath: process.env.CAPTURE_BROWSER });
try {
  const page = await browser.newPage({
    baseURL, viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2,
    locale: 'en-US', reducedMotion: 'reduce',
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const request = page.request;
  async function post(path, data, form = false) {
    const csrf = await (await request.get('/api/v1/auth/csrf')).json();
    const response = await request.post('/api/v1' + path, {
      headers: { [csrf.headerName]: csrf.token }, ...(form ? { form: data } : { data }),
    });
    if (!response.ok()) throw new Error(path + ' ' + response.status() + ' ' + await response.text());
    const body = await response.text();
    return body ? JSON.parse(body) : null;
  }
  const email = 'studio-' + randomUUID().slice(0, 8) + '@example.test';
  const password = 'Studio-' + randomUUID();
  await post('/auth/register', { email, password, displayName: 'Avery Chen' });
  await post('/auth/login', { email, password }, true);
  const org = await post('/organizations', { name: 'Juniper Coffee' });
  const api = '/organizations/' + org.id;
  const create = (path, data) => post(api + path, data);
  const root = '/app' + api;
  const location = (name, type, parentId = null) => create('/locations', { name, type, parentId });
  const room = await location('Stockroom', 'ROOM');
  const bar = await location('Coffee bar', 'ROOM');
  const shelf = await location('Coffee shelf', 'SHELF', room.id);
  const tools = await location('Equipment shelf', 'SHELF', room.id);
  const paper = await location('Paper goods', 'SHELF', room.id);
  const counter = await location('Service shelf', 'SHELF', bar.id);
  async function item(name, sku, unit, stocks) {
    const record = await create('/items', { name, sku, unit });
    for (const [place, quantity] of stocks) {
      await create('/inventory/receipts', { itemId: record.id, locationId: place.id, quantity, operationKey: randomUUID() });
    }
    return record;
  }
  const scale = await item('Brew scale', 'EQ-014', 'EACH', [[tools, 2]]);
  const espresso = await item('House espresso', 'COF-101', 'KILOGRAM', [[shelf, 12], [counter, 2]]);
  await item('Decaf espresso', 'COF-102', 'KILOGRAM', [[shelf, 4]]);
  await item('Filter papers', 'PPR-201', 'EACH', [[paper, 240]]);
  await item('Glass carafe', 'EQ-018', 'EACH', [[tools, 3]]);
  await item('Paper cups', 'PPR-202', 'EACH', [[paper, 360]]);
  const barcode = '4006381333931';
  await create('/items/' + espresso.id + '/barcodes', { value: barcode });
  const go = suffix => page.goto(root + '/' + suffix);
  const shot = async (name, locator) => {
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => document.fonts.ready);
    await page.mouse.move(0, 0);
    const opts = { path: join(output, name + '.png'), animations: 'disabled' };
    if (locator) await locator.screenshot(opts);
    else await page.screenshot(opts);
    console.log('Captured ' + name);
  };
  await mkdir(output, { recursive: true });
  await copyFile(new URL('../../frontend/public/favicon.svg', import.meta.url), join(output, 'brand.svg'));
  await go('items');
  await page.getByRole('link', { name: 'House espresso', exact: true }).waitFor();
  await shot('inventory');
  await page.getByRole('link', { name: 'House espresso', exact: true }).click();
  await page.getByRole('link', { name: 'Coffee shelf', exact: true }).waitFor();
  await shot('espresso');
  await go('locations/' + room.id);
  await page.getByRole('link', { name: 'Coffee shelf', exact: true }).waitFor();
  await shot('room');
  await page.getByRole('link', { name: 'Coffee shelf', exact: true }).click();
  const contents = page.getByRole('region', { name: 'Expected items' });
  await contents.getByRole('link', { name: 'House espresso', exact: true }).waitFor();
  await shot('shelf', contents);

  await page.getByRole('link', { name: 'Create label', exact: true }).click();
  await page.getByText('One label per page', { exact: true }).click();
  await shot('layout', page.locator('fieldset').filter({ hasText: 'One label per page' }));
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download PDF', exact: true }).click();
  const pdfPath = join(temp, 'label.pdf');
  await (await downloaded).saveAs(pdfPath);
  execFileSync('pdftoppm', ['-r', '380', '-png', '-singlefile', pdfPath, join(output, 'label')]);
  await go('scan');
  await page.getByLabel('Type a code or barcode').fill(barcode);
  await shot('lookup', page.locator('.manual-entry'));
  await page.getByRole('button', { name: 'Look up', exact: true }).click();
  await page.getByRole('heading', { name: 'House espresso' }).waitFor();
  await shot('found', page.locator('.scan-result'));
  await go('items/' + scale.id);
  await page.getByRole('button', { name: 'Transfer', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Transfer stock' });
  await dialog.getByLabel('To', { exact: true }).selectOption(counter.id);
  await dialog.getByLabel(/^Quantity/).fill('1');
  await shot('transfer', dialog);
  await dialog.getByRole('button', { name: 'Transfer stock' }).click();
  await page.getByText(/Moved 1 from/).waitFor();
  await shot('equipment');
  if (errors.length) throw new Error(errors.join('\n'));
} finally {
  await browser.close();
  await rm(temp, { recursive: true, force: true });
}
