import { test, expect } from '../../fixtures/test';

// Phase N5 / N4: adjustments with a reason and approval over a set value; a stock take counted on the phone scanner page
test.describe('Adjustments and stock take', () => {
  test('COUNT-001 an adjustment over the approval value waits for a manager; a stock take scanned on the phone page posts its variance',
    { tag: ['@P1', '@warehouse'] }, async ({ page, api, warehouses }) => {
      const product = await api.createProduct({ costPrice: 10 });
      await api.addStock(product, warehouses.main, 10);

      // The approval value: £25
      await page.goto('/stock-adjustments');
      await page.getByRole('button', { name: 'Change' }).click();
      await page.getByLabel('Approval value').fill('25');
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByText(/worth more than £25\.00 wait/)).toBeVisible();

      // 2 damaged: posted at once; 6 lost: waits, then approved
      const form = page.getByTestId('adjust-form');
      await form.getByLabel('Warehouse').selectOption({ label: warehouses.main.name });
      await form.getByLabel('Product code').fill(product.sku);
      await form.getByRole('button', { name: 'Find' }).click();
      await expect(page.getByTestId('adjust-stock')).toContainText(': 10');
      await form.getByLabel('Quantity').fill('2');
      await form.getByLabel('Reason').selectOption('DAMAGE');
      await form.getByRole('button', { name: 'Save adjustment' }).click();
      await expect(form.getByRole('alert')).toContainText('Posted');
      await form.getByLabel('Quantity').fill('6');
      await form.getByLabel('Reason').selectOption('THEFT');
      await form.getByRole('button', { name: 'Save adjustment' }).click();
      await expect(form.getByRole('alert')).toContainText('waiting for a manager');
      await expect.poll(() => api.stock(product, warehouses.main)).toBe(8);
      await page.getByTestId('adjust-pending').getByRole('button', { name: 'Approve' }).click();
      await expect(page.getByTestId('adjust-pending')).toHaveCount(0);
      await expect.poll(() => api.stock(product, warehouses.main)).toBe(2);

      // A stock take of that product, counted by scanning on the phone page: 3 found where 2 were expected
      await page.goto('/stock-takes');
      await page.getByLabel('Warehouse').selectOption({ label: warehouses.main.name });
      await page.getByLabel('Scope').selectOption('PRODUCTS');
      await page.getByLabel('Product code').fill(product.sku);
      await page.getByRole('button', { name: 'Add', exact: true }).click();
      await page.getByRole('button', { name: 'Start counting' }).click();
      await expect(page).toHaveURL(/\/stock-takes\/\d+$/);
      const takeUrl = page.url();

      await page.goto('/scan');
      await page.getByRole('tab', { name: 'Count' }).click();
      await page.getByTestId('scan-count').getByRole('button').first().click();
      const scan = page.getByLabel('Scan');
      for (let i = 0; i < 3; i++) {
        await scan.fill(product.sku);
        await scan.press('Enter');
        await expect(page.getByLabel(`Counted ${product.sku}`)).toHaveValue(`${i + 1}`);
      }
      await expect(page.getByTestId('take-lines')).toContainText('+1');

      await page.goto(takeUrl);
      await expect(page.getByTestId('take-variance')).toHaveText('£10.00');
      await page.getByRole('button', { name: 'Post stock take' }).click();
      await expect(page.getByTestId('take-status')).toHaveText('Posted');
      await expect.poll(() => api.stock(product, warehouses.main)).toBe(3);
    });
});
