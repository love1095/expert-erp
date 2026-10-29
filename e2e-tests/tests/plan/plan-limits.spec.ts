import { test, expect } from '../../fixtures/test';
import { uid } from '../../fixtures/api';

test.describe('Plan limits (FREE plan)', () => {
  test.use({ plan: 'FREE' });

  test('PLAN-001 the 51st product is blocked with an upgrade message', { tag: ['@P1', '@plan'] }, async ({ page, api }) => {
    for (let i = 0; i < 50; i++) await api.createProduct();

    await page.goto('/products');
    await page.getByRole('button', { name: '+ Add Product' }).click();
    await page.getByLabel('SKU').fill(`E2E-${uid()}`);
    await page.getByLabel('Name').fill('One too many');
    await page.getByLabel('Unit').fill('pcs');
    await page.getByRole('button', { name: 'Save Product' }).click();

    await expect(page.getByText(/Plan limit reached: .*50.*FREE/i)).toBeVisible();
  });

  test('PLAN-002 a third warehouse returns HTTP 402', { tag: ['@P1', '@plan'] }, async ({ api, warehouses }) => {
    expect(warehouses.second).toBeTruthy(); // FREE allows 2
    const res = await api.http.post('/api/warehouses', { data: { name: `E2E WH ${uid()}` } });

    expect(res.status()).toBe(402);
    expect(await res.json()).toMatchObject({ upgradeRequired: true, planTier: 'FREE' });
  });
});
