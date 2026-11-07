import { test, expect } from '../../fixtures/test';

// Phase Q6: the P&L and the Balance Sheet export as PDFs
test.describe('Financial statements as PDF', () => {
  test('LEDGER-PDF-001 the profit and loss and the balance sheet download as PDFs', { tag: ['@P2', '@accounting'] }, async ({ page, api }) => {
    const today = new Date().toISOString().slice(0, 10);
    const pl = await api.http.get('/api/ledger/export/profit-and-loss.pdf', { params: { from: `${today.slice(0, 4)}-01-01`, to: today } });
    expect(pl.ok()).toBeTruthy();
    expect(pl.headers()['content-type']).toContain('application/pdf');
    expect((await pl.body()).subarray(0, 4).toString()).toBe('%PDF');
    const bs = await api.http.get('/api/ledger/export/balance-sheet.pdf', { params: { date: today } });
    expect((await bs.body()).subarray(0, 4).toString()).toBe('%PDF');

    await page.goto('/accounting/profit-and-loss');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export PDF' }).click();
    expect((await download).suggestedFilename()).toMatch(/^Profit and loss .*\.pdf$/);
  });
});
