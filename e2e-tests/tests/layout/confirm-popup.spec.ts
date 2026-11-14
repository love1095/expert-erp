import { test, expect } from '../../fixtures/test';

/**
 * Review R7 / rule D2: actions that delete, cancel, move stock or money, or send something outside ask first in the
 * app's own pop-up. Cancel, Escape and a click outside change nothing; only the action button does it.
 */
test.describe('Ask before doing', () => {
  test.use({ autoConfirm: false });

  test('CONF-001 deleting a customer asks in a red pop-up; Cancel, Escape and a click outside keep it; Delete removes it', { tag: ['@P1', '@layout', '@customers'] },
    async ({ page, api }) => {
      const customer = await api.createCustomer();
      await page.goto('/customers');
      const row = page.getByTestId(`customer-row-${customer.id}`);
      const dialog = page.getByRole('alertdialog');

      await row.getByRole('button', { name: 'Delete' }).click();
      await expect(dialog).toContainText(`Delete customer "${customer.name}"?`);
      // Dangerous: red button, and Cancel is focused so Enter does not delete
      await expect(dialog.getByTestId('confirm-ok')).toHaveText('Delete');
      await expect(dialog.getByTestId('confirm-ok')).toHaveClass(/bg-red-600/);
      await expect(dialog.getByTestId('confirm-cancel')).toBeFocused();
      await dialog.getByTestId('confirm-cancel').click();
      await expect(dialog).toHaveCount(0);
      await expect(row).toBeVisible();

      await row.getByRole('button', { name: 'Delete' }).click();
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);

      await row.getByRole('button', { name: 'Delete' }).click();
      await page.mouse.click(5, 5);   // outside the pop-up
      await expect(dialog).toHaveCount(0);
      expect((await api.http.get(`/api/customers/${customer.id}`)).status()).toBe(200);

      await row.getByRole('button', { name: 'Delete' }).click();
      await dialog.getByTestId('confirm-ok').click();
      await expect(row).toHaveCount(0);
      expect((await api.http.get(`/api/customers/${customer.id}`)).status()).toBe(404);
    });

  test('CONF-002 confirming a sale order asks with the order number; Cancel leaves it a draft', { tag: ['@P1', '@layout', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      await api.addStock(product, warehouses.main, 5);
      const order = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
      await page.goto(`/sale-orders/${order.id}`);
      const dialog = page.getByRole('alertdialog');

      await page.getByRole('button', { name: 'Confirm order' }).click();
      await expect(dialog).toContainText(`Confirm ${order.orderNumber}?`);
      // Not dangerous: the action button is focused, in the normal colour
      await expect(dialog.getByTestId('confirm-ok')).toBeFocused();
      await expect(dialog.getByTestId('confirm-ok')).not.toHaveClass(/bg-red-600/);
      await dialog.getByTestId('confirm-cancel').click();
      expect((await (await api.http.get(`/api/sale-orders/${order.id}`)).json()).status).toBe('DRAFT');

      await page.getByRole('button', { name: 'Confirm order' }).click();
      await dialog.getByTestId('confirm-ok').click();
      await expect.poll(async () => (await (await api.http.get(`/api/sale-orders/${order.id}`)).json()).status).toBe('CONFIRMED');
    });
  test('CONF-010 every save asks first (owner\'s choice): Cancel saves nothing, the button\'s words confirm it', { tag: ['@P1', '@layout', '@suppliers'] },
    async ({ page, api }) => {
      const name = `Asked Supplier ${Date.now()}`;
      await page.goto('/suppliers/new');
      await page.getByLabel('Name *').fill(name);
      const dialog = page.getByRole('alertdialog');

      await page.getByRole('button', { name: 'Create Supplier' }).click();
      await expect(dialog).toContainText('Create Supplier?');
      await dialog.getByTestId('confirm-cancel').click();
      await expect(dialog).toHaveCount(0);
      const found = async () => (await (await api.http.get(`/api/suppliers?search=${encodeURIComponent(name)}`)).json()).content
        .filter((s: { name: string }) => s.name === name).length;
      expect(await found()).toBe(0);

      await page.getByRole('button', { name: 'Create Supplier' }).click();
      await expect(dialog.getByTestId('confirm-ok')).toHaveText('Create Supplier');
      await dialog.getByTestId('confirm-ok').click();
      await expect.poll(found).toBe(1);
    });

  test('CONF-011 a reason is asked in the pop-up, not the browser\'s box: putting a customer on hold waits for it', { tag: ['@P1', '@layout', '@customers'] },
    async ({ page, api }) => {
      const customer = await api.createCustomer();
      await page.goto(`/customers/${customer.id}`);
      const dialog = page.getByRole('alertdialog');
      await page.getByRole('button', { name: 'Put on hold' }).click();
      await expect(dialog.getByTestId('confirm-ok')).toBeDisabled();
      await dialog.getByLabel('Why is the customer put on hold?').fill('Two invoices over 90 days');
      await dialog.getByTestId('confirm-ok').click();
      await expect(page.getByText('Two invoices over 90 days').first()).toBeVisible();
    });
});
