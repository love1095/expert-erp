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

  await expect(page.getByRole('cell', { name: sku })).toBeVisible();
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
