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

  test('STK-004 a Cin7 stock file sets stock on hand after a preview; importing it again changes nothing', { tag: ['@P1', '@stock'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct({ costPrice: 2 });
      const gloves = await api.createProduct();
      await api.addStock(gloves, warehouses.main, 8);
      await api.addStock(helmet, warehouses.second, 50);   // another warehouse: not touched
      const csv = 'SKU,ProductName,Location,StockValue,OnHand\n'
        + `${helmet.sku},Helmet,Expert Warehouse,,12\n`
        + `${gloves.sku},Gloves,Expert Warehouse,30,3\n`
        + 'NOT-IN-ERP,Unknown,Expert Warehouse,10,5\n';
      const file = { name: 'AvailabilityReport_test.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) };

      await page.goto('/stock-overview');
      await page.getByRole('button', { name: 'Import stock' }).click();
      const panel = page.getByTestId('stock-import');
      await panel.getByLabel('Warehouse').selectOption(String(warehouses.main.id));
      await panel.getByLabel('CSV file').setInputFiles(file);
      await panel.getByRole('button', { name: 'Preview' }).click();

      const result = page.getByTestId('stock-import-result');
      await expect(result).toContainText('Preview');
      await expect(result).toContainText('2 products change');
      await expect(result).toContainText('1 SKUs with stock not found');
      // Helmet has no stock value in the file, so its cost price: 12 × £2 + gloves £30
      await expect(result).toContainText('£54.00');
      expect(await api.stock(helmet, warehouses.main)).toBe(0);

      await result.getByRole('button', { name: 'Import 2 changes' }).click();
      await expect(result).toContainText('✓ Imported');
      await expect.poll(() => api.stock(helmet, warehouses.main)).toBe(12);
      expect(await api.stock(gloves, warehouses.main)).toBe(3);
      expect(await api.stock(helmet, warehouses.second)).toBe(50);

      await panel.getByLabel('CSV file').setInputFiles(file);
      await panel.getByRole('button', { name: 'Preview' }).click();
      await expect(result).toContainText('0 products change');
      await expect(result.getByRole('button', { name: /Import/ })).toHaveCount(0);
    });
});
