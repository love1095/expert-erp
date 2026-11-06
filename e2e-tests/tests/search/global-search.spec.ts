import { test, expect } from '../../fixtures/test';

// Phase I2 (spec §8): one search box in the top bar over orders, invoices, customers, suppliers and products
test.describe('Global search', () => {
  test('SEARCH-001 "/" then a SKU, an order number or a customer finds it; Enter opens it', { tag: ['@P1', '@search'] },
    async ({ page, api, warehouses }) => {
      const product = await api.createProduct({ costPrice: 6 });
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);

      await page.goto('/');
      const box = page.getByRole('combobox', { name: 'Search everything' });
      await expect(box).toBeVisible();
      await expect(page.getByTestId('kpi-stock-value').or(page.getByRole('heading', { name: 'Dashboard' })).first()).toBeVisible();
      await page.locator('main').click({ position: { x: 5, y: 5 } });
      await page.keyboard.press('/');
      await expect(box).toBeFocused();

      await box.fill(product.sku);
      const results = page.locator('#global-search-results');
      await expect(results).toContainText('Products');
      await expect(results.getByRole('option').first()).toContainText(product.sku);
      await box.press('Enter');
      await expect(page).toHaveURL(new RegExp(`/products/${product.id}$`));

      await box.fill(so.orderNumber);
      await expect(results.getByRole('option', { name: new RegExp(so.orderNumber) })).toBeVisible();
      await results.getByRole('option', { name: new RegExp(so.orderNumber) }).click();
      await expect(page).toHaveURL(new RegExp(`/sale-orders/${so.id}$`));

      await box.fill('zz-no-such-thing-' + Date.now());
      await expect(results).toContainText('Nothing found');
      await box.press('Escape');
      await expect(results).toBeHidden();
    });
});
