import { test, expect } from '../../fixtures/test';
import { addUser, apiFor } from '../../fixtures/api';

// Phase I1 (spec §8): roles with ticked permissions like Cin7's Users & Roles; cost hidden from those without the tick
test.describe('Users and roles', () => {
  test('ROLE-001 staff see no cost or financial reports; a role with the ticks shows them; the admin sets it on the Users page',
    { tag: ['@P0', '@security', '@settings'] }, async ({ page, api, baseURL }) => {
      const product = await api.createProduct({ costPrice: 6 });
      const session = await addUser(api, 'STAFF');
      const staff = await apiFor(baseURL!, session);
      try {
        // Staff by default: products yes, but no cost price, no VAT return
        const p = await (await staff.http.get(`/api/products/${product.id}`)).json();
        expect(p.costPrice).toBeNull();
        expect((await staff.http.get('/api/reports/vat-return', { params: { from: '2026-01-01', to: '2026-03-31' } })).status()).toBe(403);
        expect((await staff.http.get('/api/auth/me')).ok()).toBe(true);

        // The admin makes a role in the UI and gives it to the staff user
        const name = `Buyer ${Date.now()}`;
        await page.goto('/users');
        await page.getByTestId('roles').getByRole('button', { name: '+ Add new role' }).click();
        await page.getByLabel('Role name').fill(name);
        for (const tick of ['Product - List and details, stock and movements', 'Product - Prices: cost price',
          'Financial reports (VAT return, aged debtors and creditors, sales profit, inventory movement, Xero export)']) {
          await page.getByTestId('roles').getByLabel(tick).check();
        }
        await page.getByTestId('roles').getByRole('button', { name: 'Save', exact: true }).click();
        await expect(page.getByTestId('roles').getByRole('status')).toHaveText('Saved');
        await page.getByLabel(`Access role of ${session.user.username}`).selectOption({ label: name });
        await expect(page.getByTestId('roles')).toContainText(`${name} (1)`);

        // The same login now sees the cost and the report, and cannot sell any more
        const again = await (await staff.http.get(`/api/products/${product.id}`)).json();
        expect(again.costPrice).toBe(6);
        expect((await staff.http.get('/api/reports/vat-return', { params: { from: '2026-01-01', to: '2026-03-31' } })).ok()).toBe(true);
        const sale = await staff.http.get('/api/sale-orders');
        expect(sale.status()).toBe(403);
        expect((await sale.json()).error).toContain('Sale - List and details');
      } finally {
        await staff.dispose();
      }
    });
});
