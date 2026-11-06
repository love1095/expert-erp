import { test, expect } from '../../fixtures/test';

// Phase M: e-mail through the fake sender — settings, an invoice e-mailed from its page, the outbox and the document's log
test.describe('E-mail', () => {
  test('MAIL-001 the sender is set, an invoice is e-mailed with its PDF from its page, and shows in the outbox and on the invoice',
    { tag: ['@P1', '@settings', '@email'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      const customer = await api.createCustomer({ email: `buyer-${Date.now()}@example.test` });
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }], customer);
      await api.confirmSaleOrder(so.id);
      const invoice = await (await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } })).json();

      await page.goto('/settings/email');
      await page.getByRole('tab', { name: 'Settings' }).click();
      await expect(page.getByTestId('mail-settings')).toContainText('Test mode');
      await page.getByLabel('Sender name').fill('Expert Safety');
      await page.getByLabel('Sender address').fill('accounts@expertsafety.example');
      await page.getByTestId('mail-settings').getByRole('button', { name: 'Save' }).click();
      await expect(page.getByTestId('mail-settings').getByRole('status')).toHaveText('Saved');

      await page.goto(`/invoices/${invoice.id}`);
      await page.getByRole('button', { name: 'E-mail', exact: true }).click();
      const dialog = page.getByTestId('email-dialog');
      await expect(dialog.getByLabel('Subject')).toHaveValue(new RegExp(`^Invoice ${invoice.invoiceNumber}`));
      await expect(dialog.getByLabel('To')).toHaveValue(customer.contacts[0].email!);
      await dialog.getByRole('button', { name: 'Send' }).click();
      await expect(dialog.getByRole('status')).toContainText('In the outbox (test mode)');
      await dialog.getByRole('button', { name: 'Close' }).click();
      await expect(page.getByTestId('sent-emails')).toContainText(`Invoice ${invoice.invoiceNumber}`);

      await page.goto('/settings/email');
      await expect(page.getByTestId('outbox')).toContainText(`Invoice ${invoice.invoiceNumber}`);
    });
});
