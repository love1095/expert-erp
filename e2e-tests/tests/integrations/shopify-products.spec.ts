import { test, expect } from '../../fixtures/test';
import { connectFakeShop } from '../../fixtures/shopify';

// Sending products to a real Shopify is covered by the backend test against a local fake Shopify
// (ShopifyProductSyncTest); here the store does not exist, so only the guard rails are visible.

test.describe('Shopify products', () => {
  test('SHP-010 product sync is offered on the Shopify page but refused until the connection works', { tag: ['@P2', '@shopify', '@integrations', '@external'] },
    async ({ page, api }) => {
      await connectFakeShop(api);

      await page.goto('/settings/integrations/shopify');
      const section = page.getByTestId('shopify-products');
      await expect(section).toContainText('Products are matched by SKU');
      await expect(section.getByRole('button', { name: 'Sync products to Shopify' })).toBeDisabled();

      const res = await api.http.post('/api/integrations/shopify/products/sync');
      expect(res.status()).toBe(400);
      expect(await res.text()).toContain('Click Test connection first');
      // Nothing ran
      expect((await api.http.get('/api/integrations/shopify/products/sync')).status()).toBe(204);
    });

  test('SHP-011 the Shopify order import is on the Sale Orders page, only once the store connection works', { tag: ['@P2', '@shopify', '@integrations', '@external'] },
    async ({ page, api }) => {
      await connectFakeShop(api);

      await page.goto('/settings/integrations/shopify');
      await page.getByRole('link', { name: 'Sale Orders → Import from Shopify' }).click();
      await expect(page).toHaveURL(/\/sale-orders$/);
      await expect(page.getByRole('heading', { name: 'Sale Orders' })).toBeVisible();
      // The fake store does not answer, so the connection is "Not working": no import button
      await expect(page.getByRole('button', { name: '+ New SO' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Import from Shopify' })).toHaveCount(0);
    });
});
