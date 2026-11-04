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

  test('ADDRESS-002 the Import menu takes the chosen Cin7 file and refuses another; Export gives the same file back', { tag: ['@P1', '@customers'] },
    async ({ page, api }) => {
      const customer = await api.createCustomer({ name: `Menu ${Date.now()}` });
      const file = (rows: string) => ({ name: 'CustomerAddresses.csv', mimeType: 'text/csv', buffer: Buffer.from(
        'Action,Name,AddressType,AddressDefaultForType,AddressLine1,AddressLine2,City,State,Postcode,Country,IsParent\n' + rows) });
      const addresses = file(`"Create/Update","${customer.name}",Billing,True,"1 Mill Lane","","Bury","","BL9 9SX","United Kingdom","False"\n`);
      await page.goto('/customers');

      // Import → Customers with the addresses file: refused, nothing imported
      await page.getByRole('button', { name: 'Import', exact: true }).click();
      let chooser = page.waitForEvent('filechooser');
      await page.getByRole('menuitem', { name: 'Customers' }).click();
      await (await chooser).setFiles(addresses);
      await expect(page.getByTestId('customer-import-error')).toContainText('customer addresses file, not customers: choose Import → Addresses');
      expect((await api.getCustomer(customer.id)).addresses).toHaveLength(0);

      // Import → Addresses: taken
      await page.getByRole('button', { name: 'Import', exact: true }).click();
      chooser = page.waitForEvent('filechooser');
      await page.getByRole('menuitem', { name: 'Addresses' }).click();
      await (await chooser).setFiles(addresses);
      await expect(page.getByTestId('customer-import-result')).toContainText('1 addresses');
      expect((await api.getCustomer(customer.id)).address).toBe('1 Mill Lane, Bury BL9 9SX, United Kingdom');

      // Export → Addresses: Cin7's file, with this customer's address in it
      await page.getByRole('button', { name: 'Export', exact: true }).click();
      const download = page.waitForEvent('download');
      await page.getByRole('menuitem', { name: 'Addresses' }).click();
      const d = await download;
      expect(d.suggestedFilename()).toMatch(/^CustomerAddresses_\d{4}-\d{2}-\d{2}\.csv$/);
      const text = require('fs').readFileSync(await d.path(), 'utf8');
      expect(text).toContain('AddressType,AddressDefaultForType');
      expect(text).toContain(`Create/Update,${customer.name},Billing,True,1 Mill Lane`);
    });
});
