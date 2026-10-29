import { test, expect } from '../../fixtures/test';

test.describe('Stock movements', () => {
  test('STK-001 recording an inbound movement increases stock', { tag: ['@P0', '@stock'] }, async ({ page, api, warehouses }) => {
    const product = await api.createProduct();

    await page.goto('/stock-movements');
    await page.getByRole('button', { name: '+ Record Movement' }).click();
    await page.getByLabel('Product').selectOption(String(product.id));
    await page.getByLabel('Warehouse').selectOption(String(warehouses.main.id));
    await page.getByLabel('Type').selectOption('ADJUSTMENT_IN');
    await page.getByLabel('Quantity').fill('25');
    await page.getByRole('button', { name: 'Record Movement', exact: true }).click();

    await expect.poll(() => api.stock(product, warehouses.main)).toBe(25);
  });

  test('STK-002 outbound movement larger than stock is rejected', { tag: ['@P0', '@stock'] }, async ({ page, api, warehouses }) => {
    const product = await api.createProduct();
    await api.addStock(product, warehouses.main, 5);

    await page.goto('/stock-movements');
    await page.getByRole('button', { name: '+ Record Movement' }).click();
    await page.getByLabel('Product').selectOption(String(product.id));
    await page.getByLabel('Warehouse').selectOption(String(warehouses.main.id));
    await page.getByLabel('Type').selectOption('ADJUSTMENT_OUT');
    await page.getByLabel('Quantity').fill('50');
    await page.getByRole('button', { name: 'Record Movement', exact: true }).click();

    await expect(page.getByText(/Insufficient stock/)).toBeVisible();
    expect(await api.stock(product, warehouses.main)).toBe(5);
  });

  test('STK-003 transfer moves stock between warehouses', { tag: ['@P0', '@stock'] }, async ({ page, api, warehouses }) => {
    const product = await api.createProduct();
    await api.addStock(product, warehouses.main, 30);

    await page.goto('/stock-transfer');
    await page.getByLabel('Product').selectOption(String(product.id));
    await page.getByLabel('From Warehouse').selectOption(String(warehouses.main.id));
    await page.getByLabel('To Warehouse').selectOption(String(warehouses.second.id));
    await page.getByLabel('Quantity').fill('10');
    await page.getByRole('button', { name: 'Transfer Stock' }).click();

    await expect.poll(() => api.stock(product, warehouses.second)).toBe(10);
    expect(await api.stock(product, warehouses.main)).toBe(20);
  });
});
