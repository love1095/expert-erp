import { test as base, expect, Page } from '@playwright/test';
import { apiFor, ErpApi, PlanTier, registerTenant, Session, uid, Warehouse } from './api';

/**
 * Test fixtures. Every test gets its own freshly registered tenant:
 *   - `api`       ADMIN API client for that tenant (seed data, verify results)
 *   - `warehouses` two warehouses created up front (main, second)
 *   - `page`      browser page already logged in as the tenant's ADMIN (set `loggedIn: false` to opt out)
 * Browser confirm() dialogs are accepted automatically.
 */
type Fixtures = {
  plan: PlanTier;
  loggedIn: boolean;
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

export const test = base.extend<Fixtures>({
  plan: ['ENTERPRISE', { option: true }],
  loggedIn: [true, { option: true }],

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
  page: async ({ page, session, api: _api, loggedIn }, use) => {
    if (loggedIn) await logInAs(page, session);
    page.on('dialog', dialog => dialog.accept());
    await use(page);
  },
});

export { expect };
