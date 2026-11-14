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
    await page.getByRole('link', { name: '+ New PO' }).click();
    const form = page.getByTestId('purchase-order-form');
    await form.getByLabel('Supplier *').fill(supplier.name);
    await form.getByLabel('Location *').selectOption(String(warehouses.main.id));
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
    await page.getByTestId('bills-list').getByLabel('Search', { exact: true }).fill(billNo);
    const billRow = page.locator('[data-testid^="bill-row-"]', { hasText: billNo });
    await expect(billRow).toContainText('Awaiting payment');
    await expect(billRow).toContainText('£80.71');
    await billRow.click();
    await expect(page.getByText('Billed 6, ordered 5')).toBeVisible();
    await expect(page.getByText('Charge (expense)')).toBeVisible();
  });

// New purchase like Cin7: Supplier details, Accounting details, Shipping details
test('PO-020 a new PO fills the supplier\'s contact and address and the warehouse address; blind receipt hides what was ordered', { tag: ['@P1', '@purchase-orders'] },
  async ({ page, api, warehouses }) => {
    const supplier = await (await api.http.post('/api/suppliers', { data: { name: `Orn Clothing ${Date.now()}`, contactName: 'Mark Fyffe',
      phone: '01295 221350', address: { line1: 'Tingewick Industrial Estate', city: 'Milton Keynes', postcode: 'MK18 1SU' } } })).json();
    await api.http.put(`/api/warehouses/${warehouses.main.id}`, { data: { name: warehouses.main.name,
      address: { line1: 'Unit 3 Halfpenny Bridge Ind Est', city: 'Rochdale', postcode: 'OL11 1NS' } } });
    const fleece = await api.createProduct({ costPrice: 4 });

    await page.goto('/purchase-orders/new');
    const form = page.getByTestId('purchase-order-form');
    await expect(form.getByTestId('po-form-supplier')).toBeVisible();
    await expect(form.getByTestId('po-form-accounting')).toContainText('Inventory account');
    await expect(form.getByLabel('Tax inclusive')).toBeDisabled();
    await form.getByLabel('Supplier *').fill(supplier.name);
    await expect(form.getByLabel('Contact', { exact: true })).toHaveValue('Mark Fyffe');
    await expect(form.getByLabel('Phone', { exact: true })).toHaveValue('01295 221350');
    await expect(form.getByLabel('Vendor address line 1')).toHaveValue('Tingewick Industrial Estate');
    await form.getByLabel('Location *').selectOption(String(warehouses.main.id));
    await expect(form.getByLabel('Shipping address line 1')).toHaveValue('Unit 3 Halfpenny Bridge Ind Est');
    await form.getByLabel('Blind receipt').check();
    await form.getByLabel('Product for line 1').fill(fleece.sku);
    await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: fleece.sku }).click();
    await form.getByLabel('Quantity for line 1').fill('6');
    await form.getByRole('button', { name: 'Create & mark ordered' }).click();

    const details = page.locator('[data-testid^="po-details-"]');
    await expect(details).toContainText('Mark Fyffe');
    await expect(details).toContainText('MK18 1SU');
    await expect(details).toContainText('OL11 1NS');
    await expect(details).toContainText('Blind receipt');

    // Receiving: nothing filled in, ordered and outstanding not shown
    await page.getByRole('button', { name: 'Receive stock' }).click();
    await expect(page.getByLabel(`Received quantity for ${fleece.sku}`)).toHaveValue('');
  });

