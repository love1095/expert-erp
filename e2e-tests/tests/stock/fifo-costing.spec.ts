import { test, expect, clickAndConfirm } from '../../fixtures/test';

// Phase E5: FIFO cost layers (docs/phase-e-purchasing.md, docs/business-rules.md section 2)
test('FIFO-001 stock in at two costs, shipping 15 costs the oldest first: £80, the rest is worth £30', { tag: ['@P0', '@stock'] },
  async ({ page, api, warehouses }) => {
    const product = await api.createProduct({ costPrice: 4, sellPrice: 10 });

    // 10 at £5 recorded on screen with its unit cost, then 10 at £6
    await page.goto('/stock-movements');
    await page.getByRole('button', { name: '+ Record Movement' }).click();
    await page.getByLabel('Product').selectOption(String(product.id));
    await page.getByLabel('Warehouse').selectOption(String(warehouses.main.id));
    await page.getByLabel('Type').selectOption('ADJUSTMENT_IN');
    await page.getByLabel('Quantity').fill('10');
    await page.getByLabel('Unit cost (£)').fill('5');
    await clickAndConfirm(page, page.getByRole('button', { name: 'Record Movement', exact: true }));
    await expect.poll(() => api.stock(product, warehouses.main)).toBe(10);
    const res = await api.http.post('/api/stock-movements', {
      data: { productId: product.id, warehouseId: warehouses.main.id, quantity: 10, type: 'ADJUSTMENT_IN', unitCost: 6 },
    });
    expect(res.ok()).toBeTruthy();

    const so = await api.createSaleOrder(warehouses.main, [{ product, quantity: 15 }]);
    await api.confirmSaleOrder(so.id);
    await api.fulfilSaleOrder(so.id);

    await page.goto(`/sale-orders/${so.id}`);
    await expect(page.getByTestId('order-cost-of-goods')).toContainText('£80.00');

    await page.goto(`/products/${product.id}`);
    await page.getByRole('button', { name: 'Stock', exact: true }).click();
    const layers = page.getByTestId('product-cost-layers');
    await expect(layers).toContainText('Cost layers (FIFO): £30.00');
    await expect(layers).toContainText('£6.00');
  });
