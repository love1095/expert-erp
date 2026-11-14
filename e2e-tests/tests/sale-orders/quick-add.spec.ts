import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';

// The "+" next to Customer, Contact, Terms and Sales rep on the sale form (like Cin7): a pop-up adds what is missing
// without leaving the form, and the new entry is chosen in its field.
test.describe('Sale form: + buttons', () => {
  test('SO-015 a customer, contact, payment term and sales rep are added from the sale form and kept on the order',
    { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      const name = `Quick Customer ${uid()}`;
      const term = `Net monthly ${uid()}`;
      const rep = `Rep ${uid()}`;

      await page.goto('/sale-orders/new');
      const form = page.getByTestId('sale-order-form');
      // No customer yet: a contact cannot be added
      await expect(form.getByRole('button', { name: 'New contact' })).toBeDisabled();

      // New customer, with its first contact
      await form.getByRole('button', { name: 'New customer' }).click();
      const newCustomer = page.getByTestId('quick-new-customer');
      await expect(newCustomer.getByRole('heading', { name: 'New customer' })).toBeVisible();
      await newCustomer.getByRole('textbox', { name: 'Name', exact: true }).fill(name);
      await newCustomer.getByLabel('Contact name').fill('Sam Reed');
      await newCustomer.getByLabel('Phone').fill('01632 960001');
      await newCustomer.getByLabel('E-mail').fill('sam@quick.test');
      await newCustomer.getByRole('button', { name: 'Add customer' }).click();
      await expect(newCustomer).toHaveCount(0);
      await expect(form.getByLabel('Customer *')).toHaveValue(name);
      // The pop-up's Save did not save the order
      await expect(page).toHaveURL(/\/sale-orders\/new$/);
      // Their default contact is offered until another is chosen
      await expect(form.getByTestId('so-form-contact-details')).toContainText('sam@quick.test');

      // New contact for that customer: chosen on the order
      await form.getByRole('button', { name: 'New contact' }).click();
      const newContact = page.getByTestId('quick-new-contact');
      await expect(newContact).toContainText(name);
      await newContact.getByRole('textbox', { name: 'Name', exact: true }).fill('Jo Park');
      await newContact.getByRole('textbox', { name: 'E-mail' }).fill('jo@quick.test');
      await newContact.getByRole('button', { name: 'Add contact' }).click();
      await expect(newContact).toHaveCount(0);
      await expect(form.getByLabel('Contact', { exact: true })).toHaveValue('Jo Park');
      await expect(form.getByTestId('so-form-contact-details')).toContainText('jo@quick.test');

      // Payment term: the name says nothing about days, the rule comes from the pop-up
      await form.getByRole('button', { name: 'Add a payment term' }).click();
      const newTerm = page.getByTestId('quick-payment-term');
      await newTerm.getByRole('textbox', { name: 'Name', exact: true }).fill(term);
      await newTerm.getByLabel('Due').selectOption('DAYS_EOM');
      await newTerm.getByLabel('Days').fill('15');
      await expect(newTerm.getByTestId('quick-term-meaning')).toHaveText('Due 15 days after the end of the invoice month');
      await newTerm.getByRole('button', { name: 'Add payment term' }).click();
      await expect(newTerm).toHaveCount(0);
      await expect(form.getByLabel('Terms', { exact: true })).toHaveValue(term);

      // The same name again is refused, in the pop-up; Escape closes it and keeps the form
      await form.getByRole('button', { name: 'Add a payment term' }).click();
      await newTerm.getByRole('textbox', { name: 'Name', exact: true }).fill(term.toUpperCase());
      await newTerm.getByRole('button', { name: 'Add payment term' }).click();
      await expect(newTerm.getByRole('alert')).toContainText('already a payment term');
      await page.keyboard.press('Escape');
      await expect(newTerm).toHaveCount(0);
      await expect(form.getByLabel('Terms', { exact: true })).toHaveValue(term);

      // Sales representative
      await form.getByRole('button', { name: 'Add a sales representative' }).click();
      const newRep = page.getByTestId('quick-sales-rep');
      await newRep.getByRole('textbox', { name: 'Name', exact: true }).fill(rep);
      await newRep.getByRole('button', { name: 'Add sales representative' }).click();
      await expect(newRep).toHaveCount(0);
      await expect(form.getByLabel('Sales rep', { exact: true })).toHaveValue(rep);

      // Save the order: everything added is on it
      await form.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await form.getByLabel('Product for line 1').fill(helmet.sku);
      await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: helmet.sku }).click();
      await form.getByLabel('Quantity for line 1').fill('1');
      await form.getByLabel('Unit price for line 1').fill('10');
      await form.getByRole('button', { name: 'Create Sale Order' }).click();
      await expect(page).toHaveURL(/\/sale-orders\/\d+$/);
      const id = Number(page.url().split('/').pop());
      await expect(page.getByTestId(`so-contact-${id}`)).toHaveText('Jo Park · jo@quick.test');

      const order = await (await api.http.get(`/api/sale-orders/${id}`)).json();
      expect(order).toMatchObject({ customerName: name, contactName: 'Jo Park', contactEmail: 'jo@quick.test', paymentTerm: term, salesRep: rep });
      // One customer of that name (the pop-up made it; saving the order did not make a second one), with both contacts
      const customers = await api.findCustomers(name);
      expect(customers).toHaveLength(1);
      expect(customers[0].contacts.map(c => c.name)).toEqual(['Sam Reed', 'Jo Park']);
      // The lists hold the new term and rep for the next order
      const terms = await (await api.http.get('/api/lists/payment-terms')).json();
      expect(terms).toContainEqual({ name: term, meaning: '15 days after the end of the invoice month' });
      expect(await (await api.http.get('/api/customers/sales-reps')).json()).toContain(rep);
    });
});

