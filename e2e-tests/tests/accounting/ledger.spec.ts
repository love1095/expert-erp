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
      await page.getByRole('link', { name: '+ New journal' }).click();
      await expect(page).toHaveURL(/\/accounting\/manual-journals\/new$/);
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
      // Saving opens the journal's own page (review R2)
      await expect(page).toHaveURL(/\/accounting\/manual-journals\/\d+$/, { timeout: 30_000 });
      await expect(page.getByTestId('journal-status')).toHaveText('Posted');
      await expect(page.getByTestId('journal-lines')).toContainText('£120.00');

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

  test('LEDGER-002 a sale journal shows as net profit and as current year earnings; a lock date stops a journal in the locked month',
    { tag: ['@P1', '@accounting'] }, async ({ page, api }) => {
      const today = new Date().toISOString().slice(0, 10);
      const res = await api.http.post('/api/ledger/manual-journals', { data: { date: today, narration: 'Cash sale', post: true, lines: [
        { accountCode: '090', debit: 500 }, { accountCode: '200', credit: 500 }] } });
      expect(res.ok()).toBe(true);

      await page.goto('/accounting/profit-and-loss');
      await expect(page.getByTestId('profit-and-loss')).toContainText('Sales');
      await expect(page.getByTestId('total-net-profit')).toContainText('£500.00');

      await page.goto('/accounting/balance-sheet');
      await expect(page.getByTestId('total-net-assets')).toContainText('£500.00');
      await expect(page.getByTestId('balance-sheet')).toContainText('Current Year Earnings');

      await page.goto('/accounting/settings');
      await page.getByLabel('Lock date for all users', { exact: true }).fill(today);
      await page.getByLabel('Lock date for all users except advisers').fill(today);
      await page.getByRole('button', { name: 'Save financial settings' }).click();
      await expect(page.getByTestId('ledger-periods').getByRole('status')).toHaveText('Saved');
      const locked = await api.http.post('/api/ledger/manual-journals', { data: { date: today, narration: 'Too late', post: true, lines: [
        { accountCode: '090', debit: 1 }, { accountCode: '200', credit: 1 }] } });
      expect(locked.ok()).toBe(false);
      expect((await locked.json()).error).toContain('locked');
    });
});
