import { test, expect } from '../../fixtures/test';
import { addUser, apiFor } from '../../fixtures/api';

// Phase H4 (spec §6): Cin7's Sales by Product Summary and Inventory Movement Summary, at FIFO cost
test.describe('Sales profit and stock value', () => {
  test('PROFIT-001 a sale at £10 of helmets bought at £6 shows 40 % margin; stock value moves with it; STAFF cannot see it',
    { tag: ['@P1', '@finance'] }, async ({ page, api, warehouses, baseURL }) => {
      const helmet = await api.createProduct();
      const po = await (await api.http.post('/api/purchase-orders', { data: { supplierName: `Profit Supplier ${Date.now()}`,
        warehouseId: warehouses.main.id, ordered: true, lines: [{ productId: helmet.id, quantity: 5, unitCost: 6 }] } })).json();
      expect((await api.http.post(`/api/purchase-orders/${po.id}/receive`)).ok()).toBe(true);
      const so = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 2 }]);   // 2 × £10
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect((await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } })).ok()).toBe(true);

      await page.goto('/reports/sales-by-product');
      await page.getByLabel('Mode').selectOption('thisMonth');
      await page.getByLabel('Show COGS').selectOption('ALL');
      await page.getByLabel('Search', { exact: true }).fill(helmet.sku);
      const row = page.getByTestId('profit-table').locator('tbody tr').filter({ hasText: helmet.sku });
      await expect(row).toContainText('£20.00');     // sale
      await expect(row).toContainText('£12.00');     // COGS 2 × £6
      await expect(row).toContainText('£8.00');      // profit
      await expect(row).toContainText('40.00%');
      await expect(row).toContainText('66.67%');

      await page.goto('/reports/inventory-movement');
      await page.getByLabel('Mode').selectOption('thisMonth');
      await page.getByLabel('Search', { exact: true }).fill(helmet.sku);
      const stock = page.getByTestId('movement-table').locator('tbody tr').filter({ hasText: helmet.sku });
      await expect(stock).toContainText('£30.00');   // in: 5 × £6
      await expect(stock).toContainText('£18.00');   // on hand: 3 × £6

      const staff = await apiFor(baseURL!, await addUser(api, 'STAFF'));
      try {
        const res = await staff.http.get('/api/reports/sales-profit', { params: { from: '2026-01-01', to: '2026-12-31' } });
        expect(res.status()).toBe(403);
      } finally {
        await staff.dispose();
      }
    });
});
