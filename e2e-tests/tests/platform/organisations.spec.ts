import { test, expect, logInAs } from '../../fixtures/test';
import { anonymousApi, SUPER_ADMIN } from '../../fixtures/api';

// The platform's super admin: in no organisation, no subscription; gives each organisation its plan
test.describe('Platform', () => {
  test.use({ plan: 'FREE', loggedIn: false });

  test('PLAT-001 the super admin gives an organisation Enterprise; its admin sees it but cannot change it', { tag: ['@P0', '@platform'] },
    async ({ page, session, browser, baseURL }) => {
      const org = session.user;

      await page.goto('/login');
      await page.getByLabel('Username').fill(SUPER_ADMIN.username);
      await page.getByLabel('Password').fill(SUPER_ADMIN.password);
      await page.getByRole('button', { name: 'Sign in' }).click();
      await expect(page).toHaveURL(/\/platform\/organisations/);
      await expect(page.getByRole('heading', { name: 'Organisations' })).toBeVisible();
      // No organisation, so no business modules and no plan badge
      await expect(page.getByRole('link', { name: 'Sale Orders' })).toHaveCount(0);
      await expect(page.getByText('Expert ERP platform')).toBeVisible();
      // Business pages lead back to the platform
      await page.goto('/sale-orders');
      await expect(page).toHaveURL(/\/platform\/organisations/);

      await page.getByLabel('Search organisations').fill(org.tenantName);
      const row = page.getByTestId(`organisation-${org.tenantSlug}`);
      await expect(row).toContainText(org.tenantName);
      const plan = row.getByLabel(`Plan of ${org.tenantName}`);
      await expect(plan).toHaveValue('FREE');
      const saved = page.waitForResponse(r => r.url().includes(`/api/tenants/${org.tenantId}/subscription`) && r.status() === 200);
      await plan.selectOption('ENTERPRISE');
      await saved;
      await page.reload();
      await page.getByLabel('Search organisations').fill(org.tenantName);
      await expect(page.getByTestId(`organisation-${org.tenantSlug}`).getByLabel(`Plan of ${org.tenantName}`)).toHaveValue('ENTERPRISE');

      // The organisation's admin: Enterprise, no way to change it
      const ctx = await browser.newContext();
      const admin = await ctx.newPage();
      await logInAs(admin, session);
      await admin.goto('/settings/subscription');
      await expect(admin.getByText('ENTERPRISE').first()).toBeVisible();
      await expect(admin.getByTestId('plan-change-note')).toContainText('contact Expert ERP');
      await expect(admin.getByRole('button', { name: /Switch to/ })).toHaveCount(0);
      await ctx.close();

      // Switched off: its people cannot log in; switched on again: they can
      const anon = await anonymousApi(baseURL!);
      const login = () => anon.post('/api/auth/login', { data: { username: org.username, password: session.password } });
      await page.getByLabel('Search organisations').fill(org.tenantName);
      await page.getByTestId(`organisation-${org.tenantSlug}`).getByRole('button', { name: 'Switch off' }).click();
      await expect(page.getByTestId(`organisation-${org.tenantSlug}`).getByRole('button', { name: 'Switch on' })).toBeVisible();
      expect((await login()).status()).toBe(403);
      await page.getByTestId(`organisation-${org.tenantSlug}`).getByRole('button', { name: 'Switch on' }).click();
      await expect(page.getByTestId(`organisation-${org.tenantSlug}`).getByRole('button', { name: 'Switch off' })).toBeVisible();
      expect((await login()).status()).toBe(200);
      await anon.dispose();
    });
});
