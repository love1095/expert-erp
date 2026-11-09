import { test, expect } from '../../fixtures/test';
import { logInAs } from '../../fixtures/test';

/** Manage dashboard, like Cin7's: each user chooses which parts show, their order, folded or full width; saved on the server. */
test.describe('Manage dashboard', () => {
  test('DASH-002 a part hidden in Manage dashboard stays hidden after a reload and on another browser; reset brings it back', { tag: ['@P1', '@dashboard'] },
    async ({ page, browser, session, baseURL, api }) => {
      await page.goto('/');
      const reorder = page.getByTestId('dash-part-reorder');
      await expect(reorder).toBeVisible();

      await page.getByRole('button', { name: 'Manage dashboard' }).click();
      const manage = page.getByTestId('manage-dashboard');
      await manage.getByTestId('manage-part-reorder').getByRole('checkbox').uncheck();
      await expect(reorder).toHaveCount(0);
      // Recent sale orders moved to the top of the list
      // It is the last of the 9 parts: 8 steps up
      for (let i = 0; i < 8; i++) await manage.getByRole('button', { name: 'Move up: Recent sale orders' }).click();
      await expect(manage.getByRole('button', { name: 'Move up: Recent sale orders' })).toBeDisabled();
      await manage.getByRole('button', { name: 'Done' }).click();
      await expect(page.locator('[data-testid^="dash-part-"]').first()).toHaveAttribute('data-testid', 'dash-part-recent-orders');

      // Saved on the server: after a reload, and in a fresh browser for the same user
      await expect.poll(async () => {
        const saved = await (await api.http.get('/api/dashboard/layout')).json();
        return [saved.source, saved.layout?.parts?.[0]?.key, saved.layout?.parts?.find((p: { key: string }) => p.key === 'reorder')?.visible];
      }).toEqual(['USER', 'recent-orders', false]);
      await page.reload();
      await expect(page.getByTestId('dash-part-kpis')).toBeVisible();
      await expect(page.getByTestId('dash-part-reorder')).toHaveCount(0);
      const other = await browser.newContext({ baseURL });
      const page2 = await other.newPage();
      await logInAs(page2, session);
      await page2.goto('/');
      await expect(page2.getByTestId('dash-part-kpis')).toBeVisible();
      await expect(page2.getByTestId('dash-part-reorder')).toHaveCount(0);
      await expect(page2.locator('[data-testid^="dash-part-"]').first()).toHaveAttribute('data-testid', 'dash-part-recent-orders');
      await other.close();

      // A part's own icons: fold, full width, hide
      const sales = page.getByTestId('dash-part-sales');
      await sales.getByRole('button', { name: 'Fold: Sales — waiting now' }).click();
      await expect(sales.getByTestId('sales-status')).toHaveCount(0);
      await sales.getByRole('button', { name: 'Unfold: Sales — waiting now' }).click();
      await expect(sales.getByTestId('sales-status')).toBeVisible();
      await sales.getByRole('button', { name: 'Hide: Sales — waiting now' }).click();
      await expect(page.getByTestId('dash-part-sales')).toHaveCount(0);

      // Reset layout: everything back, in the standard order
      await page.getByRole('button', { name: 'Manage dashboard' }).click();
      await manage.getByRole('button', { name: 'Reset layout' }).click();
      await expect(page.getByTestId('toast')).toContainText('Layout reset');
      await manage.getByRole('button', { name: 'Done' }).click();
      await expect(page.getByTestId('dash-part-reorder')).toBeVisible();
      await expect(page.getByTestId('dash-part-sales')).toBeVisible();
      await expect(page.locator('[data-testid^="dash-part-"]').first()).toHaveAttribute('data-testid', 'dash-part-kpis');
    });
});
