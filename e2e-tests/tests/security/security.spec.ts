import { test, expect } from '../../fixtures/test';
import { addUser, anonymousApi, apiFor, registerTenant, uid } from '../../fixtures/api';

test.describe('Security', () => {
  test('SEC-001 a tenant cannot read or change another tenant\'s data', { tag: ['@P0', '@security', '@multi-tenant'] },
    async ({ api, warehouses, baseURL }) => {
      const product = await api.createProduct();
      await api.addStock(product, warehouses.main, 10);
      const order = await api.createSaleOrder(warehouses.main, [{ product, quantity: 1 }]);

      const intruder = await apiFor(baseURL!, await registerTenant(baseURL!));
      try {
        expect((await intruder.http.get(`/api/products/${product.id}`)).status()).toBe(404);
        expect((await intruder.http.get(`/api/sale-orders/${order.id}`)).status()).toBe(404);
        expect((await intruder.http.post(`/api/sale-orders/${order.id}/confirm`)).status()).toBe(404);
        expect((await intruder.http.get('/api/stock',
          { params: { productId: product.id, warehouseId: warehouses.main.id } })).status()).toBe(404);

        const list = await (await intruder.http.get('/api/products', { params: { size: 100 } })).json();
        expect(list.content.map((p: { sku: string }) => p.sku)).not.toContain(product.sku);
      } finally {
        await intruder.dispose();
      }
      // The owner's data is untouched
      expect((await api.getSaleOrder(order.id)).status).toBe('DRAFT');
    });

  test('SEC-002 STAFF cannot delete or manage users', { tag: ['@P0', '@security'] }, async ({ api, baseURL }) => {
    const product = await api.createProduct();
    const staff = await apiFor(baseURL!, await addUser(api, 'STAFF'));
    try {
      expect((await staff.http.get('/api/products')).status()).toBe(200);
      expect((await staff.http.delete(`/api/products/${product.id}`)).status()).toBe(403);
      expect((await staff.http.get('/api/auth/users')).status()).toBe(403);
      expect((await staff.http.post('/api/subscription/upgrade', { data: { planTier: 'PRO' } })).status()).toBe(403);
    } finally {
      await staff.dispose();
    }
  });

  test('SEC-003 sign-up cannot grant itself SUPER_ADMIN', { tag: ['@P0', '@security'] }, async ({ baseURL }) => {
    const anon = await anonymousApi(baseURL!);
    try {
      const id = uid();
      const res = await anon.post('/api/auth/register', {
        data: { organizationName: `E2E Org ${id}`, username: `e2e_${id}`, email: `e2e_${id}@example.test`,
                password: `E2e-${id}-pw`, role: 'SUPER_ADMIN' },
      });
      expect(res.ok(), await res.text()).toBeTruthy();
      const body = await res.json();
      expect(body.role, 'the founder of a new organisation is its ADMIN, whatever role was requested').toBe('ADMIN');
      const tenants = await anon.get('/api/tenants', { headers: { Authorization: `Bearer ${body.token}` } });
      expect(tenants.status(), 'listing all tenants must be forbidden').toBe(403);
    } finally {
      await anon.dispose();
    }
  });

  test('SEC-005 an ADMIN cannot create SUPER_ADMIN users or unknown roles', { tag: ['@P0', '@security'] }, async ({ api }) => {
    const id = uid();
    const superAdmin = await api.http.post('/api/auth/register', {
      data: { username: `e2e_sa_${id}`, email: `e2e_sa_${id}@example.test`, password: `E2e-${id}-pw`, role: 'SUPER_ADMIN' },
    });
    expect(superAdmin.status()).toBe(403);

    const unknown = await api.http.post('/api/auth/register', {
      data: { username: `e2e_x_${id}`, email: `e2e_x_${id}@example.test`, password: `E2e-${id}-pw`, role: 'GOD' },
    });
    expect(unknown.status()).toBe(400);
  });

  test('SEC-004 API rejects requests without a token', { tag: ['@P0', '@security'] }, async ({ baseURL }) => {
    const anon = await anonymousApi(baseURL!);
    try {
      // Spring answers 403 for a missing token (401 only for an invalid one); either way it is rejected
      expect([401, 403]).toContain((await anon.get('/api/products')).status());
      expect([401, 403]).toContain((await anon.get('/api/sale-orders')).status());
    } finally {
      await anon.dispose();
    }
  });
});
