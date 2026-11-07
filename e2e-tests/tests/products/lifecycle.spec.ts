import { test, expect } from '../../fixtures/test';

// Phase P6: a discontinued product sells only what is left
test.describe('Product lifecycle', () => {
  test('LIFE-001 a discontinued product shows as such and an order for more than is left cannot be confirmed',
    { tag: ['@P1', '@products'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      const full = await (await api.http.get(`/api/products/${product.id}`)).json();
      expect((await api.http.put(`/api/products/${product.id}`, { data: { ...full, status: 'DISCONTINUED' } })).ok()).toBeTruthy();
      await api.addStock(product, warehouses.main, 1);

      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
      const refused = await api.http.post(`/api/sale-orders/${so.id}/confirm`);
      expect(refused.ok()).toBeFalsy();
      expect(await refused.text()).toContain('discontinued and sold only while stock lasts: 1 left');

      await page.goto('/products');
      await page.getByLabel('Status').selectOption('DISCONTINUED');
      await page.getByLabel('Search products').fill(product.sku);
      await expect(page.getByRole('row').filter({ hasText: product.sku })).toContainText('Discontinued');
    });
});
