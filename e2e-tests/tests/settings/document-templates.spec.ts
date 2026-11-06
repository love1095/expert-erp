import { test, expect } from '../../fixtures/test';

// Phase I4 (spec §8): named document templates like Cin7's; one picked when printing
test.describe('Document templates', () => {
  test('TEMPLATE-001 an admin adds a "Delivery Note" sales order template; the order page offers it next to Print',
    { tag: ['@P2', '@settings', '@documents'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct();
      const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);
      await api.confirmSaleOrder(so.id);

      await page.goto('/settings/templates');
      const orders = page.getByTestId('templates-SALES_ORDER');
      for (const name of ['Sales Order - Expert Safety', `Delivery Note ${Date.now()}`]) {
        await orders.getByRole('button', { name: '+ New template' }).click();
        await page.getByLabel('Template name').fill(name);
        if (name.startsWith('Delivery')) {
          await page.getByLabel('Title printed').fill('Delivery note');
          await page.getByLabel(/Show prices and totals/).uncheck();
        }
        await page.getByTestId('template-form').getByRole('button', { name: 'Save' }).click();
        await expect(page.getByRole('status')).toHaveText('Saved');
      }
      await expect(orders).toContainText('Default');
      await expect(orders).toContainText('No prices');

      await page.goto(`/sale-orders/${so.id}`);
      const picker = page.getByLabel('Template');
      await expect(picker).toBeVisible();
      await picker.selectOption({ label: (await picker.locator('option').last().textContent())! });

      const templates = await (await api.http.get('/api/settings/document-templates')).json();
      const delivery = templates.find((t: { title: string | null }) => t.title === 'Delivery note');
      const pdf = await api.http.get(`/api/sale-orders/${so.id}/pdf`, { params: { template: delivery.id } });
      expect(pdf.ok()).toBe(true);
      expect(pdf.headers()['content-type']).toContain('application/pdf');
      const wrong = await api.http.get(`/api/purchase-orders/${so.id}/pdf`, { params: { template: delivery.id } });
      expect(wrong.ok()).toBe(false);
    });
});
