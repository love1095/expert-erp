import { test, expect } from '../../fixtures/test';

// Phase Q2: a supplier invoice typed by hand takes the BILL- series, not the sales invoices' INV-
test.describe('Supplier invoice numbers', () => {
  test('BILL-NUM-001 a hand-typed supplier invoice is numbered BILL-; the series shows in the organisation settings',
    { tag: ['@P2', '@settings'] }, async ({ page, api }) => {
      const res = await api.http.post('/api/invoices', { data: { type: 'PURCHASE', partyName: 'Portwest', issueDate: new Date().toISOString().slice(0, 10),
        items: [{ description: 'Repair', quantity: 1, unitPrice: 25 }] } });
      expect(res.ok()).toBeTruthy();
      expect((await res.json()).invoiceNumber).toMatch(/^BILL-\d+$/);

      await page.goto('/settings/organisation');
      await expect(page.getByLabel('Supplier invoice prefix (typed by hand)')).toHaveValue('BILL-');
      await expect(page.getByText(/Next: BILL-\d+/)).toBeVisible();
    });
});
