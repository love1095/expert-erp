import { test, expect } from '../../fixtures/test';
import { addUser, apiFor } from '../../fixtures/api';

// Phase D1: order header, VAT per line, additional charges, totals, order numbers (docs/phase-d-sales.md)
test.describe('Sale order VAT and totals', () => {
  test('SO-008 a new order shows VAT and totals while it is filled in, with a reference and a carriage charge', { tag: ['@P0', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const customer = await api.createCustomer();
      const helmet = await api.createProduct({ costPrice: 4 });

      await page.goto('/sale-orders');
      await page.getByRole('button', { name: '+ New SO' }).click();
      const form = page.getByTestId('sale-order-form');
      await form.getByLabel('Customer *').fill(customer.name);
      await form.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await form.getByLabel('Customer reference').fill('PO-024297');
      await api.addStock(helmet, warehouses.main, 7);
      // Search by SKU and pick the match: it shows the stock available and fills in the shop price (none here)
      await form.getByLabel('Product for line 1').fill(helmet.sku);
      const match = form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('option').filter({ hasText: helmet.sku });
      await expect(match).toContainText('7 available');
      await match.getByRole('button').click();
      await form.getByLabel('Quantity for line 1').fill('2');
      await form.getByLabel('Unit price for line 1').fill('10');
      await form.getByRole('button', { name: '+ Add charge' }).click();
      await form.getByLabel('Charge 1 description').fill('Carriage');
      await form.getByLabel('Charge 1 price').fill('5');

      // No rule on product or customer: the organisation's 20 %
      await expect(form.getByTestId('so-form-line-0')).toContainText('£20.00');
      await expect(form.getByLabel('VAT for line 1')).toContainText('Auto: 20% (VAT on Income)');
      const totals = form.getByTestId('so-form-totals');
      await expect(totals).toContainText('£25.00');
      await expect(totals).toContainText('£5.00');
      await expect(totals).toContainText('£30.00');

      await form.getByRole('button', { name: 'Create Sale Order' }).click();
      const details = page.locator('[data-testid^="so-details-"]');
      await expect(details).toContainText('PO-024297');
      await expect(details).toContainText(/SO-\d+/);
      await expect(page.locator('[data-testid^="so-totals-"]')).toContainText('£30.00');
      await expect(page.locator('[data-testid^="so-charges-"]')).toContainText('Carriage');
      await expect(page.locator('[data-testid^="so-panel-"]')).toContainText('20% (VAT on Income)');
      // Margin over cost: (20 − 8) / 20
      await expect(page.locator('[data-testid^="so-panel-"]')).toContainText('60%');
    });

  test('SO-009 only a manager or admin may change the VAT on a line, and less VAT needs a reason', { tag: ['@P0', '@sale-orders', '@security'] },
    async ({ api, warehouses, baseURL }) => {
      const product = await api.createProduct();
      const order = (taxReason?: string) => ({
        data: {
          customerName: 'E2E Customer', warehouseId: warehouses.main.id,
          lines: [{ productId: product.id, quantity: 1, unitPrice: 10, taxRule: 'Zero Rated Income', taxReason }],
        },
      });

      const staff = await apiFor(baseURL!, await addUser(api, 'STAFF'));
      const manager = await apiFor(baseURL!, await addUser(api, 'MANAGER'));
      try {
        const refused = await staff.http.post('/api/sale-orders', order('Export'));
        expect(refused.status()).toBe(403);
        expect((await refused.json()).error).toContain('manager or admin');

        expect((await manager.http.post('/api/sale-orders', order())).status()).toBe(400);
        const ok = await manager.http.post('/api/sale-orders', order('Exported outside the UK'));
        expect(ok.status()).toBe(201);
        const so = await ok.json();
        expect(so.lines[0]).toMatchObject({ taxName: 'Zero Rated Income', taxOverridden: true });
        expect(so.total).toBe(10);
      } finally {
        await staff.dispose();
        await manager.dispose();
      }
    });

  test('SO-010 order numbers carry on from the number set in Settings → Organisation', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      await page.goto('/settings/organisation');
      const settings = page.getByTestId('organisation-settings');
      await settings.getByLabel('Next sale order number').fill('66006');
      await settings.getByLabel('Sales rep for Shopify orders').fill('Web Sales');
      await settings.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByRole('status')).toContainText('Saved');
      await expect(page.getByTestId('tax-rates-sales')).toContainText('Zero Rated Income');

      const product = await api.createProduct();
      const first = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);
      const second = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);
      expect(first.orderNumber).toBe('SO-66006');
      expect(second.orderNumber).toBe('SO-66007');
    });

  test('SO-011 stock goes to the order confirmed first; the next one is backordered and cannot ship it until stock is freed', { tag: ['@P0', '@sale-orders', '@stock'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 5);
      const first = await api.confirmSaleOrder((await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 3 }])).id);
      const second = await api.confirmSaleOrder((await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 4 }])).id);

      await page.goto('/sale-orders');
      await expect(page.getByTestId(`so-row-${second.id}`)).toContainText('Backordered');
      await expect(page.getByTestId(`so-row-${first.id}`)).not.toContainText('Backordered');
      await page.getByTestId(`so-row-${second.id}`).getByRole('button', { name: 'Manage' }).click();
      await expect(page.getByTestId(`so-backordered-${second.id}`)).toContainText(`2 × ${helmet.sku} short`);

      // 5 on hand, 7 reserved
      await page.goto('/stock-overview');
      const row = page.getByRole('row').filter({ hasText: helmet.sku }).filter({ hasText: warehouses.main.name });
      await expect(row.getByRole('cell').nth(7)).toHaveText('7');
      await expect(row.getByRole('cell').nth(8)).toHaveText('-2');

      await page.goto('/');
      await expect(page.getByTestId('sales-status')).toContainText('Backordered1');

      // Like Cin7, the second order can't ship stock promised to the first: only 2 of its 4 are allocated
      const refused = await api.http.post(`/api/sale-orders/${second.id}/fulfill`,
        { data: { lines: [{ saleOrderLineId: second.lines[0].id, quantity: 3 }] } });
      expect(refused.status()).toBe(422);
      expect((await refused.json()).error).toContain('only 2 allocated');

      // Shipping the first order and cancelling the second frees everything
      await api.fulfilSaleOrder(first.id);
      await api.http.post(`/api/sale-orders/${second.id}/cancel`);
      await page.goto('/stock-overview');
      await expect(row.getByRole('cell').nth(8)).toHaveText('2');
    });
});
