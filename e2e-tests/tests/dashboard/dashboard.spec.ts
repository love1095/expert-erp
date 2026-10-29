import { test, expect } from '../../fixtures/test';
import { apiFor, registerTenant } from '../../fixtures/api';

test.describe('Dashboard', () => {
  test('DSH-001 the dashboard shows this tenant\'s sales, stock value, low stock and top products', { tag: ['@P1', '@dashboard'] },
    async ({ page, api, warehouses }) => {
      // Sale orders are priced at £10 a unit by the API helper
      const helmet = await api.createProduct({ name: 'Helmet', costPrice: 4, minStock: 10 });
      const boots = await api.createProduct({ name: 'Boots', costPrice: 20, minStock: 5 });
      await api.addStock(helmet, warehouses.main, 50);
      await api.addStock(boots, warehouses.main, 3);

      const shipped = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 12 }]);
      await api.confirmSaleOrder(shipped.id);
      await api.fulfilSaleOrder(shipped.id);
      const toShip = await api.createSaleOrder(warehouses.main, [{ product: boots, quantity: 1 }]);
      await api.confirmSaleOrder(toShip.id);
      await api.createSaleOrder(warehouses.main, [{ product: boots, quantity: 2 }]);  // draft

      await page.goto('/');

      // 12×£10 + 1×£10 + 2×£10
      await expect(page.getByTestId('kpi-sales')).toContainText('£150.00');
      await expect(page.getByTestId('kpi-sales')).toContainText('3 orders');
      await expect(page.getByTestId('kpi-to-ship')).toContainText('1');
      await expect(page.getByTestId('kpi-to-ship')).toContainText('1 draft to confirm');
      // Helmet 38 × £4 + boots 3 × £20
      await expect(page.getByTestId('kpi-stock-value')).toContainText('£212.00');
      await expect(page.getByTestId('kpi-low-stock')).toContainText('1');
      await expect(page.getByTestId('kpi-shipped')).toContainText('12');
      await expect(page.getByTestId('low-stock')).toContainText(boots.sku);
      await expect(page.getByTestId('low-stock')).not.toContainText(helmet.sku);
      await expect(page.getByTestId('top-products')).toContainText(helmet.sku);
      await expect(page.getByTestId('recent-orders')).toContainText(`SO-${toShip.id}`);

      // The table view lists today's figures
      await page.getByRole('button', { name: 'Show as table' }).click();
      const todayRow = page.getByTestId('daily-table').locator('tbody tr').first();
      await expect(todayRow).toContainText('£150.00');
      await expect(todayRow).toContainText('53');   // units in: 50 + 3
    });

  test('DSH-002 the period buttons change the period of the figures', { tag: ['@P2', '@dashboard'] }, async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('kpi-sales')).toContainText('last 30 days');

    await page.getByRole('button', { name: '7 days' }).click();
    await expect(page.getByTestId('kpi-sales')).toContainText('last 7 days');
    await expect(page.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'Show as table' }).click();
    await expect(page.getByTestId('daily-table').locator('tbody tr')).toHaveCount(7);
  });

  test('DSH-003 dashboard figures never include another tenant\'s data', { tag: ['@P0', '@dashboard', '@multi-tenant'] },
    async ({ api, warehouses, baseURL }) => {
      const product = await api.createProduct({ costPrice: 100, minStock: 1000 });
      await api.addStock(product, warehouses.main, 10);
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 5 }]);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);

      const other = await apiFor(baseURL!, await registerTenant(baseURL!));
      try {
        const d = await other.dashboard(30);
        expect(d.kpis).toMatchObject({
          products: 0, stockValue: 0, lowStockProducts: 0, ordersInPeriod: 0, salesValueInPeriod: 0, unitsShippedInPeriod: 0,
        });
        expect(d.topProducts).toEqual([]);
        expect(d.daily.every(day => day.unitsIn === 0 && day.unitsOut === 0)).toBe(true);
      } finally {
        await other.dispose();
      }

      const mine = await api.dashboard(30);
      expect(mine.kpis.stockValue).toBe(500);
      expect(mine.kpis.unitsShippedInPeriod).toBe(5);
    });

  test('DSH-004 an invalid period is refused', { tag: ['@P3', '@dashboard'] }, async ({ api }) => {
    expect((await api.http.get('/api/dashboard', { params: { days: 0 } })).status()).toBe(400);
    expect((await api.http.get('/api/dashboard', { params: { days: 366 } })).status()).toBe(400);
  });
});