// The owner's rule (2026-11-10): on a creation page the date boxes start on today
test.describe('New documents start on today', () => {
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

  test('SO-016 a new sale has Ship by today and the quote date at today + the quote days; a new purchase order is required by today',
    { tag: ['@P2', '@sale-orders', '@purchase-orders'] },
    async ({ page, api, warehouses }) => {
      const today = iso(new Date());
      const days = (await (await api.http.get('/api/settings/organisation')).json()).quoteValidDays as number;
      const until = new Date();
      until.setDate(until.getDate() + days);
      const helmet = await api.createProduct();

      await page.goto('/sale-orders/new');
      const form = page.getByTestId('sale-order-form');
      await expect(form.getByLabel('Ship by')).toHaveValue(today);
      await expect(form.getByLabel('Quote valid until')).toHaveValue(iso(until));

      // Saved as shown
      await form.getByLabel('Customer *').fill('Date Default Customer');
      await form.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await form.getByLabel('Product for line 1').fill(helmet.sku);
      await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: helmet.sku }).click();
      await form.getByLabel('Quantity for line 1').fill('1');
      await form.getByLabel('Unit price for line 1').fill('10');
      await form.getByRole('button', { name: 'Create quote' }).click();
      await expect(page).toHaveURL(/\/sale-orders\/\d+$/);
      const order = await (await api.http.get(`/api/sale-orders/${page.url().split('/').pop()}`)).json();
      expect(order).toMatchObject({ shipBy: today, quoteValidUntil: iso(until) });

      // A new purchase order too
      await page.goto('/purchase-orders/new');
      await expect(page.getByLabel('Required by')).toHaveValue(today);
    });
});

test('SO-017 "+" on the customer and supplier forms; Settings renames a payment term where it is used and removes one nothing uses', { tag: ['@P2', '@customers', '@settings'] },
  async ({ page, api }) => {
    const term = `Net ${Date.now() % 100000}`;
    await page.goto('/customers/new');
    const form = page.getByTestId('customer-form');
    await form.getByRole('button', { name: 'New term' }).click();
    const dialog = page.getByTestId('quick-payment-term');
    await dialog.getByLabel('Name').fill(term);
    await dialog.getByLabel('Days').fill('45');
    await dialog.getByRole('button', { name: 'Add payment term' }).click();
    await expect(form.getByLabel('Payment Term')).toHaveValue(term);
    await form.getByRole('button', { name: 'New sales rep' }).click();
    const rep = `Rep ${Date.now() % 100000}`;
    await page.getByTestId('quick-sales-rep').getByLabel('Name').fill(rep);
    await page.getByTestId('quick-sales-rep').getByRole('button', { name: 'Add sales representative' }).click();
    await expect(form.getByLabel('Sales Representative')).toHaveValue(rep);

    await page.goto('/suppliers/new');
    await expect(page.getByTestId('supplier-form').getByRole('button', { name: 'New term' })).toBeVisible();

    // A supplier uses the term: renaming it in Settings renames it on the supplier
    const supplier = await (await api.http.post('/api/suppliers', { data: { name: `Term Supplier ${Date.now()}`, paymentTerm: term } })).json();
    await page.goto('/settings/organisation');
    const list = page.getByTestId('payment-terms-list');
    const row = list.getByRole('row').filter({ hasText: term });
    await expect(row).toContainText('45 days after the invoice date');
    await expect(row.getByRole('button', { name: 'Remove' })).toHaveCount(0);   // in use
    await row.getByRole('button', { name: 'Rename' }).click();
    await list.getByLabel(`New name for ${term}`).fill(`${term} days`);
    await list.getByRole('button', { name: 'Rename' }).first().click();
    await expect(list.getByRole('row').filter({ hasText: `${term} days` })).toBeVisible();
    await expect.poll(async () => (await (await api.http.get(`/api/suppliers/${supplier.id}`)).json()).paymentTerm).toBe(`${term} days`);

    // The sales rep nobody uses can be removed
    const reps = page.getByTestId('sales-reps-list');
    await reps.getByRole('row').filter({ hasText: rep }).getByRole('button', { name: 'Remove' }).click();
    await expect(reps.getByRole('row').filter({ hasText: rep })).toHaveCount(0);
  });
