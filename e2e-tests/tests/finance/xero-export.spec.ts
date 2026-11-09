import { test, expect, clickAndConfirm } from '../../fixtures/test';
import { addUser, apiFor } from '../../fixtures/api';

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

// Phase H5 (spec §6): Xero CSV export with Cin7's account mapping, each document once
test.describe('Xero export', () => {
  test('XERO-001 set up the account mapping, export an invoice and its COGS journal once, undo it; STAFF cannot',
    { tag: ['@P1', '@finance'] }, async ({ page, api, warehouses, baseURL }) => {
      await page.goto('/accounting-export');
      await expect(page.getByTestId('export-missing')).toContainText('Export start date');
      await page.getByLabel('Export start date').fill(iso(new Date()));
      for (const [label, code] of [['Inventory control account', '631'], ['Cost of goods sold account', '310'],
        ['Inventory discrepancy account', '495'], ['Default revenue account', '200'], ['Purchase charges account', '407']]) {
        await page.getByLabel(label, { exact: true }).fill(code);
      }
      await page.getByTestId('account-mapping').getByRole('button', { name: 'Save' }).click();
      await expect(page.getByTestId('account-mapping').getByRole('status')).toHaveText('Saved');

      const helmet = await api.createProduct({ costPrice: 6 });
      await api.http.post('/api/stock-movements', { data: { productId: helmet.id, warehouseId: warehouses.main.id, quantity: 5, type: 'ADJUSTMENT_IN' } });
      const so = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect((await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } })).ok()).toBe(true);

      await page.reload();
      await expect(page.getByTestId('export-count-INVOICE')).toContainText('1');
      await expect(page.getByTestId('export-count-JOURNAL')).not.toContainText(' 0');
      const download = page.waitForEvent('download');
      await clickAndConfirm(page, page.getByRole('button', { name: 'Export for Xero' }));
      expect((await download).suggestedFilename()).toMatch(/^Xero_export_.*\.zip$/);
      await expect(page.getByTestId('export-count-INVOICE')).toContainText('0');
      await expect(page.getByRole('button', { name: 'Export for Xero' })).toBeDisabled();

      await page.getByTestId('export-batches').getByRole('button', { name: 'Undo' }).click();
      await expect(page.getByTestId('export-count-INVOICE')).toContainText('1');

      const staff = await apiFor(baseURL!, await addUser(api, 'STAFF'));
      try {
        expect((await staff.http.post('/api/accounting/export')).status()).toBe(403);
      } finally {
        await staff.dispose();
      }
    });
});
