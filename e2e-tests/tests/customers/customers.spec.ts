import { test, expect } from '../../fixtures/test';
import { addUser, apiFor, registerTenant, uid } from '../../fixtures/api';
import path from 'node:path';

const CIN7_CSV = path.join(__dirname, '../../fixtures/files/cin7-customers.csv');

test.describe('Customers', () => {
  test('CUS-001 a customer created on the Customers page can be edited and searched', { tag: ['@P1', '@customers'] },
    async ({ page, api }) => {
      const name = `Acme ${uid()}`;
      await page.goto('/customers');
      await page.getByRole('button', { name: '+ New Customer' }).click();
      await page.getByLabel('Name *').fill(name);
      await page.getByLabel('Payment Term').fill('30 Day EOM');
      await page.getByLabel('Credit Limit (£)').fill('2500');
      await page.getByLabel('Contact 1 name').fill('Pat');
      await page.getByLabel('Contact 1 email').fill('buyer@acme.test');
      await page.getByRole('button', { name: '+ Add contact' }).click();
      await page.getByLabel('Contact 2 name').fill('Accounts');
      await page.getByRole('button', { name: 'Create Customer' }).click();
      await expect(page.getByRole('button', { name: '+ New Customer' })).toBeVisible();

      const [created] = await api.findCustomers(name);
      expect(created).toMatchObject({ name, status: 'ACTIVE', paymentTerm: '30 Day EOM', creditLimit: 2500, source: 'MANUAL', orderCount: 0 });
      expect(created.contacts.map(c => [c.name, c.email ?? null, c.defaultContact]))
        .toEqual([['Pat', 'buyer@acme.test', true], ['Accounts', null, false]]);
      const row = page.getByTestId(`customer-row-${created.id}`);
      await expect(row).toContainText('buyer@acme.test');

      await row.getByRole('button', { name: 'Edit' }).click();
      await page.getByLabel('Name *').fill(`${name} Ltd`);
      await page.getByLabel('Status').selectOption('DEPRECATED');
      await page.getByRole('button', { name: 'Save Customer' }).click();
      await expect(row).toContainText(`${name} Ltd`);
      await expect(row).toContainText('Deprecated');

      // Found by a contact's email, not only by name
      await page.getByLabel('Search customers').fill('buyer@acme');
      await expect(row).toBeVisible();

      await page.getByLabel('Search customers').fill('no-such-customer');
      await expect(page.getByText('No customers found')).toBeVisible();
      await page.getByLabel('Search customers').fill(`${name} Ltd`);
      await expect(row).toBeVisible();
    });

  test('CUS-002 picking an existing customer on a new sale order links the order to it', { tag: ['@P0', '@customers', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const customer = await api.createCustomer();
      const product = await api.createProduct();

      await page.goto('/sale-orders');
      await page.getByRole('button', { name: '+ New SO' }).click();
      // Different case on purpose: matching ignores case
      await page.getByLabel('Customer *').fill(customer.name.toUpperCase());
      await page.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await page.getByRole('combobox').filter({ hasText: 'Product' }).selectOption({ label: product.sku });
      await page.getByPlaceholder('Qty').fill('2');
      await page.getByRole('button', { name: 'Create Sale Order' }).click();

      await expect(page.getByRole('button', { name: '+ New SO' })).toBeVisible();
      expect((await api.getCustomer(customer.id)).orderCount).toBe(1);
      expect(await api.findCustomers(customer.name)).toHaveLength(1);
    });

  test('CUS-003 a new name on a sale order creates that customer', { tag: ['@P1', '@customers', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const name = `Walk-in ${uid()}`;
      const product = await api.createProduct();

      await page.goto('/sale-orders');
      await page.getByRole('button', { name: '+ New SO' }).click();
      await page.getByLabel('Customer *').fill(name);
      await expect(page.getByText('New customer — will be added to Customers')).toBeVisible();
      await page.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await page.getByRole('combobox').filter({ hasText: 'Product' }).selectOption({ label: product.sku });
      await page.getByPlaceholder('Qty').fill('1');
      await page.getByRole('button', { name: 'Create Sale Order' }).click();

      await expect(page.getByRole('button', { name: '+ New SO' })).toBeVisible();
      await expect.poll(async () => (await api.findCustomers(name)).map(c => c.orderCount)).toEqual([1]);
    });

  test('CUS-004 a customer with orders cannot be deleted; its orders are listed', { tag: ['@P1', '@customers'] },
    async ({ page, api, warehouses }) => {
      const customer = await api.createCustomer();
      const product = await api.createProduct();
      const order = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }], customer);
      const unused = await api.createCustomer();

      await page.goto('/customers');
      const row = page.getByTestId(`customer-row-${customer.id}`);
      await row.getByRole('button', { name: '1' }).click();
      await expect(page.getByTestId(`customer-orders-${customer.id}`)).toContainText(`SO-${order.id}`);

      const dialogs: string[] = [];
      page.on('dialog', d => { if (d.type() === 'alert') dialogs.push(d.message()); });
      await row.getByRole('button', { name: 'Delete' }).click();
      await expect.poll(() => dialogs).toContainEqual(expect.stringContaining('cannot be deleted'));
      expect((await api.getCustomer(customer.id)).id).toBe(customer.id);

      await page.getByTestId(`customer-row-${unused.id}`).getByRole('button', { name: 'Delete' }).click();
      await expect(page.getByTestId(`customer-row-${unused.id}`)).toHaveCount(0);
    });

  test('CUS-005 customers are private to their tenant', { tag: ['@P0', '@customers', '@multi-tenant'] },
    async ({ api, baseURL }) => {
      const customer = await api.createCustomer();
      const other = await apiFor(baseURL!, await registerTenant(baseURL!));
      try {
        expect((await other.http.get(`/api/customers/${customer.id}`)).status()).toBe(404);
        expect((await other.http.put(`/api/customers/${customer.id}`, { data: { name: 'Hijacked' } })).status()).toBe(404);
        expect(await other.findCustomers(customer.name)).toHaveLength(0);
        // Ordering by another tenant's customer id is refused
        const product = await other.createProduct();
        const wh = await other.createWarehouse();
        const res = await other.http.post('/api/sale-orders', {
          data: { customerId: customer.id, warehouseId: wh.id, lines: [{ productId: product.id, quantity: 1 }] },
        });
        expect(res.status()).toBe(404);
      } finally {
        await other.dispose();
      }
      expect((await api.getCustomer(customer.id)).name).toBe(customer.name);
    });

  test('CUS-007 a Cin7 customer export imports one customer per name with its contacts; re-importing updates', { tag: ['@P0', '@customers'] },
    async ({ page, api }) => {
      await page.goto('/customers');
      await page.getByLabel('Customer CSV file').setInputFiles(CIN7_CSV);

      const result = page.getByTestId('customer-import-result');
      await expect(result).toContainText('Imported 4 rows: 3 customers created, 0 updated, 3 contacts.');
      await expect(result).toContainText('1 warning(s)');

      const [northwind] = await api.findCustomers('Northwind Scaffolding Ltd');
      expect(northwind).toMatchObject({ status: 'ACTIVE', paymentTerm: '30 Day EOM', creditLimit: 2500 });
      expect(northwind.contacts.map(c => c.phone ?? null)).toEqual(['+447700900001', null]);
      expect((await api.findCustomers('Old Mill Plumbing'))[0].status).toBe('DEPRECATED');
      await expect(page.getByTestId(`customer-row-${northwind.id}`)).toContainText('Pat Jones +1');

      await page.getByRole('button', { name: 'Dismiss' }).click();
      await page.getByLabel('Customer CSV file').setInputFiles(CIN7_CSV);
      await expect(result).toContainText('0 customers created, 3 updated');
      expect(await api.findCustomers('Northwind Scaffolding Ltd')).toHaveLength(1);
    });

  test('CUS-008 a file that is not a customer export is rejected; STAFF cannot import', { tag: ['@P2', '@customers', '@security'] },
    async ({ page, api, baseURL }) => {
      await page.goto('/customers');
      await page.getByLabel('Customer CSV file').setInputFiles({ name: 'stock.csv', mimeType: 'text/csv', buffer: Buffer.from('Sku,Qty\nA,1\n') });
      await expect(page.getByTestId('customer-import-error')).toContainText("no 'Name' column");

      const staff = await apiFor(baseURL!, await addUser(api, 'STAFF'));
      try {
        const res = await staff.http.post('/api/customers/import', {
          multipart: { file: { name: 'c.csv', mimeType: 'text/csv', buffer: Buffer.from('Name\nX\n') } },
        });
        expect(res.status()).toBe(403);
      } finally {
        await staff.dispose();
      }
    });

  test('CUS-006 a sale order needs a customer', { tag: ['@P2', '@customers', '@sale-orders'] }, async ({ api, warehouses }) => {
    const product = await api.createProduct();
    const res = await api.http.post('/api/sale-orders', {
      data: { warehouseId: warehouses.main.id, lines: [{ productId: product.id, quantity: 1 }] },
    });
    expect(res.status()).toBe(400);
    expect((await res.json()).fields).toHaveProperty('customerGiven', 'Customer is required');
  });
});