test('PO-021 Invoice first: receiving waits for the supplier\'s bill, then takes what it covers; Stock first bills only what came in', { tag: ['@P1', '@purchase-orders'] },
  async ({ page, api, warehouses }) => {
    const gloves = await api.createProduct({ costPrice: 2 });
    await page.goto('/purchase-orders/new');
    const form = page.getByTestId('purchase-order-form');
    await form.getByLabel('Supplier *').fill(`Invoice First Ltd ${Date.now()}`);
    await form.getByLabel('Location *').selectOption(String(warehouses.main.id));
    await form.getByRole('radiogroup', { name: 'Approach' }).getByText('Invoice first').click();
    await expect(form.getByText("Enter the supplier's bill, then receive what it covers")).toBeVisible();
    await form.getByLabel('Product for line 1').fill(gloves.sku);
    await form.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: gloves.sku }).click();
    await form.getByLabel('Quantity for line 1').fill('5');
    await form.getByRole('button', { name: 'Create & mark ordered' }).click();

    await expect(page.locator('[data-testid^="po-details-"]')).toContainText('Invoice first');
    await expect(page.getByRole('button', { name: 'Receive stock' })).toBeDisabled();
    await page.getByRole('button', { name: 'Enter bill' }).click();
    const bill = page.getByTestId('bill-form');
    await bill.getByLabel("Supplier's invoice number *").fill(`IF-${Date.now()}`);
    await expect(bill.getByLabel(`Billed quantity for ${gloves.sku}`)).toHaveValue('5');
    await bill.getByLabel(`Billed quantity for ${gloves.sku}`).fill('3');
    await bill.getByRole('button', { name: 'Enter bill' }).click();
    await expect(page.locator('[data-testid^="po-bills-"]')).toBeVisible();
    await page.getByRole('button', { name: 'Receive stock' }).click();
    await expect(page.getByLabel(`Received quantity for ${gloves.sku}`)).toHaveValue('3');

    // Stock first (the default): no bill before the goods
    const po = await api.createPurchaseOrder(warehouses.main, [{ product: gloves, quantity: 4 }]);
    await api.http.post(`/api/purchase-orders/${po.id}/order`);
    const early = await api.http.post(`/api/purchase-orders/${po.id}/bills`, { data: { supplierInvoiceNumber: `SF-${Date.now()}`,
      lines: [{ purchaseOrderLineId: (await (await api.http.get(`/api/purchase-orders/${po.id}`)).json()).lines[0].id, quantity: 4 }] } });
    expect(early.status()).toBe(422);
    expect((await early.json()).error).toContain('Stock first');
  });

test('PO-022 new purchase "+" for contact, vendor address and location; Save as preset fills a later purchase', { tag: ['@P2', '@purchase-orders'] },
  async ({ page, api }) => {
    const supplier = await (await api.http.post('/api/suppliers', { data: { name: `Preset Supplier ${Date.now()}` } })).json();
    const presetName = `Monthly ${Date.now()}`;
    await page.goto('/purchase-orders/new');
    const form = page.getByTestId('purchase-order-form');
    await expect(form.getByRole('button', { name: 'New contact' })).toBeDisabled();
    await form.getByLabel('Supplier *').fill(supplier.name);

    // Contact for this order only
    await form.getByRole('button', { name: 'New contact' }).click();
    const contact = page.getByTestId('quick-supplier-contact');
    await contact.getByLabel('Name').fill('Jo Buyer');
    await contact.getByLabel('Phone').fill('0161 000 111');
    await contact.getByLabel(`Save as ${supplier.name}'s contact`).uncheck();
    await contact.getByRole('button', { name: 'Use contact' }).click();
    await expect(form.getByLabel('Contact', { exact: true })).toHaveValue('Jo Buyer');
    expect((await (await api.http.get(`/api/suppliers/${supplier.id}`)).json()).contactName ?? null).toBeNull();

    // Vendor address kept on the supplier
    await form.getByRole('button', { name: 'New vendor address' }).click();
    const address = page.getByTestId('quick-supplier-address');
    await address.getByLabel('New vendor address line 1').fill('1 Mill Street');
    await address.getByLabel('New vendor address postcode').fill('M1 1AA');
    await address.getByRole('button', { name: 'Use address' }).click();
    await expect(form.getByLabel('Vendor address line 1')).toHaveValue('1 Mill Street');
    await expect.poll(async () => (await (await api.http.get(`/api/suppliers/${supplier.id}`)).json()).address?.postcode).toBe('M1 1AA');

    // A new location, chosen at once, its address shipped to
    const locationName = `E2E Store ${Date.now()}`;
    await form.getByRole('button', { name: 'New location' }).click();
    const location = page.getByTestId('quick-new-location');
    await location.getByLabel('Name').fill(locationName);
    await location.getByLabel('New location address line 1').fill('9 Dock Road');
    await location.getByRole('button', { name: 'Add location' }).click();
    await expect(form.getByLabel('Location *').locator('option:checked')).toHaveText(locationName);
    await expect(form.getByLabel('Shipping address line 1')).toHaveValue('9 Dock Road');

    await form.getByRole('radiogroup', { name: 'Approach' }).getByText('Invoice first').click();
    await form.getByLabel('Blind receipt').check();
    await page.getByRole('button', { name: 'Save as preset' }).click();
    const save = page.getByTestId('po-save-preset');
    await save.getByLabel('Preset name *').fill(presetName);
    await save.getByRole('button', { name: 'Save preset' }).click();
    await expect(page.getByLabel('Preset', { exact: true }).locator('option:checked')).toHaveText(presetName);

    // A later purchase starts from the preset: header filled, lines empty
    await page.goto('/purchase-orders/new');
    await page.getByLabel('Preset', { exact: true }).selectOption({ label: presetName });
    await expect(form.getByLabel('Supplier *')).toHaveValue(supplier.name);
    await expect(form.getByLabel('Contact', { exact: true })).toHaveValue('Jo Buyer');
    await expect(form.getByLabel('Location *').locator('option:checked')).toHaveText(locationName);
    await expect(form.getByLabel('Blind receipt')).toBeChecked();
    await expect(form.getByRole('radio', { name: 'Invoice first' })).toBeChecked();
    await expect(form.getByLabel('Product for line 1')).toHaveValue('');

    // The pop-up that asks first is confirmed by the fixture
    await page.getByRole('button', { name: 'Delete preset' }).click();
    await expect(page.getByLabel('Preset', { exact: true }).locator(`option`, { hasText: presetName })).toHaveCount(0);
  });

