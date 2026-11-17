import { test, expect, logInAs } from '../../fixtures/test';
import { addUser, apiFor, ErpApi, Session } from '../../fixtures/api';
import { navFor } from '../../../inventory-app/components/nav';

/**
 * Review flow test F8, people and access (docs/review-and-test-plan.md, Part 3), the menu half: for every role —
 * Admin, the starting roles (Manager, Staff, Accountant) and a custom role for each single tick — the menu shows
 * exactly the pages the role may open, and every page it shows opens without a refusal from the server (no 403).
 * The API half is the backend's AccessMatrixFlowTest (docs/access-matrix.csv).
 */

type RoleCase = { name: string; base: 'ADMIN' | 'MANAGER' | 'STAFF'; startingRole?: string; tick?: string };

const PERMISSIONS = ['SALE_VIEW', 'SALE_EDIT', 'SALE_VOID', 'SALE_CHANGE_PRICES', 'SALE_PROFIT', 'SALE_IGNORE_CREDIT_LIMIT', 'CUSTOMERS',
  'CUSTOMER_CREDIT', 'CUSTOMER_PRICES', 'PURCHASE_VIEW', 'PURCHASE_EDIT', 'PURCHASE_VOID', 'PURCHASE_PRICES', 'PRODUCT_VIEW', 'PRODUCT_EDIT',
  'PRODUCT_COST', 'STOCK_VALUE', 'STOCK_ADJUST', 'STOCK_TRANSFER', 'DATA_IMPORT', 'REPORTS_SALE', 'REPORTS_PURCHASE', 'REPORTS_INVENTORY',
  'REPORTS_FINANCIAL', 'ACCOUNTING', 'ADVISER', 'DASHBOARD', 'SETTINGS', 'AUDIT_LOG'];

const ROLES: RoleCase[] = [
  { name: 'Admin', base: 'ADMIN' },
  { name: 'Manager', base: 'MANAGER', startingRole: 'Manager' },
  { name: 'Staff', base: 'STAFF', startingRole: 'Staff' },
  { name: 'Accountant', base: 'STAFF', startingRole: 'Accountant' },
  ...PERMISSIONS.map(p => ({ name: `tick ${p}`, base: 'STAFF' as const, tick: p })),
];

async function userFor(api: ErpApi, r: RoleCase): Promise<Session> {
  if (r.base === 'ADMIN') return api.session;
  const user = await addUser(api, r.base);
  let roleId: number;
  if (r.startingRole) {
    const roles: { id: number; name: string }[] = await (await api.http.get('/api/settings/roles')).json();
    roleId = roles.find(x => x.name === r.startingRole)!.id;
  } else {
    const created = await api.http.post('/api/settings/roles', { data: { name: `Only ${r.tick}`, permissions: [r.tick] } });
    expect(created.ok(), await created.text()).toBeTruthy();
    roleId = (await created.json()).id;
  }
  const assigned = await api.http.put(`/api/settings/users/${user.user.id}/access-role`, { data: { accessRoleId: roleId } });
  expect(assigned.ok(), await assigned.text()).toBeTruthy();
  return user;
}

test.describe('People and access: the menu of every role', () => {
  test.use({ loggedIn: false });
  for (const r of ROLES) {
    test(`ACCESS-MENU ${r.name}: the menu shows what the role may open and each page opens without a refusal`,
      { tag: ['@P1', '@security'] }, async ({ page, api, baseURL }) => {
        test.setTimeout(300_000);
        const session = await userFor(api, r);
        const me = await apiFor(baseURL!, session);
        const { role, permissions } = await (await me.http.get('/api/auth/me')).json();
        await me.dispose();
        const expected = navFor(role, permissions).flatMap(m => m.items ? m.items.map(i => i.href) : [m.href!]);

        await logInAs(page, session);
        const refused: string[] = [];
        let at = '';
        page.on('response', res => {
          if (res.status() === 403 && res.url().includes('/api/')) refused.push(`${at}: ${res.request().method()} ${new URL(res.url()).pathname}`);
        });

        // The menu: exactly the pages the role may open
        await page.goto('/');
        const menu = page.getByRole('navigation', { name: 'Main' });
        await expect(menu).toBeVisible();
        await expect.poll(async () => (await menu.locator('a').evaluateAll(as => as.map(a => a.getAttribute('href')))).sort())
          .toEqual([...expected].sort());

        // Every page it shows opens without a refusal
        for (const href of expected) {
          at = href;
          await page.goto(href);
          await page.waitForLoadState('networkidle');
          await expect(page.locator('h1').first()).toBeVisible();
        }
        expect(refused, 'pages in the menu that the server refuses for this role').toEqual([]);
      });
  }
});
