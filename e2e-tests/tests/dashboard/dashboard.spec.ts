import { test, expect } from '../../fixtures/test';
import { apiFor, registerTenant, uid } from '../../fixtures/api';

test.describe('Dashboard', () => {
  test('DSH-001 the dashboard shows this tenant\'s sales, stock value, reorder list and top sellers', { tag: ['@P1', '@dashboard'] },
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
      await expect(page.getByTestId('kpi-reorder')).toContainText('1');
      await expect(page.getByTestId('kpi-shipped')).toContainText('12');
      await expect(page.getByTestId('reorder')).toContainText(boots.sku);
      await expect(page.getByTestId('reorder')).not.toContainText(helmet.sku);
      // Helmet £120 first, then boots £30
      await expect(page.getByTestId('sellers').locator('li').first()).toContainText(helmet.sku);
      await expect(page.getByTestId('sellers').locator('li').first()).toContainText('£120.00');
      await page.getByRole('group', { name: 'Sellers' }).getByRole('button', { name: 'Worst' }).click();
      await expect(page.getByTestId('sellers').locator('li').first()).toContainText(boots.sku);
      await expect(page.getByTestId('sales-status')).toContainText('Draft orders1');
      await expect(page.getByTestId('sales-status')).toContainText('To ship1');
      await expect(page.getByTestId('recent-orders')).toContainText(`SO-${toShip.id}`);

      // The table view lists today's figures
      await page.getByRole('button', { name: 'Show as table' }).click();
      const todayRow = page.getByTestId('daily-table').locator('tbody tr').first();
      await expect(todayRow).toContainText('£150.00');
      await expect(todayRow).toContainText('53');   // units in: 50 + 3
    });

  test('DSH-002 the period presets and a custom range change the period of the figures', { tag: ['@P2', '@dashboard'] },
    async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      await api.createSaleOrder(warehouses.main, [{ product, quantity: 3 }]);   // today, £30

      await page.goto('/');
      await expect(page.getByTestId('kpi-sales')).toContainText('Last 30 days');

      const period = page.getByLabel('Period');
      await period.selectOption({ label: 'Last 7 days' });
      await expect(page.getByTestId('kpi-sales')).toContainText('Last 7 days');
      await page.getByRole('button', { name: 'Show as table' }).click();
      await expect(page.getByTestId('daily-table').locator('tbody tr')).toHaveCount(7);

      // Yesterday has nothing; today has the order
      await period.selectOption({ label: 'Yesterday' });
      await expect(page.getByTestId('kpi-sales')).toContainText('£0.00');
      await period.selectOption({ label: 'Today' });
      await expect(page.getByTestId('kpi-sales')).toContainText('£30.00');

      // This quarter is the whole calendar quarter, days to come included
      await period.selectOption({ label: 'This quarter' });
      const now = new Date();
      const q = Math.floor(now.getMonth() / 3) * 3;
      const qEnd = new Date(now.getFullYear(), q + 3, 0);
      await expect(page.getByTestId('period-dates')).toContainText(
        `01/${String(q + 1).padStart(2, '0')}/${now.getFullYear()} – ${String(qEnd.getDate()).padStart(2, '0')}/${String(q + 3).padStart(2, '0')}/${qEnd.getFullYear()}`);

      // A custom range that ends yesterday leaves today's order out
      await period.selectOption({ label: 'Custom' });
      const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      const day = (n: number) => iso(new Date(now.getFullYear(), now.getMonth(), now.getDate() - n));
      await page.getByLabel('From', { exact: true }).fill(day(10));
      await page.getByLabel('To', { exact: true }).fill(day(1));
      await page.getByRole('button', { name: 'Apply' }).click();
      await expect(page.getByTestId('kpi-sales')).toContainText('£0.00');
      await expect(page.getByTestId('daily-table').locator('tbody tr')).toHaveCount(10);
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
          products: 0, stockValue: 0, productsToReorder: 0, ordersInPeriod: 0, salesValueInPeriod: 0, unitsShippedInPeriod: 0,
        });
        expect(d.sales).toEqual({ draftOrders: 0, toShip: 0, draftInvoices: 0, awaitingPayment: 0, overdueInvoices: 0 });
        expect(d.topSellers).toEqual([]);
        expect((await other.dashboardReorder()).totalElements).toBe(0);
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
    expect((await api.http.get('/api/dashboard', { params: { days: 367 } })).status()).toBe(400);
    expect((await api.http.get('/api/dashboard', { params: { from: '2026-10-10', to: '2026-10-09' } })).status()).toBe(400);
    expect((await api.http.get('/api/dashboard', { params: { from: '2025-01-01', to: '2026-01-02' } })).status()).toBe(400);
    expect((await api.http.get('/api/dashboard', { params: { from: '2026-10-10' } })).status()).toBe(400);
  });

  test('DSH-005 the reorder list counts stock on order and can be searched', { tag: ['@P1', '@dashboard'] },
    async ({ page, api, warehouses }) => {
      const gloves = await api.createProduct({ name: `Gloves ${uid()}`, minStock: 10 });
      const masks = await api.createProduct({ name: `Masks ${uid()}`, minStock: 10 });
      await api.addStock(gloves, warehouses.main, 2);
      await api.addStock(masks, warehouses.main, 3);
      // Masks: 3 in stock + 8 on order is above the minimum → not to reorder
      await api.createPurchaseOrder(warehouses.main, [{ product: masks, quantity: 8 }, { product: gloves, quantity: 3 }]);

      await page.goto('/');
      const reorder = page.getByTestId('reorder');
      await expect(page.getByTestId('kpi-reorder')).toContainText('1');
      await expect(reorder).toContainText(gloves.sku);
      await expect(reorder).not.toContainText(masks.sku);
      // Gloves: 2 + 3 = 5 of a minimum of 10, so order 5
      const row = reorder.locator('tbody tr', { hasText: gloves.sku });
      await expect(row.locator('td').nth(1)).toHaveText('5');
      await expect(row.locator('td').nth(3)).toHaveText('5');
      await expect(page.getByTestId('purchase-status')).toContainText('Awaiting delivery1');

      await page.getByLabel('Search the reorder list').fill('no such product');
      await page.getByRole('button', { name: 'Search', exact: true }).click();
      await expect(page.getByText('No match')).toBeVisible();
      await page.getByLabel('Search the reorder list').fill(gloves.sku);
      await page.getByRole('button', { name: 'Search', exact: true }).click();
      await expect(reorder).toContainText(gloves.sku);
    });
});
