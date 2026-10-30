import { test, expect } from '../../fixtures/test';

// Phase D3: a quote is the first stage of the same sale document (docs/phase-d-sales.md)
test.describe('Quotes', () => {
  test('SO-012 a quote is made, revised and converted into the order with the same number', { tag: ['@P0', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const customer = await api.createCustomer();
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);

      await page.goto('/sale-orders');
      await page.getByRole('button', { name: '+ New SO' }).click();
      const form = page.getByTestId('sale-order-form');
      await form.getByLabel('Customer *').fill(customer.name);
      await form.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await form.getByLabel('Product for line 1').fill(helmet.sku);
      await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: helmet.sku }).click();
      await form.getByLabel('Quantity for line 1').fill('4');
      await form.getByLabel('Unit price for line 1').fill('10');
      await expect(form.getByTestId('so-form-totals')).toContainText('£48.00');
      await form.getByRole('button', { name: 'Create quote' }).click();

      const panel = page.locator('[data-testid^="so-panel-"]');
      await expect(page.locator('[data-testid^="so-valid-until-"]')).toBeVisible();
      const number = (await page.locator('[data-testid^="so-details-"]').textContent())!.match(/SO-\d+/)![0];
      // A quote reserves nothing
      expect((await api.http.get(`/api/products/${helmet.id}`).then(r => r.json())).reservedStock).toBe(0);

      // Revise it: 5 instead of 4
      await panel.getByRole('button', { name: 'Edit' }).click();
      await expect(form.getByLabel('Product for line 1')).toHaveValue(new RegExp(helmet.sku));
      await form.getByLabel('Quantity for line 1').fill('5');
      await form.getByRole('button', { name: 'Save changes' }).click();
      await expect(page.locator('[data-testid^="so-totals-"]')).toContainText('£60.00');

      await panel.getByRole('button', { name: 'Convert to order' }).click();
      await expect(panel.getByRole('button', { name: 'Confirm order' })).toBeVisible();
      await expect(page.locator('[data-testid^="so-details-"]')).toContainText(number);

      // The list shows it under draft orders, no longer under quotes
      await page.goto('/sale-orders');
      await page.getByLabel('Show').selectOption('QUOTE');
      await expect(page.getByText(number)).toHaveCount(0);
      await page.getByLabel('Show').selectOption('DRAFT');
      await expect(page.getByText(number)).toBeVisible();
    });

  test('SO-013 an expired quote cannot be converted until renewed; a quote can be rejected', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      const quote = async (validUntil?: string) => (await api.http.post('/api/sale-orders', {
        data: { customerName: 'E2E Customer', warehouseId: warehouses.main.id, quote: true, quoteValidUntil: validUntil,
          lines: [{ productId: helmet.id, quantity: 1, unitPrice: 10 }] },
      })).json();
      const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
      const expired = await quote(yesterday);
      const other = await quote();

      expect((await api.http.post(`/api/sale-orders/${expired.id}/convert`)).status()).toBe(422);

      await page.goto('/sale-orders');
      await expect(page.getByTestId(`so-row-${expired.id}`)).toContainText('Expired');
      await page.getByTestId(`so-row-${expired.id}`).getByRole('button', { name: 'Manage' }).click();
      await expect(page.getByTestId(`so-panel-${expired.id}`).getByRole('button', { name: 'Convert to order' })).toBeDisabled();

      await page.getByTestId(`so-row-${other.id}`).getByRole('button', { name: 'Manage' }).click();
      await page.getByTestId(`so-panel-${other.id}`).getByRole('button', { name: 'Reject quote' }).click();
      await expect(page.getByTestId(`so-row-${other.id}`)).toContainText('REJECTED');
    });
});
