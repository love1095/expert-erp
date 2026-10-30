import { test, expect } from '../../fixtures/test';

// Cin7's list actions: tick sales or purchases, then Fill and authorise / Undo / Void / Export them
test.describe('List actions', () => {
  test('SO-014 ticked sales are authorised, undone back to draft with Cin7\'s warning, and voided', { tag: ['@P1', '@sale-orders'] },
    async ({ page, api, warehouses }) => {
      const helmet = await api.createProduct();
      await api.addStock(helmet, warehouses.main, 10);
      // One customer's orders only, so other tests' orders don't push them off the page
      const customer = await api.createCustomer();
      const a = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 1 }], customer);
      const b = await api.createSaleOrder(warehouses.main, [{ product: helmet, quantity: 2 }], customer);
      const status = async (id: number) => (await api.getSaleOrder(id)).status;

      await page.goto(`/sale-orders?customer=${customer.id}`);
      await page.getByTestId(`so-select-${a.id}`).check();
      await page.getByTestId(`so-select-${b.id}`).check();
      const bar = page.getByTestId('bulk-actions');
      await expect(bar).toContainText('2 selected');
      // Print and e-mail come later
      await expect(bar.getByRole('button', { name: 'Print' })).toBeDisabled();

      await bar.getByRole('button', { name: 'Fill and authorise' }).click();
      await expect(page.getByTestId('bulk-result')).toContainText('Fill and authorise: 2 done');
      expect(await status(a.id)).toBe('CONFIRMED');
      expect(await status(b.id)).toBe('CONFIRMED');
      // Made as orders: no quote step
      await expect(page.getByTestId(`so-stages-${a.id}`).locator('[data-stage="quote"]')).toHaveAttribute('data-state', 'na');

      // Undo asks with Cin7's words; one shipped order is refused and the other goes back to draft
      await api.fulfilSaleOrder(b.id);
      await page.reload();
      await page.getByTestId(`so-select-${a.id}`).check();
      await page.getByTestId(`so-select-${b.id}`).check();
      // (the fixture accepts confirm dialogs)
      let asked = '';
      page.once('dialog', d => { asked = d.message(); });
      await bar.getByRole('button', { name: 'Undo' }).click();
      const result = page.getByTestId('bulk-result');
      await expect(result).toContainText('Undo: 1 done, 1 not done');
      await expect(result).toContainText(b.orderNumber);
      expect(await status(a.id)).toBe('DRAFT');
      expect(asked).toBe('This will change the task status back to draft. Would you like to proceed anyway?');

      // Export downloads the ticked rows
      await page.getByTestId(`so-select-${a.id}`).check();
      const download = page.waitForEvent('download');
      await bar.getByRole('button', { name: 'Export' }).click();
      const csv = await (await download).createReadStream().then(async s => {
        let text = '';
        for await (const chunk of s) text += chunk;
        return text;
      });
      expect(csv).toContain('Order #,Status,Customer');
      expect(csv).toContain(a.orderNumber);

      await bar.getByRole('button', { name: 'Void' }).click();
      await expect(page.getByTestId('bulk-result')).toContainText('Void: 1 done');
      expect(await status(a.id)).toBe('CANCELLED');
      await expect(page.getByTestId(`so-stages-${a.id}`).locator('[data-stage="ship"]')).toHaveAttribute('data-state', 'na');
    });

  test('PO-003 ticked purchase orders are authorised and undone back to draft', { tag: ['@P1', '@purchase-orders'] },
    async ({ page, api, warehouses }) => {
      const gloves = await api.createProduct();
      const po = await api.createPurchaseOrder(warehouses.main, [{ product: gloves, quantity: 3 }]) as { id: number; orderNumber: string };
      const status = async () => (await api.http.get(`/api/purchase-orders/${po.id}`).then(r => r.json())).status;

      await page.goto(`/purchase-orders?q=${po.orderNumber}`);
      await page.getByTestId(`po-select-${po.id}`).check();
      const bar = page.getByTestId('bulk-actions');
      await bar.getByRole('button', { name: 'Fill and authorise' }).click();
      await expect(page.getByTestId('bulk-result')).toContainText('Fill and authorise: 1 done');
      expect(await status()).toBe('ORDERED');

      await page.getByTestId(`po-select-${po.id}`).check();
      await bar.getByRole('button', { name: 'Undo' }).click();
      await expect(page.getByTestId('bulk-result')).toContainText('Undo: 1 done');
      expect(await status()).toBe('DRAFT');
    });
});
