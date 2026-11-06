import { test, expect } from '../../fixtures/test';

// Phase K5: the accountant — CSV exports and the year-end pack; the Accountant role ready to give
test.describe('Accountant', () => {
  test('ACCT-001 the trial balance downloads as CSV, the year-end pack as a zip, and the Accountant role has the Adviser tick',
    { tag: ['@P2', '@accounting'] }, async ({ page, api }) => {
      const today = new Date().toISOString().slice(0, 10);
      await api.http.post('/api/ledger/manual-journals', { data: { date: today, narration: 'Cash sale', post: true, lines: [
        { accountCode: '090', debit: 80 }, { accountCode: '200', credit: 80 }] } });

      await page.goto('/accounting/trial-balance');
      await expect(page.getByTestId('tb-200')).toContainText('£80.00');
      const csv = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Export CSV' }).click();
      expect((await csv).suggestedFilename()).toBe(`Trial balance ${today}.csv`);

      await page.goto('/accounting/settings');
      const pack = page.waitForEvent('download');
      await page.getByTestId('year-end-pack').getByRole('button', { name: 'Download the year-end pack' }).click();
      expect((await pack).suggestedFilename()).toMatch(/^Year-end pack \d{4}-\d{2}-\d{2}\.zip$/);

      const roles = await (await api.http.get('/api/settings/roles')).json();
      const accountant = roles.find((r: { name: string }) => r.name === 'Accountant');
      expect(accountant.permissions).toContain('ADVISER');
    });
});