test('PO-023 the product list on a line: "SKU: name" rows, "+ Add a new item" adds a product and picks it, the information box shows its stock', { tag: ['@P2', '@purchase-orders'] },
  async ({ page, api, warehouses }) => {
    const visor = await api.createProduct({ name: 'Blue Eagle FC48 clear visor with a long name that runs onto a second line of the list', costPrice: 3 });
    await api.addStock(visor, warehouses.main, 4);
    await page.goto('/purchase-orders/new');
    const form = page.getByTestId('purchase-order-form');
    await form.getByLabel('Supplier *').fill(`Picker Supplier ${Date.now()}`);
    await form.getByLabel('Location *').selectOption(String(warehouses.main.id));

    // Rows read "SKU: full name"
    await form.getByLabel('Product for line 1').fill(visor.sku);
    const list = form.getByRole('listbox', { name: 'Product for line 1 matches' });
    await expect(list.getByRole('option').first()).toContainText(`${visor.sku}: Blue Eagle FC48 clear visor`);
    await list.getByRole('button').filter({ hasText: visor.sku }).click();

    // Resting the mouse on the chosen product shows its details and stock in the order's warehouse
    await form.getByLabel('Product for line 1').hover();
    const info = page.getByTestId('product-info');
    await expect(info).toContainText('Unit of measure');
    await expect(info).toContainText(`Stock in ${warehouses.main.name}`);
    await expect(info).toContainText('Available in other locations');
    await page.mouse.move(5, 5);
    await expect(info).toHaveCount(0);

    // A product that does not exist yet is added from the list and chosen on the line
    const sku = `NEW-${Date.now()}`;
    await form.getByRole('button', { name: '+ Add line' }).click();
    await form.getByLabel('Product for line 2').click();
    await form.getByRole('button', { name: '+ Add a new item' }).click();
    const dialog = page.getByTestId('quick-new-product');
    await dialog.getByLabel('SKU').fill(sku);
    await dialog.getByLabel('Name').fill('Brand new gloves');
    await dialog.getByLabel('Cost price £').fill('2.5');
    await dialog.getByRole('button', { name: 'Add product' }).click();
    await expect(form.getByLabel('Product for line 2', { exact: true })).toHaveValue(`${sku} — Brand new gloves`);
    // The list stays closed after the new product is chosen
    await expect(form.getByRole('listbox', { name: 'Product for line 2 matches' })).toHaveCount(0);
  });
