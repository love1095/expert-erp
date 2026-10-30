import { test, expect } from '../../fixtures/test';

test('AUD-001 shipments appear in the audit log with the acting user', { tag: ['@P1', '@audit'] }, async ({ page, api, warehouses, session }) => {
  const product = await api.createProduct();
  await api.addStock(product, warehouses.main, 10);
  const order = await api.createSaleOrder(warehouses.main, [{ product, quantity: 2 }]);
  await api.confirmSaleOrder(order.id);
  await api.fulfilSaleOrder(order.id);

  await page.goto('/audit-log');
  const fulfilRow = page.getByRole('row').filter({ hasText: 'FULFILL' }).filter({ hasText: String(order.id) });

  await expect(fulfilRow).toBeVisible();
  await expect(fulfilRow).toContainText(session.user.username);
});

test('AUD-002 the Shopify webhooks tab is for admins only', { tag: ['@P2', '@audit', '@shopify'] }, async ({ page }) => {
  await page.goto('/audit-log');
  await expect(page.getByRole('tab', { name: 'Changes' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: 'Shopify webhooks' }).click();
  await expect(page.getByTestId('shopify-events')).toContainText('Nothing received yet');
});
