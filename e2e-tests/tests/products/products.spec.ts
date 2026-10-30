import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';

test('PRD-001 create a product', { tag: ['@P0', '@products'] }, async ({ page }) => {
  const sku = `E2E-${uid()}`;
  await page.goto('/products');
  await page.getByRole('button', { name: '+ Add Product' }).click();
  await page.getByLabel('SKU').fill(sku);
  await page.getByLabel('Name').fill('Safety Helmet');
  await page.getByLabel('Unit').fill('pcs');
  await page.getByRole('button', { name: 'Save Product' }).click();

  await expect(page.getByRole('cell', { name: sku, exact: true })).toBeVisible();
});

test('PRD-002 duplicate SKU is rejected', { tag: ['@P1', '@products'] }, async ({ page, api }) => {
  const existing = await api.createProduct();

  await page.goto('/products');
  await page.getByRole('button', { name: '+ Add Product' }).click();
  await page.getByLabel('SKU').fill(existing.sku);
  await page.getByLabel('Name').fill('Duplicate');
  await page.getByLabel('Unit').fill('pcs');
  await page.getByRole('button', { name: 'Save Product' }).click();

  await expect(page.getByText(`SKU already exists: ${existing.sku}`)).toBeVisible();
});

test('PRD-003 a product is edited: cost and selling price are saved and listed', { tag: ['@P1', '@products'] }, async ({ page, api }) => {
  const product = await api.createProduct({ costPrice: 4 });

  await page.goto('/products');
  const row = page.getByRole('row').filter({ hasText: product.sku });
  // Like Cin7: tick the product, then More actions → Edit
  await row.getByLabel(`Select ${product.sku}`).check();
  await page.getByRole('button', { name: 'More actions' }).click();
  await page.getByRole('menuitem', { name: 'Edit' }).click();
  await expect(page.getByRole('heading', { name: `Edit ${product.sku}` })).toBeVisible();
  await expect(page.getByLabel('Cost price (£)')).toHaveValue('4');
  await page.getByLabel('Name').fill('Renamed helmet');
  await page.getByLabel('Selling price (£)').fill('12.50');
  await page.getByRole('button', { name: 'Save Product' }).click();

  await expect(row).toContainText('Renamed helmet');
  await expect(row).toContainText('£4.00');
  await expect(row).toContainText('£12.50');
  const saved = await (await api.http.get(`/api/products/${product.id}`)).json();
  expect(saved).toMatchObject({ name: 'Renamed helmet', sellPrice: 12.5, costPrice: 4 });
});

test('PRD-004 a negative selling price is refused', { tag: ['@P2', '@products'] }, async ({ api }) => {
  const res = await api.http.post('/api/products', { data: { sku: `E2E-${Date.now()}`, name: 'Bad', unit: 'pcs', sellPrice: -1 } });
  expect(res.status()).toBe(400);
  expect(await res.text()).toContain('Selling price cannot be negative');
});

test('PRD-005 a Cin7 inventory list is imported; deprecated products are hidden by default', { tag: ['@P1', '@products'] }, async ({ page }) => {
  await page.goto('/products');
  await page.getByRole('button', { name: 'Import' }).click();
  await page.getByLabel('CSV file').setInputFiles('fixtures/files/cin7-inventory.csv');

  const result = page.getByTestId('products-import-result');
  await expect(result).toContainText('5 rows: 5 created');
  await expect(result).toContainText('New: 4 categories, 2 suppliers');

  // Active by default: the deprecated glove is hidden until asked for
  await page.getByLabel('Search products').fill('TST-');
  const rows = page.getByRole('row').filter({ hasText: 'TST-' });
  await expect(rows).toHaveCount(4);
  const polo = page.getByRole('row').filter({ hasText: 'TST-POLO-NAV-M' });
  await expect(polo).toContainText('Navy · M');
  await expect(polo).toContainText('Testbrand');
  await expect(polo).toContainText('£9.99');
  await page.getByLabel('Status').selectOption('DEPRECATED');
  await expect(page.getByRole('row').filter({ hasText: 'TST-OLD' })).toContainText('Deprecated');

  // Importing again changes nothing
  await page.getByLabel('CSV file').setInputFiles('fixtures/files/cin7-inventory.csv');
  await expect(result).toContainText('0 created, 0 updated, 5 unchanged');
});

test('PRD-006 export downloads the products as a re-importable CSV', { tag: ['@P2', '@products'] }, async ({ page, api }) => {
  const product = await api.createProduct({ sellPrice: 12.5 });

  await page.goto('/products');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^products_\d{4}-\d{2}-\d{2}\.csv$/);
  const csv = await (await import('node:fs/promises')).readFile(await file.path(), 'utf8');
  expect(csv).toContain('ProductCode,Name,Category,Brand');
  expect(csv).toContain(`${product.sku},${product.name},`);
  expect(csv).toContain(',12.5,');
});

test('PRD-007 a file that is not an inventory list is refused with a reason', { tag: ['@P2', '@products'] }, async ({ page }) => {
  await page.goto('/products');
  await page.getByRole('button', { name: 'Import' }).click();
  await page.getByLabel('CSV file').setInputFiles({ name: 'wrong.csv', mimeType: 'text/csv', buffer: Buffer.from('Name,Price\nX,1\n') });
  await expect(page.getByTestId('products-import-error')).toContainText("needs 'ProductCode' and 'Name' columns");
});

test('PRD-008 a product opens on its own page: stock per warehouse, what is on order, and its changes in the activity log', { tag: ['@P1', '@products'] },
  async ({ page, api, warehouses }) => {
    const product = await api.createProduct({ costPrice: 2, sellPrice: 5 });
    await api.addStock(product, warehouses.main, 7);
    await api.createPurchaseOrder(warehouses.main, [{ product, quantity: 10 }]);

    await page.goto('/products');
    await page.getByLabel('Search products').fill(product.sku);
    await page.getByTestId(`product-row-${product.id}`).click();
    await expect(page.getByTestId(`product-page-${product.id}`)).toContainText(product.sku);
    await expect(page.getByTestId('product-section')).toContainText('FIFO');

    await page.getByRole('button', { name: 'Stock', exact: true }).click();
    await expect(page.getByTestId('product-stock')).toContainText(warehouses.main.name);
    await expect(page.getByTestId('product-stock')).toContainText('7');
    await expect(page.getByTestId('product-section')).toContainText('On order: 10');

    // Edit from the page, then back to it with the change in the activity log
    await page.getByRole('link', { name: 'Edit' }).click();
    await page.getByLabel('Selling price (£)').fill('6');
    await page.getByRole('button', { name: 'Save Product' }).click();
    await expect(page.getByTestId(`product-page-${product.id}`)).toBeVisible();
    await page.getByRole('button', { name: 'Activity log' }).click();
    await expect(page.getByTestId('product-activity')).toContainText('sellPrice: 5 → 6');

    // A product with stock can't be deleted
    await page.getByRole('button', { name: 'More actions' }).click();
    await page.getByRole('menuitem', { name: 'Delete product' }).click();
    await expect(page.getByTestId('product-error')).toContainText('mark it deprecated');
  });
