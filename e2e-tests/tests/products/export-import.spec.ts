import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';
import { readFile } from 'node:fs/promises';
import type { Page } from '@playwright/test';

// Export / Import menus like Cin7 (X1–X4): each list's menu offers Cin7's file types, files carry Cin7's names and columns

async function download(page: Page, menu: string, item: string) {
  const file = page.waitForEvent('download');
  await page.getByRole('button', { name: menu, exact: true }).click();
  await page.getByRole('menuitem', { name: item, exact: true }).click();
  const d = await file;
  return { name: d.suggestedFilename(), text: await readFile(await d.path(), 'utf8') };
}

test('EXP-001 the products Export menu lists Cin7\'s files and each downloads under Cin7\'s name', { tag: ['@P2', '@products'] },
  async ({ page, api, warehouses }) => {
    const product = await api.createProduct({ costPrice: 2, sellPrice: 5 });
    await api.addStock(product, warehouses.main, 3);
    await page.goto('/products');

    const files: [string, RegExp, string][] = [
      ['Inventory list', /^InventoryList_/, 'ProductCode,Name,Category'],
      ['Inventory list (without descriptions)', /^InventoryListWithoutDescriptions_/, 'ProductCode,Name,Category'],
      ['Stock on hand', /^StockOnHand_/, 'ProductCode,Quantity,AverageCost,Location,Bin'],
      ['Units of measure', /^AdditionalUOM_/, 'Action,BaseSKU,BaseProductName'],
      ['Assembly BOM', /^AssemblyBOM_/, 'Action,ProductSKU,ProductName,ComponentSKU'],
      ['Simple prices', /^SimplePrices_/, 'ProductCode,Name,PriceTier1'],
      ['Custom prices', /^CustomPrices_/, 'Action,ProductSKU,ProductName,CustomerName,Price'],
    ];
    for (const [item, name, header] of files) {
      const f = await download(page, 'Export', item);
      expect(f.name).toMatch(name);
      expect(f.text).toContain(header);
    }
    const stock = await download(page, 'Export', 'Stock on hand');
    expect(stock.text).toContain(`${product.sku},3.0000,`);
  });

test('EXP-002 stock on hand is previewed, then applied, from the products Import menu', { tag: ['@P2', '@products'] },
  async ({ page, api, warehouses }) => {
    const product = await api.createProduct({ costPrice: 2 });
    await api.addStock(product, warehouses.main, 3);
    await page.goto('/products');
    await page.getByRole('button', { name: 'Import' }).click();
    await page.getByRole('menuitem', { name: 'Stock on hand' }).click();
    const csv = `ProductCode,Quantity,AverageCost,Location\n${product.sku},8,2.5,${warehouses.main.name}\n`;
    await page.getByLabel('CSV file').setInputFiles({ name: 'StockOnHand.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    const result = page.getByTestId('products-import-result');
    await expect(result).toContainText('Preview, nothing changed yet');
    expect(await api.stock(product, warehouses.main)).toBe(3);
    await page.getByRole('button', { name: 'Apply these changes' }).click();
    await expect(result).toContainText('Applied');
    expect(await api.stock(product, warehouses.main)).toBe(8);

    // A file of another type is refused
    await page.getByRole('button', { name: 'Import' }).click();
    await page.getByRole('menuitem', { name: 'Units of measure' }).click();
    await page.getByLabel('CSV file').setInputFiles({ name: 'StockOnHand.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await expect(page.getByTestId('products-import-error')).toContainText("stock on hand file, not the units of measure");
  });

test('EXP-003 suppliers export and import in Cin7\'s columns', { tag: ['@P2', '@purchasing'] }, async ({ page }) => {
  const name = `E2E Supplier ${uid()}`;
  await page.goto('/suppliers');
  const csv = `Name,Status,Currency,PaymentTerm,ContactName,Email,ContactDefault\n${name},Active,GBP,30 days,Ann,ann@x.test,True\n`;
  await page.getByRole('button', { name: 'Import' }).click();
  await page.getByRole('menuitem', { name: 'Suppliers' }).click();
  await page.getByLabel('Supplier CSV file').setInputFiles({ name: 'Suppliers.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByTestId('supplier-import-result')).toContainText('1 suppliers created');

  const f = await download(page, 'Export', 'Suppliers');
  expect(f.name).toMatch(/^Suppliers_\d{4}-\d{2}-\d{2}\.csv$/);
  expect(f.text).toContain('Name,Status,Currency,PaymentTerm,TaxRule,AccountPayable');
  expect(f.text).toContain(`${name},Active,GBP,30 days`);
});

test('EXP-004 sale and purchase lists export every order of the current search, made on the server', { tag: ['@P2', '@sales'] },
  async ({ page, api, warehouses }) => {
    const product = await api.createProduct({ sellPrice: 5, costPrice: 2 });
    const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);
    await page.goto('/sale-orders');
    await page.getByLabel(/Search/).first().fill(so.orderNumber);
    const sales = await download(page, 'Export', 'Export to CSV format');
    expect(sales.name).toMatch(/^Sales_/);
    expect(sales.text).toContain('Order #,Status,Fulfilment,Customer');
    expect(sales.text).toContain(so.orderNumber);
    expect(sales.text.trim().split('\n')).toHaveLength(2);

    await api.createPurchaseOrder(warehouses.main, [{ product, quantity: 4 }]);
    await page.goto('/purchase-orders');
    const purchases = await download(page, 'Export', 'Export to CSV format');
    expect(purchases.name).toMatch(/^Purchases_/);
    expect(purchases.text).toContain('PO #,Status,Supplier');
    const payable = await download(page, 'Export', 'Export accounts payable');
    expect(payable.name).toMatch(/^AccountsPayable_/);
    expect(payable.text).toContain('Supplier,Type,Number,Date,Due date,Days overdue,Total,Outstanding');
  });
