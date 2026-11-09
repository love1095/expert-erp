import { test, expect } from '../../fixtures/test';

// Phase G1 (spec §5): price tiers and contract prices price order lines that have no typed price
test.describe('Customer pricing', () => {
  test('PRICE-001 tiers are named in settings; a contract price beats the tier; lines say where their price came from', { tag: ['@P1', '@customers'] },
    async ({ page, api, warehouses }) => {
      const gloves = await api.createProduct({ name: 'Gloves', sellPrice: 4.5 });
      const boots = await api.createProduct({ name: 'Boots', sellPrice: 40 });
      // Tier 2 price on the gloves (Cin7's PriceTier2)
      await api.http.put(`/api/products/${gloves.id}`, { data: { ...gloves, sellPrice: 4.5, tradePrice: 3.6 } });

      // Settings: the second tier is renamed Contract
      await page.goto('/settings/organisation');
      const tiers = page.getByTestId('price-tiers');
      await expect(tiers.getByLabel('Tier 1 name')).toHaveValue('Retail');
      await tiers.getByLabel('Tier 2 name').fill('Contract');
      await tiers.getByRole('button', { name: 'Save price tiers' }).click();
      await expect(tiers.getByRole('status')).toContainText('Saved');

      const customer = await api.createCustomer({ name: `Loudfoot ${Date.now()}` });
      await api.http.put(`/api/customers/${customer.id}`, { data: { ...customer, priceTier: 'Contract' } });

      // Customer page: a contract price for the boots
      await page.goto(`/customers/${customer.id}`);
      const card = page.getByTestId('customer-contract-prices');
      await expect(card).toContainText('Price tier: Contract');
      await card.getByLabel('Contract price product').fill(boots.sku);
      await page.getByRole('listbox', { name: 'Contract price product matches' }).getByRole('button').filter({ hasText: boots.sku }).click();
      await card.getByLabel('Contract price', { exact: true }).fill('32.50');
      await card.getByLabel('Contract note').fill('2026 framework');
      await card.getByRole('button', { name: 'Add contract price' }).click();
      await expect(card).toContainText('£32.50');
      await expect(card).toContainText('2026 framework');

      // An order with no typed prices: gloves at the Contract tier, boots at the contract price
      const res = await api.http.post('/api/sale-orders', { data: { customerId: customer.id, warehouseId: warehouses.main.id,
        lines: [{ productId: gloves.id, quantity: 2 }, { productId: boots.id, quantity: 1 }] } });
      expect(res.status()).toBe(201);
      const order = await res.json();
      expect(order.lines.map((l: { unitPrice: number; priceSource: string }) => [Number(l.unitPrice), l.priceSource]))
        .toEqual([[3.6, 'Contract tier'], [32.5, 'Contract price']]);

      await page.goto(`/sale-orders/${order.id}`);
      await expect(page.getByTestId(`so-line-price-source-${order.lines[1].id}`)).toHaveText('Contract price');
      await expect(page.getByTestId(`so-line-price-source-${order.lines[0].id}`)).toHaveText('Contract tier');

      // On a new order the tier starts as the customer's and can be changed for that order (like Cin7)
      await page.goto('/sale-orders');
      await page.getByRole('link', { name: '+ New SO' }).click();
      await page.getByLabel('Customer *').fill(customer.name);
      await page.getByLabel('Ship from Warehouse *').selectOption(String(warehouses.main.id));
      await page.getByLabel('Product for line 1').fill(gloves.sku);
      await page.getByRole('listbox', { name: 'Product for line 1 matches' }).getByRole('button').filter({ hasText: gloves.sku }).click();
      await page.getByPlaceholder('Qty').fill('2');
      await expect(page.getByLabel('Price tier')).toContainText("Contract (customer's)");
      await expect(page.getByTestId('so-form-price-source-0')).toHaveText('Contract tier price £3.60');
      await page.getByLabel('Price tier').selectOption('Retail');
      await expect(page.getByTestId('so-form-price-source-0')).toHaveText('Retail price £4.50');
      await page.getByRole('button', { name: 'Create Sale Order' }).click();
      await expect(page).toHaveURL(/\/sale-orders\/\d+$/);
    });
});
