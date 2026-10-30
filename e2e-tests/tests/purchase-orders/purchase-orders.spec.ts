import { test, expect } from '../../fixtures/test';

test('PO-001 receiving a purchase order adds stock', { tag: ['@P0', '@purchase-orders'] }, async ({ page, api, warehouses }) => {
  const product = await api.createProduct();
  const po = await api.createPurchaseOrder(warehouses.main, [{ product, quantity: 40 }]);

  await page.goto('/purchase-orders');
  const row = page.getByTestId(`po-row-${po.id}`);
  await row.getByRole('button', { name: 'Receive' }).click();

  await expect(row).toContainText('RECEIVED');
  expect(await api.stock(product, warehouses.main)).toBe(40);
});

// Phase E1: the purchase order like Cin7 (docs/phase-e-purchasing.md)
test('PO-002 a PO takes the supplier\'s terms and purchase VAT, is placed with the supplier, then received', { tag: ['@P0', '@purchase-orders'] },
  async ({ page, api, warehouses }) => {
    const supplier = await (await api.http.post('/api/suppliers', { data: { name: `Orn Clothing ${Date.now()}`, paymentTerm: '30 Day EOM' } })).json();
    const fleece = await api.createProduct();

    await page.goto('/purchase-orders');
    await page.getByRole('button', { name: '+ New PO' }).click();
    const form = page.getByTestId('purchase-order-form');
    await form.getByLabel('Supplier *').fill(supplier.name);
    await form.getByLabel('Deliver to *').selectOption(String(warehouses.main.id));
    await form.getByLabel('Supplier reference').fill('ORN-55');
    await form.getByLabel('Product for line 1').fill(fleece.sku);
    await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: fleece.sku }).click();
    await form.getByLabel('Quantity for line 1').fill('5');
    await form.getByLabel('Unit cost for line 1').fill('9.21');
    // 5 × 9.21 = 46.05 + 20 % VAT 9.21 = 55.26; terms from the supplier
    await expect(form.getByLabel('VAT for line 1')).toContainText('Auto: 20% (VAT on Expenses)');
    await expect(form.getByTestId('po-form-totals')).toContainText('£55.26');
    await expect(form.getByLabel('Terms')).toHaveAttribute('placeholder', /30 Day EOM/);
    await form.getByRole('button', { name: 'Create & mark ordered' }).click();

    const status = page.locator('[data-testid^="po-status-"]');
    await expect(status).toContainText('ORDERED');
    const details = page.locator('[data-testid^="po-details-"]');
    await expect(details).toContainText('30 Day EOM');
    await expect(details).toContainText('ORN-55');
    await expect(details).toContainText(/PO-\d+/);
    await expect(page.locator('[data-testid^="po-totals-"]')).toContainText('£55.26');

    // E2: 3 of the 5 arrive first — partially received, 2 still on order
    await page.getByRole('button', { name: 'Receive stock' }).click();
    await page.getByLabel(`Received quantity for ${fleece.sku}`).fill('3');
    await page.getByLabel('Notes').fill('DN-1001');
    await page.getByRole('button', { name: 'Receive & add stock' }).click();
    await expect(status).toContainText('ORDERED');
    await expect(status).toContainText('Partially received');
    await expect(page.locator('[data-testid^="po-receipts-"]')).toContainText(`3 × ${fleece.sku}`);
    expect(await api.stock(fleece, warehouses.main)).toBe(3);

    // Then 3 more instead of the 2 outstanding: accepted and flagged
    await page.getByRole('button', { name: 'Receive stock' }).click();
    await expect(page.getByLabel(`Received quantity for ${fleece.sku}`)).toHaveValue('2');
    await page.getByLabel(`Received quantity for ${fleece.sku}`).fill('3');
    await expect(page.getByText('1 more than outstanding')).toBeVisible();
    await page.getByRole('button', { name: 'Receive & add stock' }).click();
    await expect(status).toContainText('RECEIVED');
    await expect(status).toContainText('Over-delivered');
    await expect(page.locator('[data-testid^="po-receipts-"]')).toContainText('(1 over)');
    expect(await api.stock(fleece, warehouses.main)).toBe(6);

    // E3: the supplier's bill for the 6 received, at the PO price, with their shipping
    const billNo = `ORN-INV-${Date.now()}`;
    await page.getByRole('button', { name: 'Enter bill' }).click();
    const bill = page.getByTestId('bill-form');
    await bill.getByLabel("Supplier's invoice number *").fill(billNo);
    await expect(bill.getByLabel(`Billed quantity for ${fleece.sku}`)).toHaveValue('6');
    await bill.getByRole('button', { name: '+ Add charge' }).click();
    await bill.getByLabel('Charge 1 description').fill('Shipping - Standard');
    await bill.getByLabel('Charge 1 price').fill('12');
    await bill.getByRole('button', { name: 'Enter bill' }).click();

    const bills = page.locator('[data-testid^="po-bills-"]');
    await expect(bills).toContainText(billNo);
    await expect(bills).toContainText('Fully billed');
    // 6 × 9.21 = 55.26 + 11.05 VAT, shipping 12 + 2.40 VAT = 80.71
    await expect(bills).toContainText('£80.71');
    // 6 billed against 5 ordered is flagged by the three-way match
    await expect(bills).toContainText("1 line don't match the PO");
    // General tab: ordered, received and billed done, not paid yet; Accounting tab: the bill, owed in full
    const poId = Number((await page.locator('[data-testid^="po-page-"]').getAttribute('data-testid'))!.replace('po-page-', ''));
    await page.goto('/purchase-orders');
    const stages = page.getByTestId(`po-stages-${poId}`);
    for (const [stage, state] of [['order', 'done'], ['receive', 'done'], ['invoice', 'done'], ['paid', 'todo']]) {
      await expect(stages.locator(`[data-stage="${stage}"]`)).toHaveAttribute('data-state', state);
    }
    await expect(page.getByTestId(`po-row-${poId}`)).toContainText(billNo);
    await page.getByRole('tab', { name: 'Accounting' }).click();
    await page.getByTestId('bills-list').getByLabel('Search').fill(billNo);
    const billRow = page.locator('[data-testid^="bill-row-"]', { hasText: billNo });
    await expect(billRow).toContainText('Awaiting payment');
    await expect(billRow).toContainText('£80.71');
    await billRow.click();
    await expect(page.getByText('Billed 6, ordered 5')).toBeVisible();
    await expect(page.getByText('Charge (expense)')).toBeVisible();
  });
