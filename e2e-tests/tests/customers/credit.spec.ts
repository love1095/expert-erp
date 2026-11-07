import { test, expect } from '../../fixtures/test';

// Phase G2 (spec §5): credit hold when the limit or payment terms are breached, warning at order entry
test.describe('Credit control', () => {
  test('CREDIT-001 an order over the credit limit warns, needs a manager to confirm, and a hold can be put on by hand', { tag: ['@P1', '@customers'] },
    async ({ page, api, warehouses }) => {
      const product = await api.createProduct({ sellPrice: 10 });
      const customer = await api.createCustomer({ name: `Credit ${Date.now()}` });
      await api.http.put(`/api/customers/${customer.id}`, { data: { ...customer, creditLimit: 50 } });

      // 5 × £10 + 20 % VAT = £60 against a £50 limit
      const res = await api.http.post('/api/sale-orders', { data: { customerId: customer.id, warehouseId: warehouses.main.id,
        lines: [{ productId: product.id, quantity: 5, unitPrice: 10 }] } });
      const order = await res.json();
      expect(order.creditHold).toBe(true);

      await page.goto(`/sale-orders/${order.id}`);
      await expect(page.getByTestId(`so-credit-${order.id}`)).toContainText('£10.00 over the £50.00 credit limit');
      // The ERP's admin is a manager: confirms with a reason
      page.removeAllListeners('dialog');
      page.once('dialog', d => d.accept('Paying on collection'));
      await page.getByRole('button', { name: 'Confirm anyway' }).click();
      await expect(page.getByTestId(`so-status-${order.id}`)).toContainText('CONFIRMED');
      await expect(page.getByText('Confirmed or shipped past a credit hold: Paying on collection')).toBeVisible();

      // The customer page: owed and on order, over the limit; put on hold by hand, then released
      await page.goto(`/customers/${customer.id}`);
      const credit = page.getByTestId('customer-credit');
      await expect(page.getByTestId('customer-credit-status')).toHaveText('On credit hold');
      await expect(credit).toContainText('£60.00');
      page.once('dialog', d => d.accept('Cheque bounced'));
      await credit.getByRole('button', { name: 'Put on hold' }).click();
      await expect(credit).toContainText('put on hold: Cheque bounced');
      await credit.getByRole('button', { name: 'Release the hold' }).click();
      await expect(credit).not.toContainText('Cheque bounced');

      // Listed as on hold while held by hand
      page.once('dialog', d => d.accept('Disputed invoice'));
      await credit.getByRole('button', { name: 'Put on hold' }).click();
      await expect(credit).toContainText('Disputed invoice');
      await page.goto('/customers');
      await page.getByLabel('Search customers').fill(customer.name);
      await expect(page.locator('[data-testid^="customer-row-"]', { hasText: customer.name })).toContainText('On hold');
    });
  test('CREDIT-002 a customer put on hold after the order was confirmed stops the shipping until a manager gives a reason', { tag: ['@P1', '@customers'] },
    async ({ page, api, warehouses }) => {
      const product = await api.createProduct({ sellPrice: 10 });
      await api.addStock(product, warehouses.main, 5);
      const customer = await api.createCustomer({ name: `Hold ${Date.now()}` });
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }], customer);
      await api.confirmSaleOrder(so.id);
      expect((await api.http.put(`/api/customers/${customer.id}/credit/hold`, { data: { hold: true, reason: 'Cheque bounced' } })).ok()).toBeTruthy();

      const refused = await api.http.post(`/api/sale-orders/${so.id}/fulfill`);
      expect(await refused.text()).toContain('Shipping is stopped');
      // The admin is a manager: ships anyway with a reason
      const shipped = await api.http.post(`/api/sale-orders/${so.id}/fulfill`, { data: { creditOverride: 'Paid by card' } });
      expect(shipped.ok()).toBeTruthy();
      await page.goto(`/sale-orders/${so.id}`);
      await expect(page.getByText('Confirmed or shipped past a credit hold: Paid by card')).toBeVisible();
    });
});
