import { test, expect } from '../../fixtures/test';

// List toolbar like Cin7: search as you type, filters and a footer with pages
test.describe('List search', () => {
  test('LST-001 sale orders are found by customer or order number, and the footer counts them', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);
      const acme = await api.createCustomer();
      const other = await api.createCustomer();
      const acmeOrder = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 1 }], acme);
      const otherOrder = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 1 }], other);

      await page.goto('/sale-orders');
      await page.getByLabel('Search').fill(acme.name);
      await expect(page.getByTestId(`so-row-${acmeOrder.id}`)).toBeVisible();
      await expect(page.getByTestId(`so-row-${otherOrder.id}`)).toHaveCount(0);
      await expect(page.getByText('Displaying 1 - 1 of 1')).toBeVisible();

      await page.getByLabel('Search').fill(otherOrder.orderNumber);
      await expect(page.getByTestId(`so-row-${otherOrder.id}`)).toBeVisible();
      await expect(page.getByTestId(`so-row-${acmeOrder.id}`)).toHaveCount(0);

      // Combined with the status filter: nothing is a quote
      await page.getByLabel('Show').selectOption('QUOTE');
      await expect(page.getByText('No quotes or orders match')).toBeVisible();
    });

  test('LST-002 stock is found by SKU', { tag: ['@P1', '@stock'] },
    async ({ page, api, warehouses }) => {
      const gloves = await api.createProduct();
      const boots = await api.createProduct();
      await api.addStock(gloves, warehouses.main, 3);
      await api.addStock(boots, warehouses.main, 3);

      await page.goto('/stock-overview');
      await page.getByLabel('Search').fill(gloves.sku);
      await expect(page.getByRole('cell', { name: gloves.sku, exact: true })).toBeVisible();
      await expect(page.getByRole('cell', { name: boots.sku, exact: true })).toHaveCount(0);
    });
});
