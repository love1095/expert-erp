import { readFileSync } from 'fs';
import { join } from 'path';
import { test, expect } from '../../fixtures/test';

/**
 * Review R6: when signed in, only the page area scrolls. The window must never scroll, or the top bar and the menu
 * slide off the top of the screen. Every menu page is opened, scrolled with the mouse wheel, and checked.
 */
const NAV = readFileSync(join(__dirname, '../../../inventory-app/components/nav.ts'), 'utf8');
const PAGES = [...new Set([...NAV.matchAll(/href: "(\/[^"]*)"/g)].map(m => m[1]))].filter(h => !h.startsWith('/platform'));

for (const size of [{ name: 'desktop', width: 1500, height: 800 }, { name: 'phone', width: 390, height: 844 }]) {
  test(`LAY-00${size.name === 'desktop' ? 1 : 2} the window does not scroll on any menu page (${size.name})`, { tag: ['@P2', '@layout'] }, async ({ page, api, warehouses }) => {
    test.setTimeout(240_000);
    const product = await api.createProduct();
    const customer = await api.createCustomer();
    for (let i = 0; i < 3; i++) await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }], customer);
    await page.setViewportSize({ width: size.width, height: size.height });

    const bad: string[] = [];
    for (const url of PAGES) {
      await page.goto(url);
      await expect(page.locator('main')).toBeVisible();
      await page.waitForLoadState('networkidle');
      await page.mouse.move(size.width - 20, size.height / 2);
      for (let i = 0; i < 5; i++) await page.mouse.wheel(0, 600);
      await page.waitForTimeout(200);
      const r = await page.evaluate(() => ({ y: window.scrollY, h: document.documentElement.scrollHeight }));
      if (r.y !== 0 || r.h > size.height + 1) bad.push(`${url}: window scrolled ${r.y}px, ${r.h}px tall`);
    }
    expect(bad).toEqual([]);
  });
}
