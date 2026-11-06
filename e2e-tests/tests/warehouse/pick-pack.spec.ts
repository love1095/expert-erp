import { test, expect } from '../../fixtures/test';

// Phase N2 / N3: pick (by scanning), pack and ship from the ERP
test.describe('Pick, pack and ship', () => {
  test('PICK-001 an order is put on a pick list, picked by scanning (a wrong product refused), packed into a carton and shipped',
    { tag: ['@P1', '@warehouse'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      await api.addStock(product, warehouses.main, 5);
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);

      await page.goto('/picking');
      await page.getByLabel(`Tick ${so.orderNumber}`).check();
      await page.getByRole('button', { name: /Create pick list/ }).click();
      await expect(page).toHaveURL(/\/picking\/\d+$/);
      await expect(page.getByTestId('pick-status')).toHaveText('To pick');

      const scan = page.getByLabel('Scan barcode or SKU');
      await scan.fill('NOT-A-PRODUCT');
      await scan.press('Enter');
      await expect(page.getByRole('status')).toContainText('wrong product');
      await scan.fill(product.sku);
      await scan.press('Enter');
      await expect(page.getByRole('status')).toContainText(`✓`);
      await scan.fill(product.sku);
      await scan.press('Enter');
      await expect(page.getByTestId('pick-lines')).toContainText('2');
      await page.getByRole('button', { name: 'Confirm pick' }).click();
      await expect(page.getByTestId('pick-status')).toHaveText('Picked');

      await page.goto(`/sale-orders/${so.id}`);
      const card = page.getByTestId('warehouse-card');
      await expect(card.getByTestId('warehouse-stage')).toHaveText('Picked');
      await card.getByLabel('Carton weight kg').fill('1.2');
      await card.getByRole('button', { name: 'Pack into a carton' }).click();
      await expect(card.getByTestId('warehouse-stage')).toHaveText('Packed');
      await card.getByLabel('Carrier').fill('DPD');
      await card.getByLabel('Tracking number').fill('TRK-E2E');
      await card.getByRole('button', { name: 'Ship cartons' }).click();
      await expect(card.getByTestId('warehouse-stage')).toHaveText('Shipped');
    });
});
