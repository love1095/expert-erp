import { test, expect } from '../../fixtures/test';
import { SaleOrdersPage } from '../../fixtures/pages/sale-orders';
import { connectFakeShop, sendWebhook, shopifyOrder } from '../../fixtures/shopify';

// Uses a made-up store: saving it calls Shopify once (the store does not exist), hence @external.

test.describe('Shopify order import', () => {
  test('SHP-004 a paid Shopify order arrives as a confirmed sale order that can ship', { tag: ['@P0', '@shopify', '@integrations', '@external'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct({ name: 'Helmet' });
      await api.addStock(helmet, warehouses.main, 20);
      const shop = await connectFakeShop(api);
      const orderNo = Date.now() % 1_000_000_000;

      const res = await sendWebhook(shop, 'orders/create', shopifyOrder(orderNo, [{ sku: helmet.sku, title: 'Helmet', quantity: 3, price: '25.00' }],
        { customer: { id: orderNo, first: 'Ada', last: 'Lovelace', email: `ada-${orderNo}@buyer.test` } }));
      expect(res.status()).toBe(200);

      await page.goto('/sale-orders');
      const row = page.locator('[data-testid^="so-row-"]', { hasText: `Shopify #${orderNo}` });
      await expect(row).toContainText('Ada Lovelace');
      await expect(row).toContainText('CONFIRMED');
      await expect(row).not.toContainText('Needs review');

      // It ships like any other order
      const id = Number((await row.getAttribute('data-testid'))!.replace('so-row-', ''));
      const so = new SaleOrdersPage(page);
      await so.open(id);
      await so.ship(id, { [helmet.sku]: 3 });
      await expect(so.status(id)).toContainText('COMPLETED');
      expect(await api.stock(helmet, warehouses.main)).toBe(17);

      // The customer was created from Shopify
      await page.goto('/customers');
      const customer = page.locator('[data-testid^="customer-row-"]', { hasText: `ada-${orderNo}@buyer.test` });
      await expect(customer).toContainText('Ada Lovelace');
      await expect(customer).toContainText('Shopify');

      // The delivery is listed in the Audit Log, Shopify webhooks tab
      await page.goto('/audit-log?tab=shopify-webhooks');
      await expect(page.getByTestId('shopify-events')).toContainText('orders/create');
      await expect(page.getByTestId('shopify-events')).toContainText('PROCESSED');
    });

  test('SHP-005 an order with an unknown SKU is kept as a draft that needs review; a repeat delivery adds nothing', { tag: ['@P1', '@shopify', '@integrations', '@external'] },
    async ({ page, api, warehouses: _warehouses }) => {
      const helmet = await api.createProduct({ name: 'Helmet' });
      const shop = await connectFakeShop(api);
      const orderNo = Date.now() % 1_000_000_000;
      const payload = shopifyOrder(orderNo, [
        { sku: helmet.sku, title: 'Helmet', quantity: 1, price: '25.00' },
        { sku: 'NOT-IN-ERP', title: 'Mystery box', quantity: 2, price: '3.00' },
      ]);

      expect((await sendWebhook(shop, 'orders/create', payload, { eventId: `evt-${orderNo}` })).status()).toBe(200);
      expect((await sendWebhook(shop, 'orders/create', payload, { eventId: `evt-${orderNo}` })).status()).toBe(200);

      await page.goto('/sale-orders');
      const rows = page.locator('[data-testid^="so-row-"]', { hasText: `Shopify #${orderNo}` });
      await expect(rows).toHaveCount(1);
      await expect(rows).toContainText('DRAFT');
      await expect(rows).toContainText('Needs review');

      const id = Number((await rows.getAttribute('data-testid'))!.replace('so-row-', ''));
      await rows.getByRole('link', { name: 'Open ›' }).click();
      await expect(page.getByTestId(`so-review-${id}`)).toContainText('SKU NOT-IN-ERP is not in the ERP');

      await page.getByTestId(`so-panel-${id}`).getByRole('button', { name: 'Confirm as it is' }).click();
      const status = page.getByTestId(`so-status-${id}`);
      await expect(status).toContainText('CONFIRMED');
      await expect(status).not.toContainText('Needs review');
    });

  test('SHP-008 without any warehouse the order is refused (500, so Shopify retries) and the reason is shown', { tag: ['@P2', '@shopify', '@integrations', '@external'] },
    async ({ page, api }) => {
      // No `warehouses` fixture: this tenant has none
      const helmet = await api.createProduct();
      const shop = await connectFakeShop(api);
      const res = await sendWebhook(shop, 'orders/create',
        shopifyOrder(Date.now() % 1_000_000_000, [{ sku: helmet.sku, title: 'Helmet', quantity: 1, price: '1.00' }]));
      expect(res.status()).toBe(500);

      await page.goto('/settings/integrations/shopify');
      await page.getByRole('link', { name: 'Audit Log → Shopify webhooks' }).click();
      await expect(page.getByRole('tab', { name: 'Shopify webhooks' })).toHaveAttribute('aria-selected', 'true');
      await expect(page.getByTestId('shopify-events')).toContainText('FAILED');
      await expect(page.getByTestId('shopify-events')).toContainText('No warehouse exists');
    });

  test('SHP-009 shipping a Shopify order sends it to Shopify; if Shopify is unreachable the shipment stands and can be retried', { tag: ['@P1', '@shopify', '@integrations', '@external'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct({ name: 'Helmet' });
      await api.addStock(helmet, warehouses.main, 10);
      const shop = await connectFakeShop(api);   // a made-up store: Shopify cannot be reached for it
      const orderNo = Date.now() % 1_000_000_000;
      const order = shopifyOrder(orderNo, [{ sku: helmet.sku, title: 'Helmet', quantity: 2, price: '25.00' }]);
      (order.line_items[0] as Record<string, unknown>).id = 777;
      expect((await sendWebhook(shop, 'orders/create', order)).status()).toBe(200);

      await page.goto('/sale-orders');
      const row = page.locator('[data-testid^="so-row-"]', { hasText: `Shopify #${orderNo}` });
      const id = Number((await row.getAttribute('data-testid'))!.replace('so-row-', ''));
      await row.getByRole('link', { name: 'Open ›' }).click();
      const panel = page.getByTestId(`so-panel-${id}`);
      await panel.getByRole('button', { name: 'Ship items' }).click();
      await expect(panel.getByText('also sent to Shopify')).toBeVisible();
      await panel.getByLabel('Carrier').fill('Royal Mail');
      await panel.getByLabel('Tracking number').fill('RM123456789GB');
      await panel.getByRole('button', { name: 'Ship & deduct stock' }).click();

      // The ERP side is done: stock left, order completed, tracking recorded
      await expect(page.getByTestId(`so-status-${id}`)).toContainText('COMPLETED', { timeout: 30_000 });
      expect(await api.stock(helmet, warehouses.main)).toBe(8);
      await expect(panel).toContainText('Royal Mail RM123456789GB');

      const sync = panel.locator('[data-testid^="shipment-sync-"]');
      await expect(sync).toContainText('Not sent to Shopify');
      await expect(sync.getByRole('button', { name: 'Retry' })).toBeVisible();

      // Retrying cannot succeed for a made-up store, but it must not ship twice
      await sync.getByRole('button', { name: 'Retry' }).click();
      await expect(sync).toContainText('Not sent to Shopify', { timeout: 30_000 });
      expect(await api.stock(helmet, warehouses.main)).toBe(8);
    });

  test('SHP-006 a webhook with a wrong signature is refused and creates nothing', { tag: ['@P0', '@shopify', '@integrations', '@security', '@external'] },
    async ({ api }) => {
      const helmet = await api.createProduct();
      const shop = await connectFakeShop(api);
      const orderNo = Date.now() % 1_000_000_000;

      const forged = await sendWebhook(shop, 'orders/create',
        shopifyOrder(orderNo, [{ sku: helmet.sku, title: 'Helmet', quantity: 1, price: '1.00' }]), { secret: 'not-the-secret' });
      expect(forged.status()).toBe(401);

      const unknownShop = await sendWebhook({ domain: 'nobody-here.myshopify.com', secret: shop.secret }, 'orders/create',
        shopifyOrder(orderNo, [{ sku: helmet.sku, title: 'Helmet', quantity: 1, price: '1.00' }]));
      expect(unknownShop.status()).toBe(401);

      const orders = await (await api.http.get('/api/sale-orders', { params: { size: 50 } })).json();
      expect(orders.totalElements).toBe(0);
      expect(await (await api.http.get('/api/integrations/shopify/events')).json()).toEqual([]);
    });

  test('SHP-007 order settings: warehouse and public address; webhooks need an https address', { tag: ['@P2', '@shopify', '@integrations', '@external'] },
    async ({ page, api, warehouses }) => {
      await connectFakeShop(api);
      await page.goto('/settings/integrations/shopify');
      const orders = page.getByTestId('shopify-orders');
      await expect(orders.getByTestId('shopify-webhooks-state')).toContainText('Not registered');
      await expect(orders.getByRole('button', { name: 'Register webhooks' })).toBeDisabled();

      await orders.getByLabel('Ship Shopify orders from').selectOption(String(warehouses.second.id));
      await orders.getByLabel('Public address (for webhooks)').fill('http://insecure.example.com');
      await orders.getByRole('button', { name: 'Save order settings' }).click();
      await expect(orders.getByTestId('shopify-orders-message')).toContainText('https://');

      await orders.getByLabel('Public address (for webhooks)').fill('https://e2e-tunnel.trycloudflare.com/');
      await orders.getByRole('button', { name: 'Save order settings' }).click();
      await expect(orders.getByTestId('shopify-orders-message')).toContainText('Order settings saved');
      await expect(orders.getByTestId('shopify-webhooks-state')).toContainText('Click Register webhooks');

      const conn = await (await api.http.get('/api/integrations/shopify')).json();
      expect(conn).toMatchObject({
        defaultWarehouseId: warehouses.second.id,
        publicUrl: 'https://e2e-tunnel.trycloudflare.com',
        webhookUrl: 'https://e2e-tunnel.trycloudflare.com/api/webhooks/shopify',
      });
    });
});
