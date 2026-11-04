import { test, expect } from '../../fixtures/test';

// Phase G4 (spec §5): customers' address book like Cin7's; billing and shipping copied onto the order, changeable there
test.describe('Customer addresses', () => {
  test('ADDRESS-001 an order starts with the customer\'s default addresses, can deliver elsewhere, and keeps them', { tag: ['@P1', '@customers'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);
      const customer = await api.http.post('/api/customers', { data: { name: `Addresses ${Date.now()}`, contacts: [], addresses: [
        { type: 'BILLING', defaultForType: true, line1: 'Barlow Fold', city: 'Bury', postcode: 'BL9 9SX', country: 'United Kingdom' },
        { type: 'SHIPPING', defaultForType: true, line1: 'Site 4, Canal Street', city: 'Manchester', postcode: 'M1 3HE' },
        { type: 'SHIPPING', line1: 'Yard 2, Moss Lane', city: 'Bolton' },
      ] } }).then(r => r.json());

      // The customer page lists the address book with the defaults marked
      await page.goto(`/customers/${customer.id}`);
      const book = page.getByTestId(`customer-addresses-${customer.id}`);
      await expect(book).toContainText('Addresses (3)');
      await expect(book).toContainText('Barlow Fold');
      await expect(book.getByText('Default')).toHaveCount(2);

      // A new order fills in the defaults; deliver to the other saved address instead, for a named contact
      await page.goto('/sale-orders');
      await page.getByRole('button', { name: '+ New SO' }).click();
      const form = page.getByTestId('sale-order-form');
      await form.getByLabel('Customer *').fill(customer.name);
      await expect(form.getByLabel('Billing address line 1')).toHaveValue('Barlow Fold');
      await expect(form.getByLabel('Shipping address line 1')).toHaveValue('Site 4, Canal Street');
      await form.getByLabel('Shipping address: saved addresses').selectOption({ label: 'Shipping: Yard 2, Moss Lane, Bolton' });
      await expect(form.getByLabel('Shipping address line 1')).toHaveValue('Yard 2, Moss Lane');
      await form.getByLabel('Ship to contact').fill('Gatehouse');
      await form.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await form.getByLabel('Product for line 1').fill(helmet.sku);
      await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: helmet.sku }).click();
      await form.getByLabel('Quantity for line 1').fill('1');
      await form.getByLabel('Unit price for line 1').fill('10');
      await form.getByRole('button', { name: 'Create sale order' }).click();

      const billing = page.locator('[data-testid^="so-billing-"]');
      const shipping = page.locator('[data-testid^="so-shipping-"]');
      await expect(billing).toContainText('Barlow Fold');
      await expect(shipping).toContainText('Yard 2, Moss Lane');
      await expect(page.getByText('Gatehouse')).toBeVisible();
      const orderId = Number((await shipping.getAttribute('data-testid'))!.replace('so-shipping-', ''));

      // The customer moves: the order keeps the addresses it was made with
      await api.http.put(`/api/customers/${customer.id}`, { data: { ...customer, addresses: [
        { type: 'BILLING', defaultForType: true, line1: 'New House', city: 'Rochdale' }] } });
      const order = await api.http.get(`/api/sale-orders/${orderId}`).then(r => r.json());
      expect(order.billingAddress.line1).toBe('Barlow Fold');
      expect(order.shippingAddress.line1).toBe('Yard 2, Moss Lane');
      expect(order.shipToContact).toBe('Gatehouse');
    });
});
