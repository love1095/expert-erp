import { test, expect } from '../../fixtures/test';
import { SaleOrdersPage } from '../../fixtures/pages/sale-orders';
import { ErpApi, Product, Warehouse } from '../../fixtures/api';

async function seedOrder(api: ErpApi, wh: Warehouse, helmets: number, gloves: number, stockEach = 100) {
  const helmet = await api.createProduct({ name: 'Helmet' });
  const glove = await api.createProduct({ name: 'Gloves' });
  await api.addStock(helmet, wh, stockEach);
  await api.addStock(glove, wh, stockEach);
  const order = await api.createSaleOrder(wh, [{ product: helmet, quantity: helmets }, { product: glove, quantity: gloves }]);
  return { order, helmet, glove };
}

const lineOf = (order: { lines: { id: number; productId: number }[] }, p: Product) =>
  order.lines.find(l => l.productId === p.id)!.id;

test.describe('Sale order lifecycle', () => {
  test('SO-001 a draft must be confirmed before it can ship', { tag: ['@P0', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order } = await seedOrder(api, warehouses.main, 10, 20);
    const so = new SaleOrdersPage(page);

    const panel = await so.open(order.id);
    await expect(panel.getByRole('button', { name: 'Ship items' })).toHaveCount(0);

    await so.confirm(order.id);

    await expect(so.status(order.id)).toContainText('CONFIRMED');
    await expect(panel.getByRole('button', { name: 'Ship items' })).toBeVisible();
  });

  test('SO-002 partial shipments deduct only the shipped quantities', { tag: ['@P0', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet, glove } = await seedOrder(api, warehouses.main, 10, 20);
    await api.confirmSaleOrder(order.id);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.ship(order.id, { [helmet.sku]: 10, [glove.sku]: 5 });

    await expect(so.status(order.id)).toContainText('Partly shipped');
    expect(await api.stock(helmet, warehouses.main)).toBe(90);
    expect(await api.stock(glove, warehouses.main)).toBe(95);

    await so.ship(order.id, { [glove.sku]: 15 });

    await expect(so.status(order.id)).toContainText('COMPLETED');
    await expect(so.status(order.id)).toContainText('Shipped');
    expect(await api.stock(glove, warehouses.main)).toBe(80);
    await expect(so.panel(order.id).getByText(/Shipment #/)).toHaveCount(2);
  });

  test('SO-003 shipping more than stock shows an error and changes nothing', { tag: ['@P0', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet, glove } = await seedOrder(api, warehouses.main, 10, 150);
    await api.confirmSaleOrder(order.id);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.ship(order.id, { [helmet.sku]: 10, [glove.sku]: 150 });

    await expect(so.panel(order.id).getByText(/Insufficient stock/)).toBeVisible();
    expect(await api.stock(helmet, warehouses.main)).toBe(100);
    expect(await api.stock(glove, warehouses.main)).toBe(100);
    expect((await api.getSaleOrder(order.id)).fulfilmentStatus).toBe('UNFULFILLED');
  });

  test('SO-004 cancelling after a partial shipment keeps shipped quantities', { tag: ['@P0', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet } = await seedOrder(api, warehouses.main, 10, 20);
    await api.confirmSaleOrder(order.id);
    await api.fulfilSaleOrder(order.id, [{ lineId: lineOf(order, helmet), quantity: 4 }]);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.cancel(order.id);

    await expect(so.status(order.id)).toContainText('COMPLETED');
    await expect(so.status(order.id)).toContainText('Partly shipped');
    const after = await api.getSaleOrder(order.id);
    const helmetLine = after.lines.find(l => l.productId === helmet.id)!;
    expect(helmetLine.fulfilledQuantity).toBe(4);
    expect(helmetLine.cancelledQuantity).toBe(6);
    expect(await api.stock(helmet, warehouses.main)).toBe(96);
  });

  test('SO-005 cancelling a draft cancels the whole order', { tag: ['@P1', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet } = await seedOrder(api, warehouses.main, 10, 20);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.cancel(order.id);

    await expect(so.status(order.id)).toContainText('CANCELLED');
    expect(await api.stock(helmet, warehouses.main)).toBe(100);
  });

  test('SO-006 restocked returns add stock, damaged returns do not', { tag: ['@P0', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet, glove } = await seedOrder(api, warehouses.main, 10, 20);
    await api.confirmSaleOrder(order.id);
    await api.fulfilSaleOrder(order.id);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.recordReturn(order.id, {
      [helmet.sku]: { qty: 3, restock: true },
      [glove.sku]: { qty: 2, restock: false },
    }, 'Customer return');

    await expect(so.panel(order.id).getByText(/Return #/)).toBeVisible();
    expect(await api.stock(helmet, warehouses.main)).toBe(93); // 100 - 10 + 3
    expect(await api.stock(glove, warehouses.main)).toBe(80);  // damaged: not restocked
  });

  test('SO-007 cannot return more than was shipped', { tag: ['@P1', '@sale-orders'] }, async ({ page, api, warehouses }) => {
    const { order, helmet } = await seedOrder(api, warehouses.main, 10, 20);
    await api.confirmSaleOrder(order.id);
    await api.fulfilSaleOrder(order.id, [{ lineId: lineOf(order, helmet), quantity: 2 }]);
    const so = new SaleOrdersPage(page);
    await so.open(order.id);

    await so.recordReturn(order.id, { [helmet.sku]: { qty: 5 } });

    // The browser's own validation (input max) blocks the submit
    const input = so.panel(order.id).getByLabel(`Return quantity for ${helmet.sku}`);
    expect(await input.evaluate((el: HTMLInputElement) => el.validity.rangeOverflow)).toBe(true);
    await expect(so.panel(order.id).getByText(/Return #/)).toHaveCount(0);
    expect((await api.getSaleOrder(order.id)).lines.find(l => l.productId === helmet.id)!.returnedQuantity).toBe(0);
    expect(await api.stock(helmet, warehouses.main)).toBe(98);
  });
});
