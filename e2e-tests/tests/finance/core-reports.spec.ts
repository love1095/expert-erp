import { test, expect } from '../../fixtures/test';

// Phase I3 (spec §8): Cin7's everyday reports from Finance → Reports
test.describe('Reports', () => {
  test('REPORT-001 the Reports page lists Cin7\'s groups; Sales Overview shows an order with its invoice; Stock Received Not Invoiced a receipt not billed',
    { tag: ['@P1', '@finance'] }, async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      const po = await (await api.http.post('/api/purchase-orders', { data: { supplierName: `Report Supplier ${Date.now()}`,
        warehouseId: warehouses.main.id, ordered: true, lines: [{ productId: helmet.id, quantity: 4, unitCost: 5 }] } })).json();
      expect((await api.http.post(`/api/purchase-orders/${po.id}/receive`)).ok()).toBe(true);
      const so = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect((await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } })).ok()).toBe(true);

      await page.goto('/reports');
      for (const group of ['Sale reports', 'Purchase reports', 'Inventory reports', 'Financial reports']) {
        await expect(page.getByRole('heading', { name: group })).toBeVisible();
      }
      await page.getByRole('link', { name: 'Sales Overview' }).click();
      await page.getByLabel('Mode').selectOption('thisMonth');
      await page.getByLabel('Search the report').fill(so.orderNumber);
      const row = page.getByTestId('core-report-table').locator('tbody tr').filter({ hasText: so.orderNumber });
      await expect(row).toContainText('£24.00');          // 2 × £10 + VAT invoiced
      await expect(row.getByRole('link', { name: so.orderNumber })).toHaveAttribute('href', `/sale-orders/${so.id}`);

      await page.goto('/reports/core/stock-received-not-invoiced');
      await page.getByLabel('Search the report').fill(helmet.sku);
      const received = page.getByTestId('core-report-table').locator('tbody tr').filter({ hasText: helmet.sku });
      await expect(received).toContainText('£20.00');     // 4 × £5 not billed yet
      const download = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Export CSV' }).click();
      expect((await download).suggestedFilename()).toMatch(/^Stock_Received_Not_Invoiced\.csv$/);
    });
});
