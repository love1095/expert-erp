import { test, expect } from '../../fixtures/test';

test('PO-001 receiving a purchase order adds stock', { tag: ['@P0', '@purchase-orders'] }, async ({ page, api, warehouses }) => {
  const product = await api.createProduct();
  const po = await api.createPurchaseOrder(warehouses.main, [{ product, quantity: 40 }]);

  await page.goto('/purchase-orders');
  const row = page.getByTestId(`po-row-${po.id}`);
  await row.getByRole('button', { name: 'Receive' }).click();

  await expect(row).toContainText('RECEIVED');
  expect(await api.stock(product, warehouses.main)).toBe(40);
});
