import { test, expect } from '../../fixtures/test';

// Phase J: Cin7's sale task CSV import (open orders at cutover), then the go-live check
test.describe('Cutover', () => {
  test('CUTOVER-001 an open Cin7 order is previewed, imported with its SO number, refused the second time; the go-live check counts it',
    { tag: ['@P1', '@cutover'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      await api.addStock(product, warehouses.main, 10);
      const customer = await api.createCustomer();
      const number = `SO-E2E${Date.now()}`;
      const csv = [
        'RecordType*,CustomerName*,InvoiceNumber*,Reference/Comment/Note,Product*,Quantity*,Price/Amount*,Total*,InvoiceDate*/ExpireDate,TaxInclusive*',
        `Invoice,${customer.name},${number},CUST-PO-1,,,,,30/10/2026,FALSE`,
        `InvoiceLines,${customer.name},${number},,${product.sku},3,10.00,30.00,,`,
      ].join('\n');
      const file = { name: 'SaleTasks.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) };

      await page.goto('/settings/data-import');
      const sales = page.getByTestId('import-sales');
      await sales.getByLabel('Warehouse').selectOption({ label: warehouses.main.name });
      // An open order at cutover: no invoice yet, nothing shipped, not sent to Xero
      await sales.getByLabel('Invoice status').selectOption('NONE');
      await sales.getByLabel('Pick, pack and ship processing mode').selectOption('NO_PICKING');
      await sales.getByLabel('Export imported tasks to Xero').uncheck();
      await sales.getByLabel('CSV file').setInputFiles(file);
      await sales.getByRole('button', { name: 'Preview' }).click();
      const result = page.getByTestId('import-result');
      await expect(result).toContainText('Preview: 1 sales');
      await expect(result).toContainText(number);
      await result.getByRole('button', { name: 'Import 1 sales' }).click();
      await expect(result).toContainText('✓ Imported: 1 sales');

      const orders = await (await api.http.get('/api/sale-orders', { params: { q: number } })).json();
      expect(orders.content[0].orderNumber).toBe(number);
      expect(orders.content[0].status).toBe('CONFIRMED');

      // The same file again: refused, nothing doubled
      await sales.getByLabel('CSV file').setInputFiles({ ...file, name: 'SaleTasks-again.csv' });
      await sales.getByRole('button', { name: 'Preview' }).click();
      await expect(page.getByTestId('import-result')).toContainText('already exists');

      await page.getByRole('tab', { name: 'Logs' }).click();
      await expect(page.getByTestId('import-logs')).toContainText('SaleTasks.csv');

      await page.goto('/settings/go-live');
      const openOrders = page.getByTestId('figure-open-sales');
      await expect(openOrders).toBeVisible();
      const erp = (await openOrders.locator('td').nth(1).textContent())!.trim();
      await openOrders.getByRole('textbox').fill(erp);
      await expect(openOrders).toContainText('✓ matches');
      await openOrders.getByRole('textbox').fill('0');
      await expect(openOrders).not.toContainText('✓ matches');

      const health = await api.http.get('/api/health');
      expect(health.ok()).toBe(true);
    });
});
