import { test, expect } from '../../fixtures/test';

test.describe('Authentication', () => {
  test.use({ loggedIn: false });

  test('AUTH-001 valid credentials open the dashboard', { tag: ['@P0', '@auth'] }, async ({ page, session }) => {
    await page.goto('/login');
    await page.getByLabel('Username').fill(session.user.username);
    await page.getByLabel('Password').fill(session.password);
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });

  test('AUTH-002 wrong password shows an error and stays on login', { tag: ['@P1', '@auth'] }, async ({ page, session }) => {
    await page.goto('/login');
    await page.getByLabel('Username').fill(session.user.username);
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByText('Invalid username or password')).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('AUTH-003 invalid or expired token redirects to login', { tag: ['@P0', '@auth'] }, async ({ page, session }) => {
    await page.addInitScript(user => {
      localStorage.setItem('erp_token', 'not-a-valid-jwt');
      localStorage.setItem('erp_user', user);
    }, JSON.stringify(session.user));

    await page.goto('/products');

    await expect(page).toHaveURL(/\/login$/);
  });

  test('AUTH-004 protected pages redirect to login when signed out', { tag: ['@P1', '@auth'] }, async ({ page }) => {
    await page.goto('/sale-orders');
    await expect(page).toHaveURL(/\/login$/);
  });
});

test('AUTH-005 sign out returns to login', { tag: ['@P1', '@auth'] }, async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();

  await expect(page).toHaveURL(/\/login$/);
});
