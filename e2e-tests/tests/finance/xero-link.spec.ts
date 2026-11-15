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

      // The old address still leads there
      await page.goto('/finance/xero');
      await expect(page).toHaveURL(/\/settings\/integrations\/xero$/);
      await expect(page.getByTestId('xero-connection')).toContainText('Fake Xero (test mode)');
      await expect(page.getByTestId('xero-status')).toHaveText('Not connected');
      await page.getByRole('button', { name: 'Connect to Xero' }).click();
      await expect(page).toHaveURL(/\/settings\/integrations\/xero$/);
      await expect(page.getByTestId('xero-status')).toContainText('Connected to Demo Company (UK) — fake');

      await page.getByRole('button', { name: 'Send through the Xero link' }).click();
      await expect(page.getByTestId('xero-link')).toContainText('Through this link');
      await page.getByRole('navigation', { name: 'Xero settings' }).getByRole('button', { name: 'Sync options' }).click();
      await page.getByTestId('xero-queue').getByRole('button', { name: 'Send now' }).click();
      await expect(page.getByTestId('xero-queue').getByRole('status')).toContainText('Sent 2, failed 0');
      await page.getByRole('tab', { name: 'History' }).click();
      await expect(page.getByTestId('xero-item-INVOICE')).toContainText(invoice.invoiceNumber);
      await expect(page.getByTestId('xero-item-CONTACT')).toBeVisible();

      await page.getByTestId('xero-payments').getByRole('button', { name: 'Pull now' }).click();
      await expect(page.getByTestId('xero-payments').getByRole('status')).toContainText('Recorded 0');
    });

  test('INT-001 Integrations is a catalogue: search and categories find Xero, its card opens the set-up page with its sections; the old menu entries are gone',
    { tag: ['@P1', '@integrations', '@xero'] }, async ({ page }) => {
      await page.goto('/settings/integrations');
      for (const key of ['xero', 'shopify', 'api']) await expect(page.getByTestId(`integration-${key}`)).toBeVisible();
      await expect(page.getByTestId('integration-xero')).toContainText('Not connected');

      await page.getByLabel('Search integrations').fill('xero');
      await expect(page.getByTestId('integration-shopify')).toHaveCount(0);
      await expect(page.getByTestId('integration-xero')).toBeVisible();
      await page.getByLabel('Search integrations').fill('');
      const categories = page.getByRole('navigation', { name: 'Integration categories' });
      await categories.getByRole('button', { name: 'eCommerce' }).click();
      await expect(page.getByTestId('integration-xero')).toHaveCount(0);
      await expect(page.getByTestId('integration-shopify')).toBeVisible();
      await categories.getByRole('button', { name: 'My integrations' }).click();
      await expect(page.getByTestId('integrations-empty')).toHaveText('Nothing is connected yet.');
      await categories.getByRole('button', { name: 'Accounting' }).click();
      await page.getByTestId('integration-xero').click();

      await expect(page).toHaveURL(/\/settings\/integrations\/xero$/);
      await expect(page.getByRole('heading', { name: 'Xero', exact: true })).toBeVisible();
      const sections = page.getByRole('navigation', { name: 'Xero settings' });
      for (const name of ['How the integration works', 'General settings', 'Sync options', 'Accounts and CSV files', 'Advanced settings']) {
        await expect(sections.getByRole('button', { name })).toBeVisible();
      }
      // Never the platform's own app
      await expect(page.getByText('Client ID')).toHaveCount(0);
      await sections.getByRole('button', { name: 'How the integration works' }).click();
      await expect(page.getByTestId('xero-how')).toContainText('Invoices and bills');
      await sections.getByRole('button', { name: 'Advanced settings' }).click();
      await expect(page.getByTestId('xero-advanced').getByRole('button', { name: 'Disconnect' })).toBeDisabled();
      await sections.getByRole('button', { name: 'Accounts and CSV files' }).click();
      await expect(page.getByTestId('account-mapping')).toBeVisible();

      // Finance no longer lists Xero; the old CSV address leads to the same page
      await expect(page.getByRole('link', { name: 'Xero export (CSV)' })).toHaveCount(0);
      await page.goto('/accounting-export');
      await expect(page).toHaveURL(/\/settings\/integrations\/xero\?section=accounts$/);
      await expect(page.getByTestId('accounting-export')).toBeVisible();
    });
});
