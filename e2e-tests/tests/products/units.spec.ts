import { test, expect } from '../../fixtures/test';

// Phase P3: a product sold by the box; stock moves in its own unit
test.describe('Units of measure', () => {
  test('UOM-001 a box unit is added on the product page; two boxes sold take 20 from stock',
    { tag: ['@P1', '@products'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct({ sellPrice: 0.5 });
      await api.addStock(product, warehouses.main, 50);

      await page.goto(`/products/${product.id}`);
      await page.getByRole('navigation', { name: 'Product sections' }).getByRole('button', { name: 'Units' }).click();
      const card = page.getByTestId('product-units');
      await card.getByRole('button', { name: 'Add unit' }).click();
      await card.getByLabel('Unit 1 name').fill('Box of 10');
      await card.getByLabel('Unit 1 holds').fill('10');
      await card.getByLabel('Unit 1 sell price').fill('4');
      await card.getByRole('button', { name: 'Save units' }).click();
      await expect(card.getByRole('status')).toHaveText('Saved');

      const res = await api.http.post('/api/sale-orders', { data: { customerName: 'E2E Customer', warehouseId: warehouses.main.id,
        lines: [{ productId: product.id, quantity: 2, unitName: 'Box of 10' }] } });
      expect(res.ok()).toBeTruthy();
      const so = await res.json();
      expect(Number(so.lines[0].unitPrice)).toBe(4);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect(await api.stock(product, warehouses.main)).toBe(30);

      await page.goto(`/sale-orders/${so.id}`);
      await expect(page.getByText('Box of 10').first()).toBeVisible();
    });
});
