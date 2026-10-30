import { expect, Locator, Page } from '@playwright/test';

/**
 * UI steps for the sale order page (/sale-orders/{id}). Tests call these instead of clicking directly,
 * so a change to the page only needs updating here.
 */
export class SaleOrdersPage {
  constructor(private readonly page: Page) {}

  row(orderId: number): Locator {
    return this.page.getByTestId(`so-row-${orderId}`);
  }

  panel(orderId: number): Locator {
    return this.page.getByTestId(`so-panel-${orderId}`);
  }

  async open(orderId: number): Promise<Locator> {
    await this.page.goto('/sale-orders');
    await this.row(orderId).getByRole('link', { name: 'Open ›' }).click();
    const panel = this.panel(orderId);
    await expect(panel.getByRole('table').first()).toBeVisible();
    return panel;
  }

  async confirm(orderId: number) {
    await this.panel(orderId).getByRole('button', { name: 'Confirm order' }).click();
  }

  /** Ships the given quantities per SKU; SKUs not listed ship 0. */
  async ship(orderId: number, qtyBySku: Record<string, number>) {
    const panel = this.panel(orderId);
    await panel.getByRole('button', { name: 'Ship items' }).click();
    for (const input of await panel.getByLabel(/^Ship quantity for /).all()) {
      const sku = (await input.getAttribute('aria-label'))!.replace('Ship quantity for ', '');
      if (await input.isEnabled()) await input.fill(String(qtyBySku[sku] ?? 0));
    }
    await panel.getByRole('button', { name: 'Ship & deduct stock' }).click();
  }

  async cancel(orderId: number) {
    await this.panel(orderId).getByRole('button', { name: /^Cancel (order|remaining)$/ }).click();
  }

  /** Records a return; `restock: false` means the goods are recorded but not put back in stock. */
  async recordReturn(orderId: number, lines: Record<string, { qty: number; restock?: boolean }>, reason?: string) {
    const panel = this.panel(orderId);
    await panel.getByRole('button', { name: 'Record return' }).click();
    for (const [sku, { qty, restock = true }] of Object.entries(lines)) {
      await panel.getByLabel(`Return quantity for ${sku}`).fill(String(qty));
      await panel.getByLabel(`Back to stock for ${sku}`).setChecked(restock);
    }
    if (reason) await panel.getByLabel('Reason').fill(reason);
    await panel.getByRole('button', { name: 'Record return' }).last().click();
  }

  /** Status, shipping state and flags shown under the order's title. */
  status(orderId: number): Locator {
    return this.page.getByTestId(`so-status-${orderId}`);
  }
}
