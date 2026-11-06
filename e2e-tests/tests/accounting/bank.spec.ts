import { test, expect } from '../../fixtures/test';

// Phase K3: bank statement import and reconciliation like Xero's
test.describe('Bank reconciliation', () => {
  test('BANK-001 a statement is imported; its £60 line matches the spend money suggested; bank charges are created and reconciled',
    { tag: ['@P1', '@accounting'] }, async ({ page, api }) => {
      const d = new Date();
      const iso = d.toISOString().slice(0, 10);
      // The server's date (UTC), not the browser's: after midnight in Asia they differ
      const uk = `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`;
      const spend = await api.http.post('/api/bank/transactions', { data: { kind: 'SPEND', date: iso, accountCode: '090', contact: 'Viking',
        reference: 'INV-9', taxInclusive: true, lines: [{ accountCode: '461', description: 'Paper', amount: 60 }] } });
      expect(spend.ok()).toBe(true);

      await page.goto('/accounting/bank');
      const card = page.getByTestId('bank-090');
      await card.getByLabel('Statement file for 090').setInputFiles({ name: 'statement.csv', mimeType: 'text/csv', buffer: Buffer.from(
        `Date,Description,Paid in,Paid out\n${uk},VIKING DIRECT INV-9,,60.00\n${uk},BANK SERVICE CHARGE,,6.50\n`) });
      await card.getByRole('button', { name: 'Import a statement' }).click();
      await expect(card.getByTestId('statement-import')).toContainText('Preview (CSV): 2 lines');
      await card.getByRole('button', { name: 'Import 2 lines' }).click();
      await expect(card.getByTestId('statement-import')).toContainText('✓ Imported');

      await page.goto('/accounting/bank/090');
      const reconcile = page.getByTestId('reconcile');
      await expect(reconcile).toContainText('VIKING DIRECT INV-9');
      await reconcile.getByRole('button', { name: 'OK' }).click();
      await expect(reconcile).not.toContainText('VIKING DIRECT');

      await reconcile.getByLabel('Account').selectOption({ label: '404 - Bank Fees' });
      await reconcile.getByRole('button', { name: /Create spend money and reconcile/ }).click();
      await expect(page.getByTestId('all-reconciled')).toBeVisible();

      await page.getByRole('tab', { name: 'Reconciled statement lines' }).click();
      await expect(page.getByTestId('reconciled-lines').locator('tbody tr')).toHaveCount(2);
    });
});
