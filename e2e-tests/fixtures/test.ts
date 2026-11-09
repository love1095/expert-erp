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
  await dialog.getByTestId('confirm-ok').click();
  await expect(dialog).toHaveCount(0);
  await page.addLocatorHandler(dialog, d => d.getByTestId('confirm-ok').click());
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
    if (autoConfirm) await page.addLocatorHandler(page.getByTestId('confirm-dialog'), dialog => dialog.getByTestId('confirm-ok').click());
    await use(page);
  },
});

export { expect };
