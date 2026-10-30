import { test, expect } from '../../fixtures/test';

// Phase E4: the reorder screen raises one draft PO per supplier (docs/phase-e-purchasing.md)
test('RO-001 products below their minimum are chosen per supplier and become one draft PO per supplier', { tag: ['@P1', '@purchase-orders'] },
  async ({ page, api, warehouses }) => {
    const stamp = Date.now();
    const portwest = await (await api.http.post('/api/suppliers', { data: { name: `Portwest ${stamp}` } })).json();
    const jsp = await (await api.http.post('/api/suppliers', { data: { name: `JSP ${stamp}` } })).json();
    const gloves = await api.createProduct({ minStock: 20, reorderQuantity: 50, costPrice: 1.2, defaultSupplierId: portwest.id, supplierProductCode: 'PW-G' });
    const helmet = await api.createProduct({ minStock: 5, costPrice: 8, defaultSupplierId: jsp.id });
    await api.addStock(gloves, warehouses.main, 5);

    await page.goto('/reorder');
    const suppliers = page.getByTestId('reorder-suppliers');
    await suppliers.getByRole('button', { name: new RegExp(`Portwest ${stamp}`) }).click();
    const row = page.getByTestId(`reorder-row-${gloves.id}`);
    // Cin7's suggestion: the reorder quantity
    await expect(row).toContainText('PW-G');
    await row.getByLabel(`Choose ${gloves.sku}`).check();
    await expect(row.getByLabel(`Order quantity for ${gloves.sku}`)).toHaveValue('50');

    await suppliers.getByRole('button', { name: new RegExp(`JSP ${stamp}`) }).click();
    await page.getByTestId(`reorder-row-${helmet.id}`).getByLabel(`Choose ${helmet.sku}`).check();

    await page.getByLabel('Deliver to').selectOption(String(warehouses.main.id));
    await expect(page.getByText('2 products chosen')).toBeVisible();
    await page.getByRole('button', { name: 'Create draft POs' }).click();

    const created = page.getByTestId('reorder-created');
    await expect(created).toContainText('2 draft purchase orders created');
    await expect(created).toContainText(`Portwest ${stamp}`);
    await expect(created).toContainText(`JSP ${stamp}`);
    await created.getByRole('link').first().click();
    await expect(page.locator('[data-testid^="po-status-"]')).toContainText('DRAFT');
  });
