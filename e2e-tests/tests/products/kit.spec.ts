import { test, expect } from '../../fixtures/test';

// Phase P2: a kit made of components on its product page; its stock is what they make; selling it ships the components
test.describe('Kits', () => {
  test('KIT-001 a kit gets its components, shows what the stock makes, and shipping it takes the components',
    { tag: ['@P1', '@products'] }, async ({ page, api, warehouses }) => {
      const gloves = await api.createProduct();
      const glasses = await api.createProduct();
      await api.addStock(gloves, warehouses.main, 10);
      await api.addStock(glasses, warehouses.main, 3);
      const res = await api.http.post('/api/products', { data: { sku: `KIT-${gloves.sku}`, name: 'Starter kit', unit: 'Each', type: 'KIT' } });
      expect(res.ok()).toBeTruthy();
      const kit = await res.json();

      await page.goto(`/products/${kit.id}`);
      await page.getByRole('navigation', { name: 'Product sections' }).getByRole('button', { name: 'Components' }).click();
      const card = page.getByTestId('kit-components');
      await card.getByLabel('Component code').fill(gloves.sku);
      await card.getByRole('button', { name: 'Add component' }).click();
      await card.getByLabel(`Quantity of ${gloves.sku}`).fill('2');
      await card.getByLabel('Component code').fill(glasses.sku);
      await card.getByRole('button', { name: 'Add component' }).click();
      await card.getByRole('button', { name: 'Save components' }).click();
      await expect(card.getByRole('status')).toHaveText('Saved');
      // min(10 / 2, 3 / 1) = 3 kits
      await expect(page.getByTestId('kit-buildable')).toContainText(warehouses.main.name);
      await expect(page.getByTestId('kit-buildable').locator('tbody tr').first()).toContainText('3');

      // Two kits sold and shipped: 4 gloves and 2 glasses leave
      const so = await api.createSaleOrder(warehouses.main, [{ product: kit, quantity: 2 }]);
      await api.confirmSaleOrder(so.id);
      await api.fulfilSaleOrder(so.id);
      expect(await api.stock(gloves, warehouses.main)).toBe(6);
      expect(await api.stock(glasses, warehouses.main)).toBe(1);
      await page.reload();
      await page.getByRole('navigation', { name: 'Product sections' }).getByRole('button', { name: 'Components' }).click();
      await expect(page.getByTestId('kit-buildable').locator('tbody tr').first()).toContainText('1');
    });
});
