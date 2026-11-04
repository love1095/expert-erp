import { test, expect } from '../../fixtures/test';

// Phase H2 (spec §6): supplier credit note sending goods back; a deposit used on the next bill; aged creditors
test.describe('Purchase ledger', () => {
  test('PLEDGER-001 a credit note sends goods back and pays off the bill; a deposit pays the next bill', { tag: ['@P1', '@purchase-orders'] },
    async ({ page, api, warehouses }) => {
      const vest = await api.createProduct();
      const supplierName = `Ledger Supplier ${Date.now()}`;
      // Ordered, received and billed: 4 × £10 + VAT = £48
      const order = async (qty: number, number: string) => {
        const po = await (await api.http.post('/api/purchase-orders', { data: { supplierName, warehouseId: warehouses.main.id, ordered: true,
          lines: [{ productId: vest.id, quantity: qty, unitCost: 10 }] } })).json();
        expect((await api.http.post(`/api/purchase-orders/${po.id}/receive`)).ok()).toBe(true);
        const bill = await (await api.http.post(`/api/purchase-orders/${po.id}/bills`, { data: { supplierInvoiceNumber: number } })).json();
        return { po, bill };
      };
      const first = await order(4, `B-${Date.now()}`);
      expect(first.bill.totalAmount).toBe(48);

      // Supplier credit note on the bill: 1 vest sent back, £12 off what is due
      await page.goto(`/invoices/${first.bill.id}`);
      await page.getByRole('button', { name: 'Supplier credit note' }).click();
      const form = page.getByTestId('supplier-credit-note-form');
      await form.getByLabel('Supplier credit note number').fill('SCN-77');
      await form.getByLabel(/Credit quantity for/).fill('1');
      await form.getByLabel(/Send back/).check();
      await form.getByRole('button', { name: 'Create supplier credit note' }).click();
      await expect(page.getByRole('heading', { name: /SCN-77/ })).toBeVisible();
      expect((await api.http.get(`/api/invoices/${first.bill.id}`).then(r => r.json())).amountPaid).toBe(12);
      expect((await api.http.get(`/api/products/${vest.id}`).then(r => r.json())).totalStock).toBe(3);

      // A £20 deposit on the next order, used on its bill
      const second = await order(2, `B2-${Date.now()}`);
      await page.goto(`/purchase-orders/${second.po.id}`);
      const deposits = page.getByTestId('supplier-deposits');
      await deposits.getByTestId('open-supplier-deposit').click();
      await deposits.getByLabel('Deposit amount').fill('20');
      await deposits.getByLabel('Deposit reference').fill('Proforma 88');
      await deposits.getByRole('button', { name: 'Record deposit' }).click();
      await expect(deposits.getByTestId('supplier-deposit-available')).toHaveText('£20.00');
      await page.goto(`/invoices/${second.bill.id}`);
      await page.getByTestId('use-supplier-credit').click();
      await expect(page.getByText(/GBP 4\.00 due/)).toBeVisible();

      // Aged creditors: £36 + £4 owed
      await page.goto('/aged-creditors');
      const row = page.locator('[data-testid^="aged-row-"]', { hasText: supplierName });
      await expect(row).toContainText('£40.00');
    });
});
