import { test, expect } from '../../fixtures/test';
import { anonymousApi, apiFor, registerTenant } from '../../fixtures/api';

// API access per organisation, like Cin7 Core: Account ID + Application Key
test.describe('API keys', () => {
  test('API-001 an admin makes a key, another system uses it on this organisation only, then it is revoked', { tag: ['@P1', '@integrations'] },
    async ({ page, warehouses, baseURL }) => {
      // Another organisation's data must never show up
      const other = await apiFor(baseURL!, await registerTenant(baseURL!));
      const hidden = await other.createWarehouse(`Other org ${Date.now()}`);
      await other.dispose();

      await page.goto('/settings/integrations');
      await page.getByTestId('integration-api').click();
      await expect(page.getByRole('heading', { name: 'API' })).toBeVisible();
      const accountId = (await page.getByTestId('api-account-id').innerText()).trim();
      expect(accountId).toMatch(/^[0-9a-f-]{36}$/);

      await page.getByLabel('Name').fill('Warehouse scanner');
      await page.getByLabel('Can do what').selectOption('STAFF');
      await page.getByRole('button', { name: 'Create key' }).click();
      const shown = page.getByTestId('api-key-created');
      await expect(shown).toContainText('not shown again');
      const key = (await page.getByTestId('api-application-key').innerText()).trim();
      expect(key).toMatch(/^ek_/);
      await shown.getByRole('button', { name: 'I have copied it' }).click();
      await expect(page.getByTestId('api-application-key')).toHaveCount(0);
      const row = page.getByTestId('api-keys').getByRole('row', { name: /Warehouse scanner/ });
      await expect(row).toContainText(key.substring(0, 10));
      await expect(row).toContainText('Never');

      // The other system: no login, just the two headers
      const system = await anonymousApi(baseURL!);
      const headers = { 'api-auth-accountid': accountId, 'api-auth-applicationkey': key };
      const res = await system.get('/api/warehouses?size=100', { headers });
      expect(res.status()).toBe(200);
      const names = (await res.json()).content.map((w: { name: string }) => w.name);
      expect(names).toContain(warehouses.main.name);
      expect(names).not.toContain(hidden.name);
      // Account administration needs a person
      expect((await system.get('/api/users', { headers })).status()).toBe(403);

      await page.reload();
      await expect(row).not.toContainText('Never');

      await row.getByRole('button', { name: 'Revoke' }).click();
      await expect(row).toContainText('Revoked');
      expect((await system.get('/api/warehouses', { headers })).status()).toBe(401);
      await system.dispose();
    });
});
