import { test, expect } from '../../fixtures/test';

// Phase K1: the ERP's own accounting (like Xero): chart of accounts, manual journal, trial balance, account transactions
test.describe('General ledger', () => {
  test('LEDGER-001 the standard chart is there; a posted manual journal shows in a balanced trial balance and in the account; voiding reverses it',
    { tag: ['@P1', '@accounting'] }, async ({ page }) => {
      await page.goto('/accounting/chart');
      const chart = page.getByTestId('chart-of-accounts');
      await expect(chart).toContainText('Accounts Receivable');
      await expect(chart).toContainText('Inventory Control');

      await page.goto('/accounting/manual-journals');
      await page.getByRole('button', { name: '+ New journal' }).click();
      await page.getByLabel('Narration').fill('Advert in Safety Weekly');
      await page.getByLabel('Line 1 account').selectOption({ label: '400 - Advertising & Marketing' });
      await page.getByLabel('Line 1 debit').fill('120');
      await page.getByLabel('Line 2 account').selectOption({ label: '090 - Business Bank Account' });
      await page.getByLabel('Line 2 credit').fill('100');
      await expect(page.getByTestId('journal-form')).toContainText('Out of balance');
      await page.getByRole('button', { name: 'Post' }).click();
      await expect(page.getByTestId('journal-form').getByRole('alert')).toContainText('must be equal');
      await page.getByLabel('Line 2 credit').fill('120');
      await page.getByRole('button', { name: 'Post' }).click();
      await expect(page.getByTestId('manual-journals')).toContainText('Posted');

      await page.goto('/accounting/trial-balance');
      await expect(page.getByTestId('tb-400')).toContainText('£120.00');
      await expect(page.getByTestId('tb-debits')).toHaveText((await page.getByTestId('tb-credits').textContent())!);

      await page.getByTestId('tb-090').getByRole('link').click();
      await expect(page.getByTestId('account-transactions')).toContainText('Manual journal');
      await expect(page.getByTestId('closing')).toContainText('-£120.00');

      await page.goto('/accounting/manual-journals');
      await page.getByTestId('manual-journals').getByRole('button', { name: 'Void' }).click();
      await expect(page.getByTestId('manual-journals')).toContainText('Voided');
      await page.goto('/accounting/trial-balance');
      await expect(page.getByTestId('tb-400')).toHaveCount(0);
    });
});
