import { test, expect } from '../../fixtures/test';

// Phase D4a: invoices made from orders (docs/phase-d-sales.md)
test.describe('Invoices from orders', () => {
  test('INV-001 part of an order is invoiced from the order with its VAT; the rest stays to invoice', { tag: ['@P0', '@invoices', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      const gloves = await api.createProduct();
      // £10 a unit (API helper), 20 % VAT: 3 helmets £36, 2 gloves £24
      const order = await api.confirmSaleOrder((await api.createSaleOrder(warehouses.main,
        [{ product: helmet, quantity: 3 }, { product: gloves, quantity: 2 }])).id);

      await page.goto('/sale-orders');
      await page.getByTestId(`so-row-${order.id}`).getByRole('button', { name: 'Manage' }).click();
      const panel = page.getByTestId(`so-panel-${order.id}`);
      await expect(panel.getByTestId(`so-invoices-${order.id}`)).toContainText('to invoice £60.00');

      await panel.getByRole('button', { name: 'Create invoice' }).click();
      await panel.getByLabel(`Invoice quantity for ${helmet.sku}`).fill('1');
      await panel.getByLabel(`Invoice quantity for ${gloves.sku}`).fill('0');
      await panel.getByRole('button', { name: 'Create & issue' }).click();

      const invoices = panel.getByTestId(`so-invoices-${order.id}`);
      await expect(invoices).toContainText('ISSUED');
      await expect(invoices).toContainText('£12.00');
      await expect(invoices).toContainText('to invoice £48.00');
      await expect(invoices).toContainText('invoiced £12.00');

      // The invoice carries the VAT
      await page.goto('/invoices');
      await page.getByRole('row').filter({ hasText: /INV-/ }).first().click();
      await expect(page.getByText('VAT', { exact: true })).toBeVisible();
    });

  test('INV-002 with the setting on, each shipment is invoiced and issued', { tag: ['@P1', '@invoices', '@sale-orders'] },
    async ({ api, warehouses }) => {
      const settings = await (await api.http.get('/api/settings/organisation')).json();
      expect((await api.http.put('/api/settings/organisation', { data: { ...settings, autoInvoiceOnShipment: true } })).status()).toBe(200);
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);
      const order = await api.confirmSaleOrder((await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 4 }])).id);

      await api.fulfilSaleOrder(order.id, [{ lineId: order.lines[0].id, quantity: 1 }]);
      await api.fulfilSaleOrder(order.id);
      const after = await (await api.http.get(`/api/sale-orders/${order.id}`)).json();
      expect(after.invoices.map((i: { status: string; total: number }) => [i.status, i.total])).toEqual([['ISSUED', 12], ['ISSUED', 36]]);
      expect(after.toInvoiceTotal).toBe(0);
    });

  test('INV-003 a credit note restocks goods, credits a shipping refund and records the refund, as in Cin7', { tag: ['@P0', '@invoices', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);
      const order = await api.confirmSaleOrder((await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 4 }])).id);
      await api.fulfilSaleOrder(order.id);
      const invoice = await (await api.http.post(`/api/sale-orders/${order.id}/invoices`, { data: { issue: true } })).json();
      expect(await api.stock(helmet, warehouses.main)).toBe(6);

      await page.goto('/invoices');
      await page.getByRole('row').filter({ hasText: invoice.invoiceNumber }).click();
      await page.getByRole('button', { name: 'Credit note & restock' }).click();
      const form = page.getByTestId('credit-note-form');
      await form.getByLabel(`Credit quantity for ${helmet.sku}`).fill('1');
      await form.getByLabel(`Restock ${helmet.sku}`).check();
      await form.getByLabel('Extra amount description').fill('Shipping refund');
      await form.getByLabel('Extra amount price').fill('5');
      await form.getByLabel('Settle the credit').selectOption('REFUND');
      await form.getByLabel('Refund reference').fill('BANK-123');
      await form.getByRole('button', { name: 'Create credit note' }).click();

      // 1 × £10 + £5, + 20 % VAT = £18.00, refunded
      await expect(page.getByText(`Credit note for ${invoice.invoiceNumber}`)).toBeVisible();
      await expect(page.getByText('CR-00001').first()).toBeVisible();
      await expect(page.getByText('BANK-123')).toBeVisible();
      await expect.poll(() => api.stock(helmet, warehouses.main)).toBe(7);

      const after = await (await api.http.get(`/api/sale-orders/${order.id}`)).json();
      expect(after.creditedTotal).toBe(18);
    });
});
