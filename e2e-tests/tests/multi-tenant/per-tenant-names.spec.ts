import { test, expect } from '../../fixtures/test';
import { apiFor, registerTenant, uid } from '../../fixtures/api';

/**
 * Names and codes only need to be unique within a tenant. Two companies must be able to use
 * the same SKU, warehouse name, category or supplier name.
 */
const cases = [
  { id: 'MT-001', what: 'product SKU', path: '/api/products', body: (v: string) => ({ sku: v, name: 'Shared', unit: 'pcs' }) },
  { id: 'MT-002', what: 'warehouse name', path: '/api/warehouses', body: (v: string) => ({ name: v }) },
  { id: 'MT-003', what: 'category name', path: '/api/categories', body: (v: string) => ({ name: v }) },
  { id: 'MT-004', what: 'supplier name', path: '/api/suppliers', body: (v: string) => ({ name: v }) },
];

for (const c of cases) {
  test(`${c.id} two tenants can use the same ${c.what}`, { tag: ['@P1', '@multi-tenant'] }, async ({ api, baseURL }) => {
    const value = `E2E-shared-${uid()}`;
    const first = await api.http.post(c.path, { data: c.body(value) });
    expect(first.ok(), await first.text()).toBeTruthy();

    const other = await apiFor(baseURL!, await registerTenant(baseURL!));
    try {
      const second = await other.http.post(c.path, { data: c.body(value) });
      expect(second.ok(), `second tenant got HTTP ${second.status()}`).toBeTruthy();
    } finally {
      await other.dispose();
    }
  });
}
