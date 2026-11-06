import { test, expect } from '../../fixtures/test';

// Phase O: book the packed cartons with the (fake) courier, cancel and book again, ship with its tracking, e-mail it
test.describe('Couriers', () => {
  test('COURIER-001 packed cartons are booked with a courier (cancelled and rebooked), shipped with its tracking, e-mailed to the customer',
    { tag: ['@P1', '@warehouse'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      await api.addStock(product, warehouses.main, 5);
      const customer = await api.createCustomer({ email: 'stores@courier.example' });
      expect((await api.http.put('/api/mail/settings', { data: { fromName: 'E2E', fromAddress: 'despatch@e2e.example', smtpStartTls: true } })).ok()).toBeTruthy();
      const res = await api.http.post('/api/sale-orders', { data: {
        customerId: customer.id, warehouseId: warehouses.main.id,
        shippingAddress: { line1: 'Unit 4, Canal Street', city: 'Manchester', postcode: 'M1 3HE' },
        lines: [{ productId: product.id, quantity: 2, unitPrice: 10 }] } });
      expect(res.ok()).toBeTruthy();
      const so = await res.json();
      await api.confirmSaleOrder(so.id);
      const pl = await (await api.http.post('/api/warehouse/pick-lists', { data: { orderIds: [so.id] } })).json();
      expect((await api.http.post(`/api/warehouse/pick-lists/${pl.id}/confirm`)).ok()).toBeTruthy();
      expect((await api.http.post(`/api/warehouse/orders/${so.id}/pack`, { data: [{ weightKg: 2.5, lines: [{ saleOrderLineId: so.lines[0].id, quantity: 2 }] }] })).ok()).toBeTruthy();

      await page.goto(`/sale-orders/${so.id}`);
      const card = page.getByTestId('warehouse-card');
      await expect(card.getByTestId('warehouse-stage')).toHaveText('Packed');

      // Book DPD, then cancel it and book Royal Mail instead
      await card.getByRole('button', { name: 'Book courier' }).click();
      await card.getByLabel('Courier service').selectOption('DPD-NEXT-DAY');
      await card.getByRole('button', { name: 'Book', exact: true }).click();
      const bookings = card.getByTestId('courier-bookings');
      await expect(bookings).toContainText('DPD Next Day');
      await expect(bookings.getByTestId('courier-status')).toHaveText('booked');
      await bookings.getByRole('button', { name: 'Cancel booking' }).click();
      await expect(bookings.getByTestId('courier-status')).toHaveText('cancelled');
      await card.getByRole('button', { name: 'Book courier' }).click();
      await card.getByLabel('Courier service').selectOption('RM-TRACKED-48');
      await card.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(card.getByText(/Ships with Royal Mail Tracked 48, tracking FK\d{9}GB/)).toBeVisible();

      // Ship: the booking's tracking goes on the shipment and to the customer
      await card.getByRole('button', { name: 'Ship cartons' }).click();
      await expect(card.getByTestId('warehouse-stage')).toHaveText('Shipped');
      await expect(card.getByTestId('ship-email')).toHaveText('Tracking e-mailed to stores@courier.example');
      await expect(bookings.getByTestId('courier-status').last()).toHaveText('shipped');
      const order = await api.getSaleOrder(so.id) as unknown as { fulfilments: { trackingCompany: string; trackingNumber: string; trackingUrl: string }[] };
      expect(order.fulfilments[0].trackingCompany).toBe('Royal Mail Tracked 48');
      expect(order.fulfilments[0].trackingNumber).toMatch(/^FK\d{9}GB$/);
      expect(order.fulfilments[0].trackingUrl).toContain(order.fulfilments[0].trackingNumber);
    });
});
