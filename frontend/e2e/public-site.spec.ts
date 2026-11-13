import { expect, test } from '@playwright/test';

const pages = [
  { path: '/', heading: 'Know what you stock, where it’s kept and who last touched it.' },
  { path: '/terms', heading: 'Terms of Service' },
  { path: '/privacy', heading: 'Privacy Policy' },
  { path: '/security', heading: 'Security' },
  { path: '/contact', heading: 'Contact' },
];

test('public pages render with the shared header and footer', async ({ page }) => {
  for (const { path, heading } of pages) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1, name: heading, exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Get started', exact: true }).first()).toHaveAttribute('href', '/register');
    const legal = page.getByRole('navigation', { name: 'Legal' });
    for (const name of ['Terms', 'Privacy', 'Security', 'Contact']) await expect(legal.getByRole('link', { name, exact: true })).toBeVisible();
  }
});

test('footer links move between the legal pages', async ({ page }) => {
  await page.goto('/');
  const legal = page.getByRole('navigation', { name: 'Legal' });
  await legal.getByRole('link', { name: 'Privacy', exact: true }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page).toHaveTitle('Privacy Policy · Backroom');
  await page.getByRole('navigation', { name: 'Legal' }).getByRole('link', { name: 'Terms', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Terms of Service' })).toBeVisible();
  await page.getByRole('banner').getByRole('link', { name: 'Backroom home' }).click();
  await expect(page).toHaveURL(/\/$/);
});

test('the footer links every public page and jumps to landing sections', async ({ page }) => {
  await page.goto('/privacy');
  const footer = page.getByRole('contentinfo');
  for (const column of ['Product', 'Who it’s for', 'Company']) await expect(footer.getByRole('navigation', { name: column })).toBeVisible();
  await footer.getByRole('navigation', { name: 'Product' }).getByRole('link', { name: 'Inventory features' }).click();
  await expect(page).toHaveURL(/\/#features$/);
  await expect(page.getByRole('heading', { name: 'What’s in it' })).toBeInViewport();
  await page.getByRole('contentinfo').getByRole('navigation', { name: 'Company' }).getByRole('link', { name: 'Security' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Security' })).toBeVisible();
});

test('the header navigation jumps to landing sections from any public page', async ({ page }) => {
  await page.goto('/terms');
  const nav = page.getByRole('banner').getByRole('navigation', { name: 'Site' });
  await nav.getByRole('link', { name: 'How it works' }).click();
  await expect(page).toHaveURL(/\/#how-it-works$/);
  await expect(page.getByRole('heading', { name: 'Label every shelf, bin and item.' })).toBeInViewport();
  await nav.getByRole('link', { name: 'Questions' }).click();
  await expect(page.getByRole('heading', { name: 'Questions', exact: true })).toBeInViewport();
});

test('the legal documents link their sections and the landing FAQ opens', async ({ page }) => {
  await page.goto('/privacy');
  await page.getByRole('navigation', { name: 'Contents' }).getByRole('link', { name: 'Camera', exact: true }).click();
  await expect(page).toHaveURL(/#section-6$/);
  await expect(page.getByRole('heading', { name: '6. Camera' })).toBeInViewport();
  await page.goto('/');
  await page.getByText('What if someone forgets their password?').click();
  await expect(page.getByText('Backroom does not send email')).toBeVisible();
});

test('registration links the terms and privacy policy, and signed-in visitors reach their businesses', async ({ page }) => {
  await page.goto('/register');
  const consent = page.getByText('By creating an account you agree to');
  await expect(consent.getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
  await expect(consent.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Legal' }).getByRole('link', { name: 'Privacy' })).toBeVisible();

  await page.getByLabel('Your name').fill('Site Visitor');
  await page.getByLabel('Email').fill(`site-${Date.now()}@example.test`);
  await page.getByLabel('Password').fill('correct horse battery');
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your businesses', exact: true })).toBeVisible();

  await page.goto('/terms');
  await expect(page.getByRole('link', { name: 'Get started', exact: true })).toHaveCount(0);
  await page.getByRole('banner').getByRole('link', { name: 'Your businesses', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your businesses', exact: true })).toBeVisible();
});
