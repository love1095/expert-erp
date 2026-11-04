import { test, expect } from '../../fixtures/test';

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

// Phase H3 (spec §6): the VAT return in HMRC's nine boxes from the invoices and bills of the period
test.describe('VAT return', () => {
  test('VAT-001 a sale, a bill and import VAT fill boxes 1, 4, 5, 6 and 7', { tag: ['@P1', '@finance'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      // A purchase: 2 × £10 + 20 % VAT, and £5 import VAT (Full VAT: all VAT)
      const po = await (await api.http.post('/api/purchase-orders', { data: { supplierName: `VAT Supplier ${Date.now()}`,
        warehouseId: warehouses.main.id, ordered: true, lines: [{ productId: helmet.id, quantity: 2, unitCost: 10 }] } })).json();
      expect((await api.http.post(`/api/purchase-orders/${po.id}/receive`)).ok()).toBe(true);
      const bill = await (await api.http.post(`/api/purchase-orders/${po.id}/bills`, { data: { supplierInvoiceNumber: `C79-${Date.now()}`,
        charges: [{ description: 'Import VAT (C79)', unitPrice: 5, taxRule: 'Full VAT' }] } })).json();
      expect(bill.totalAmount).toBe(29);

      // A sale: 2 × £10 + 20 % VAT, shipped and invoiced
      const so = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect((await api.http.post(`/api/sale-orders/${so.id}/invoices`, { data: { issue: true } })).ok()).toBe(true);

      await page.goto('/vat-return');
      const today = new Date();
      await page.getByLabel('Period from', { exact: true }).fill(iso(new Date(today.getFullYear(), today.getMonth(), 1)));
      await page.getByLabel('Period to', { exact: true }).fill(iso(today));
      await expect(page.getByTestId('vat-box1')).toContainText('£4.00');
      await expect(page.getByTestId('vat-box4')).toContainText('£9.00');     // 4 on the bill + 5 import VAT
      await expect(page.getByTestId('vat-box5')).toContainText('£5.00');
      await expect(page.getByTestId('vat-result')).toHaveText('HMRC owes you £5.00');
      await expect(page.getByTestId('vat-box6')).toContainText('£20');
      await expect(page.getByTestId('vat-box7')).toContainText('£20');
      await expect(page.getByTestId('vat-rates')).toContainText('All VAT (import)');
      await expect(page.getByTestId('vat-documents')).toContainText(bill.invoiceNumber);
    });
});
