import { test, expect } from '../../fixtures/test';

// Phase L5: the Xero API link against the fake Xero — connect, send through the link, history, payments from Xero
test.describe('Xero link', () => {
  test('XERO-002 connect to the fake Xero, switch to the link, an invoice and its contact are sent and shown in the history',
    { tag: ['@P1', '@finance', '@xero'] }, async ({ page, api, warehouses }) => {
      const start = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
      const settings = await api.http.put('/api/accounting/settings', { data: { exportStartDate: start, revenueAccount: '200', inventoryAccount: '631',
        cogsAccount: '310', discrepancyAccount: '310', purchaseChargesAccount: '425', customerCreditAccount: '802', supplierDepositsAccount: '615' } });
      expect(settings.ok()).toBe(true);
      const product = await api.createProduct();
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);
      const inv = await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } });
      expect(inv.ok()).toBe(true);
      const invoice = await inv.json();

      await page.goto('/finance/xero');
      await expect(page.getByTestId('xero-connection')).toContainText('Fake Xero (test mode)');
      await expect(page.getByTestId('xero-status')).toHaveText('Not connected');
      await page.getByRole('button', { name: 'Connect to Xero' }).click();
      await expect(page).toHaveURL(/\/finance\/xero$/);
      await expect(page.getByTestId('xero-status')).toContainText('Connected to Demo Company (UK) — fake');

      await page.getByRole('button', { name: 'Send through the Xero link' }).click();
      await expect(page.getByTestId('xero-link')).toContainText('Through this link');
      await page.getByTestId('xero-queue').getByRole('button', { name: 'Send now' }).click();
      await expect(page.getByTestId('xero-queue').getByRole('status')).toContainText('Sent 2, failed 0');
      await page.getByRole('tab', { name: 'History' }).click();
      await expect(page.getByTestId('xero-item-INVOICE')).toContainText(invoice.invoiceNumber);
      await expect(page.getByTestId('xero-item-CONTACT')).toBeVisible();

      await page.getByTestId('xero-payments').getByRole('button', { name: 'Pull now' }).click();
      await expect(page.getByTestId('xero-payments').getByRole('status')).toContainText('Recorded 0');
    });
});
