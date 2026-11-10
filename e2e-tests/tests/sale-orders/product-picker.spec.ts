import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';

/** Review R9: the product box on order lines is a selection list like Cin7's, not a search box. */
test.describe('Product selection list', () => {
  test('PICK-001 a click opens the list A-Z with no typing; scrolling loads more; typing narrows; the keyboard picks', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api }) => {
      const tag = uid().toUpperCase();
      // 25 products: more than one page of 20
      for (let i = 0; i < 25; i++) await api.createProduct({ sku: `AA${tag}-${String(i).padStart(2, '0')}`, name: `Picker item ${i}` });

      await page.goto('/sale-orders/new');
      const box = page.getByLabel('Product for line 1', { exact: true });
      await expect(box).toHaveAttribute('placeholder', 'Select a product');
      await box.click();
      const list = page.getByRole('listbox', { name: 'Product for line 1 matches' });
      await expect(list.getByRole('option')).toHaveCount(20);
      await expect(list.getByRole('option').first()).toContainText(`AA${tag}-00`);
      // The sale list shows what is available and the selling price
      await expect(page.getByText('Available', { exact: true })).toBeVisible();

      // Scrolling to the end loads the next 20 (here: the last 5)
      await list.evaluate(el => el.scrollTo(0, el.scrollHeight));
      await expect(list.getByRole('option')).toHaveCount(25);

      // Typing narrows; arrow down and Enter pick
      await box.fill(`${tag}-1`);
      await expect(list.getByRole('option').first()).toContainText(`AA${tag}-10`);
      await box.press('ArrowDown');
      await box.press('Enter');
      await expect(box).toHaveValue(`AA${tag}-11 — Picker item 11`);
      await expect(list).toHaveCount(0);

      // Esc closes and keeps the choice
      await box.click();
      await expect(list).toBeVisible();
      await box.press('Escape');
      await expect(list).toHaveCount(0);
      await expect(box).toHaveValue(`AA${tag}-11 — Picker item 11`);
    });

  test('PICK-002 on a purchase order the list shows on hand, on order and cost, the supplier\'s products first', { tag: ['@P1', '@purchase-orders'] },
    async ({ page, api, warehouses }) => {
      const supplier = await (await api.http.post('/api/suppliers', { data: { name: `Portwest ${uid()}` } })).json();
      const theirs = await api.createProduct({ name: 'Their vest', costPrice: 4, defaultSupplierId: supplier.id });
      await api.createPurchaseOrder(warehouses.main, [{ product: theirs, quantity: 7 }]);

      await page.goto('/purchase-orders/new');
      await page.getByLabel('Supplier *').fill(supplier.name);
      await page.getByLabel('Product for line 1').click();
      const list = page.getByRole('listbox', { name: 'Product for line 1 matches' });
      const first = list.getByRole('option').first();
      await expect(first).toContainText(theirs.sku);
      await expect(first).toContainText("Supplier's");
      await expect(first).toContainText('£4.00');
      await expect(first.getByTestId('picker-on-order')).toHaveText('7');
      await expect(page.getByText('On order', { exact: true })).toBeVisible();
    });

  test('PICK-003 the customer box opens the app\'s own list under it: the name, the e-mail small under it', { tag: ['@P2', '@sale-orders', '@customers'] },
    async ({ page, api }) => {
      const customer = await api.createCustomer({ name: `Acme ${uid()}`, email: 'buyer@acme.test' });
      await page.goto('/sale-orders/new');
      await page.getByLabel('Customer *').click();
      const list = page.getByRole('listbox', { name: 'Customers' });
      const option = list.getByRole('option').filter({ hasText: customer.name });
      await expect(option).toContainText('buyer@acme.test');
      await option.click();
      await expect(page.getByLabel('Customer *')).toHaveValue(customer.name);
      await expect(list).toHaveCount(0);
      // A new name can still be typed
      await page.getByLabel('Customer *').fill('Walk-in cash sale');
      await expect(page.getByText('New customer — will be added to Customers')).toBeVisible();
    });

  test('PICK-004 the sales rep is picked from a list of the names used on customers, like Cin7', { tag: ['@P2', '@sale-orders', '@customers'] },
    async ({ page, api }) => {
      const rep = `Rep ${uid()}`;
      await api.http.post('/api/customers', { data: { name: `Acme ${uid()}`, salesRepresentative: rep } });
      await page.goto('/sale-orders/new');
      await page.getByLabel('Sales rep', { exact: true }).click();
      const list = page.getByRole('listbox', { name: 'Sales reps' });
      await expect(list).toContainText('Web Sales');
      await list.getByRole('option').filter({ hasText: rep }).click();
      await expect(page.getByLabel('Sales rep', { exact: true })).toHaveValue(rep);

      // The customer form uses the same list
      await page.goto('/customers/new');
      await page.getByLabel('Sales Representative').click();
      await expect(page.getByRole('listbox', { name: 'Sales reps' })).toContainText(rep);
    });

  test('PICK-005 terms are picked from a list that says what each one means for the due date', { tag: ['@P2', '@sale-orders'] },
    async ({ page, api }) => {
      await api.http.post('/api/customers', { data: { name: `Acme ${uid()}`, paymentTerm: '30 Day EOM' } });
      await page.goto('/sale-orders/new');
      await page.getByLabel('Terms', { exact: true }).click();
      const option = page.getByRole('listbox', { name: 'Payment terms' }).getByRole('option').filter({ hasText: '30 Day EOM' });
      await expect(option).toContainText('30 days after the end of the invoice month');
      await option.click();
      await expect(page.getByLabel('Terms', { exact: true })).toHaveValue('30 Day EOM');
    });
});
