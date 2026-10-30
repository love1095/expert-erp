import { test, expect } from '../../fixtures/test';
import { addUser, apiFor, uid } from '../../fixtures/api';

// Real Shopify credentials never appear in tests. Successful connections are covered by the backend
// test against a local fake Shopify (ShopifyConnectionServiceTest).

test.describe('Shopify connection', () => {
  test('SHP-001 Shopify is reached from the Integrations page; a store address that is not *.myshopify.com is refused', { tag: ['@P1', '@shopify', '@integrations'] }, async ({ page }) => {
    await page.goto('/');
    const menu = page.getByRole('navigation', { name: 'Main' });
    if (await menu.getByRole('button', { name: 'Settings' }).getAttribute('aria-expanded') !== 'true') {
      await menu.getByRole('button', { name: 'Settings' }).click();
    }
    await menu.getByRole('link', { name: 'Integrations' }).click();
    const card = page.getByTestId('integration-shopify');
    await expect(card).toContainText('Not connected');
    await card.click();
    await expect(page).toHaveURL(/\/settings\/integrations\/shopify$/);
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Integrations');

    await page.getByLabel('Store address *').fill('https://evil.example.com');
    await page.getByLabel('Client ID *').fill('client');
    await page.getByLabel('Client secret *').fill('secret');
    await page.getByRole('button', { name: 'Save & test connection' }).click();

    await expect(page.getByTestId('shopify-error')).toContainText('your-store.myshopify.com');
    await expect(page.getByTestId('shopify-status')).toHaveCount(0);
  });

  test('SHP-002 an unreachable store is saved as "Not working" with a reason; the secret is never sent back', { tag: ['@P1', '@shopify', '@integrations', '@external'] },
    async ({ page, api }) => {
      const shop = `e2e-missing-${uid()}`;
      await page.goto('/settings/integrations/shopify');
      await page.getByLabel('Store address *').fill(shop);
      await page.getByLabel('Client ID *').fill('e2e-client');
      await page.getByLabel('Client secret *').fill('e2e-very-secret');
      await page.getByRole('button', { name: 'Save & test connection' }).click();

      const status = page.getByTestId('shopify-status');
      // Saving asks the real Shopify about the store: allow for a slow network
      await expect(status).toContainText(`${shop}.myshopify.com`, { timeout: 30_000 });
      await expect(status).toContainText('Not working');
      await expect(page.getByTestId('shopify-last-error')).toContainText(/No Shopify store answered|Could not reach/);
      await expect(page.getByTestId('shopify-test-result')).toContainText('Connection test failed');

      // Testing again gives visible feedback, even when the outcome is unchanged
      await page.getByRole('button', { name: 'Test connection' }).click();
      await expect(page.getByTestId('shopify-test-result')).toContainText(/Connection test failed at/, { timeout: 30_000 });

      const res = await api.http.get('/api/integrations/shopify');
      const body = await res.text();
      expect(res.status()).toBe(200);
      expect(body).not.toContain('e2e-very-secret');
      expect(JSON.parse(body)).toMatchObject({ clientId: 'e2e-client', secretSaved: true, status: 'ERROR' });

      // The Integrations overview shows the same state
      await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Integrations' }).click();
      await expect(page.getByTestId('integration-shopify')).toContainText('Not working');
      await expect(page.getByTestId('integration-shopify')).toContainText(`${shop}.myshopify.com`);
      await page.getByTestId('integration-shopify').click();

      // Changing credentials keeps the saved secret when the field is left empty
      await page.getByRole('button', { name: 'Change credentials' }).click();
      await expect(page.getByLabel('Client secret')).toHaveAttribute('placeholder', /leave empty to keep/);

      await page.getByRole('button', { name: 'Disconnect' }).click();
      await expect(page.getByRole('button', { name: 'Save & test connection' })).toBeVisible();
      expect((await api.http.get('/api/integrations/shopify')).status()).toBe(204);
    });

  test('SHP-003 only an ADMIN can see or change the Shopify connection', { tag: ['@P0', '@shopify', '@integrations', '@security'] },
    async ({ api, baseURL }) => {
      for (const role of ['MANAGER', 'STAFF'] as const) {
        const user = await apiFor(baseURL!, await addUser(api, role));
        try {
          expect((await user.http.get('/api/integrations/shopify')).status(), role).toBe(403);
          expect((await user.http.put('/api/integrations/shopify',
            { data: { shopDomain: 'x.myshopify.com', clientId: 'a', clientSecret: 'b' } })).status(), role).toBe(403);
          expect((await user.http.delete('/api/integrations/shopify')).status(), role).toBe(403);
        } finally {
          await user.dispose();
        }
      }
    });
});
