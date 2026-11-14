import { test as base, expect, Locator, Page } from '@playwright/test';
import { apiFor, ErpApi, PlanTier, registerTenant, Session, uid, Warehouse } from './api';

/**
 * Test fixtures. Every test gets its own freshly registered tenant:
 *   - `api`       ADMIN API client for that tenant (seed data, verify results)
 *   - `warehouses` two warehouses created up front (main, second)
 *   - `page`      browser page already logged in as the tenant's ADMIN (set `loggedIn: false` to opt out)
 * Browser confirm() / prompt() dialogs are accepted automatically, and so is the app's own confirm pop-up (review R7):
 * set `autoConfirm: false` to test the pop-up itself.
 */
type Fixtures = {
  plan: PlanTier;
  loggedIn: boolean;
  autoConfirm: boolean;
  session: Session;
  api: ErpApi;
  warehouses: { main: Warehouse; second: Warehouse };
};

/** Presses the pop-up's action button, typing a reason first when it asks for one (R7: reasons in the pop-up). */
export async function acceptConfirm(dialog: Locator) {
  const reason = dialog.locator('textarea');
  if (await reason.count()) await reason.fill('E2E reason');
  await dialog.getByTestId('confirm-ok').click();
}

/**
 * Chooses a product in the type-to-search product box (order lines, and since review R5 the stock pages): types the
 * SKU and clicks its row in the list.
 */
export async function pickProduct(scope: Page | Locator, label: string, product: { sku: string }) {
  await scope.getByLabel(label, { exact: true }).fill(product.sku);
  await scope.getByRole('listbox', { name: `${label} matches` }).getByRole('button').filter({ hasText: product.sku }).first().click();
}

/**
 * Clicks a button that asks for a reason in the app's pop-up (R7: no browser prompt any more), types this reason and
 * confirms. For tests with autoConfirm on (the automatic confirm would type its own "E2E reason").
 */
export async function clickWithReason(page: Page, button: Locator, reason: string) {
  const dialog = page.getByTestId('confirm-dialog');
  await page.removeLocatorHandler(dialog);
  await button.click();
  await dialog.locator('textarea').fill(reason);
  await dialog.getByTestId('confirm-ok').click();
  await expect(dialog).toHaveCount(0);
  await page.addLocatorHandler(dialog, acceptConfirm);
}

export async function logInAs(page: Page, session: Session) {
  // The app keeps its JWT and user profile in localStorage (see inventory-app/lib/auth.ts)
  await page.addInitScript(([token, user]) => {
    localStorage.setItem('erp_token', token);
    localStorage.setItem('erp_user', user);
  }, [session.token, JSON.stringify(session.user)] as const);
}

/**
 * Clicks a button that asks first (review R7) and presses the pop-up's action button. Use it when the next step reads
 * the result through the API (the automatic confirm only runs before a page step). For tests with autoConfirm on.
 */
export async function clickAndConfirm(page: Page, button: Locator) {
  const dialog = page.getByTestId('confirm-dialog');
  await page.removeLocatorHandler(dialog);
  await button.click();
  await acceptConfirm(dialog);
  await expect(dialog).toHaveCount(0);
  await page.addLocatorHandler(dialog, acceptConfirm);
}

export const test = base.extend<Fixtures>({
  plan: ['ENTERPRISE', { option: true }],
  loggedIn: [true, { option: true }],
  autoConfirm: [true, { option: true }],

  session: async ({ baseURL }, use) => {
    await use(await registerTenant(baseURL!));
  },

  api: async ({ baseURL, session, plan }, use) => {
    const api = await apiFor(baseURL!, session);
    if (plan !== 'FREE') await api.upgradePlan(plan);
    await use(api);
    await api.dispose();
  },

  warehouses: async ({ api }, use) => {
    await use({
      main: await api.createWarehouse(`Main ${uid()}`),
      second: await api.createWarehouse(`Second ${uid()}`),
    });
  },

  // Depends on api so the chosen plan is applied before the UI is used
  page: async ({ page, session, api: _api, loggedIn, autoConfirm }, use) => {
    if (loggedIn) await logInAs(page, session);
    page.on('dialog', dialog => dialog.accept());
    // "Ask before doing" (R7): whenever the pop-up is open before a step, press its action button
    if (autoConfirm) await page.addLocatorHandler(page.getByTestId('confirm-dialog'), acceptConfirm);
    await use(page);
  },
});

export { expect };
