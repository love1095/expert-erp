import { test, expect } from '../../fixtures/test';

// A 1 × 1 PNG for the logo
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

// Phase D5: documents as PDF (docs/phase-d-sales.md)
test.describe('Documents', () => {
  test('DOC-001 company details and logo, then a sales order printed from its page and two from the list', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      // Company details on documents
      await page.goto('/settings/organisation');
      const card = page.getByTestId('company-details');
      await card.getByLabel('Company name on documents').fill('Expert Safety Ltd');
      await card.getByLabel('VAT number').fill('GB 999 9999 73');
      await card.getByLabel('Address').fill('Unit 1, Mill Lane\nRochdale\nOL11 1NS');
      await card.getByLabel('Bank details (printed on invoices)').fill('Sort code 00-00-00, account 12345678');
      await card.getByRole('button', { name: 'Save company details' }).click();
      await expect(card.getByRole('status')).toContainText('Saved');
      await card.getByLabel('Logo file').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: PNG });
      await expect(card.getByTestId('company-logo')).toBeVisible();
      await card.getByLabel('Logo file').setInputFiles({ name: 'logo.txt', mimeType: 'text/plain', buffer: Buffer.from('nope') });
      await expect(card.getByRole('status')).toContainText('PNG or JPEG');
      await page.reload();
      await expect(page.getByLabel('VAT number')).toHaveValue('GB 999 9999 73');

      const product = await api.createProduct({ sellPrice: 10 });
      const first = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
      const second = await api.createSaleOrder(warehouses.main, [{ product, quantity: 3 }]);

      // Print on the order's page: the PDF opens in a new tab
      await page.goto(`/sale-orders/${first.id}`);
      const pdf = page.waitForResponse(r => r.url().includes(`/api/sale-orders/${first.id}/pdf`));
      const tab = page.waitForEvent('popup');
      await page.getByTestId('print-document').click();
      const res = await pdf;
      expect(res.status()).toBe(200);
      expect(res.headers()['content-type']).toContain('application/pdf');
      expect(res.headers()['content-disposition']).toContain(`${first.orderNumber}.pdf`);
      expect((await res.body()).subarray(0, 5).toString()).toBe('%PDF-');
      await (await tab).close();

      // Print on ticked rows: one PDF with both
      await page.goto('/sale-orders');
      await page.getByTestId(`so-select-${first.id}`).check();
      await page.getByTestId(`so-select-${second.id}`).check();
      const both = page.waitForResponse(r => r.url().includes('/api/documents/sale-orders/pdf'));
      const tab2 = page.waitForEvent('popup');
      await page.getByTestId('bulk-actions').getByRole('button', { name: 'Print' }).click();
      const bothRes = await both;
      expect(bothRes.status()).toBe(200);
      // In list order (newest first)
      expect(bothRes.url()).toContain(`ids=${second.id},${first.id}`);
      await (await tab2).close();
    });
});
