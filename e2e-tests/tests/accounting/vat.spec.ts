import { test, expect } from '../../fixtures/test';

// Phase K4: VAT returns like Xero's (Making Tax Digital needs the HMRC credentials on the server: not set here)
test.describe('VAT returns', () => {
  test('VAT-001 a period with spend money is reviewed in HMRC boxes, marked as filed, listed, and the period is locked',
    { tag: ['@P1', '@accounting'] }, async ({ page, api }) => {
      const now = new Date();
      const first = new Date(Date.UTC(now.getFullYear(), now.getMonth() - 1, 1));
      const last = new Date(Date.UTC(now.getFullYear(), now.getMonth(), 0));
      const iso = (d: Date) => d.toISOString().slice(0, 10);
      const spend = await api.http.post('/api/bank/transactions', { data: { kind: 'SPEND', date: iso(first), accountCode: '090', contact: 'Viking',
        taxInclusive: true, lines: [{ accountCode: '461', description: 'Paper', amount: 60, taxRule: '20% (VAT on Expenses)' }] } });
      expect(spend.ok()).toBe(true);

      await page.goto('/accounting/vat');
      await expect(page.getByTestId('mtd-connection')).toContainText('Not set up on this server yet');
      await page.getByLabel('From', { exact: true }).fill(iso(first));
      await page.getByLabel('To', { exact: true }).fill(iso(last));
      await page.getByRole('button', { name: 'Review', exact: true }).click();
      const review = page.getByTestId('vat-review');
      await expect(review.getByTestId('vat-box4')).toHaveText('£10.00');
      await expect(review.getByTestId('vat-box7')).toHaveText('£50.00');
      await review.getByRole('button', { name: 'Mark as filed (filed another way)' }).click();
      await expect(page.getByTestId('filed-vat-returns')).toContainText('Filed another way');

      const periods = await (await api.http.get('/api/ledger/periods')).json();
      expect(periods.lockDate).toBe(iso(last));
    });
});
