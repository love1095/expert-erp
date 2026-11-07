import { test, expect } from '../../fixtures/test';

// Phase P5: with landed cost on, freight on a supplier bill is added to the cost of the stock it priced
test.describe('Landed cost', () => {
  test('LAND-001 landed cost is switched on by value; a bill with £20 freight raises the received stock cost',
    { tag: ['@P1', '@purchase'] }, async ({ page, api, warehouses }) => {
      await page.goto('/stock-adjustments');
      await page.getByLabel('Landed cost').selectOption('VALUE');
      await expect.poll(async () => (await (await api.http.get('/api/stock-adjustments/settings')).json()).landedCost).toBe('VALUE');

      const product = await api.createProduct();
      const po = await (await api.http.post('/api/purchase-orders', { data: { supplierName: 'E2E Supplier', warehouseId: warehouses.main.id,
        lines: [{ productId: product.id, quantity: 10, unitCost: 4 }] } })).json();
      expect((await api.http.post(`/api/purchase-orders/${po.id}/order`)).ok()).toBeTruthy();
      expect((await api.http.post(`/api/purchase-orders/${po.id}/receive`)).ok()).toBeTruthy();
      const bill = await api.http.post(`/api/purchase-orders/${po.id}/bills`, { data: { supplierInvoiceNumber: `LC-${product.sku}`,
        charges: [{ description: 'Freight', unitPrice: 20 }] } });
      expect(bill.ok()).toBeTruthy();

      // £4 + £20 / 10 = £6 a unit
      const stock = await (await api.http.get(`/api/products/${product.id}/stock`)).json();
      expect(Number(stock.costLayers[0].unitCost)).toBe(6);
    });
});
