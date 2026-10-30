import { APIRequestContext, APIResponse, request } from '@playwright/test';

/** Typed client for the Expert ERP REST API, used to seed test data and check results. */

export type PlanTier = 'FREE' | 'PRO' | 'ENTERPRISE';

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: 'SUPER_ADMIN' | 'ADMIN' | 'MANAGER' | 'STAFF';
  tenantId: number;
  tenantName: string;
  tenantSlug: string;
}

export interface Session {
  token: string;
  user: AuthUser;
  password: string;
}

export interface Product { id: number; sku: string; name: string }
export interface Warehouse { id: number; name: string }

export interface SaleOrderLine {
  id: number;
  productId: number;
  productSku: string;
  quantity: number;
  fulfilledQuantity: number;
  cancelledQuantity: number;
  returnedQuantity: number;
  remainingQuantity: number;
}

export interface CustomerContact { name?: string; email?: string; phone?: string; defaultContact: boolean }
export interface Customer {
  id: number; name: string; status: 'ACTIVE' | 'DEPRECATED'; orderCount: number; source: 'MANUAL' | 'SHOPIFY';
  paymentTerm?: string; creditLimit?: number; contacts: CustomerContact[];
}

export interface SaleOrder {
  id: number;
  orderNumber: string;
  salesRep?: string;
  subtotal: number;
  taxTotal: number;
  total: number;
  customerId: number;
  customerName: string;
  status: 'DRAFT' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED';
  fulfilmentStatus: 'UNFULFILLED' | 'PARTIALLY_FULFILLED' | 'FULFILLED';
  lines: SaleOrderLine[];
}

export interface Dashboard {
  from: string;
  to: string;
  days: number;
  kpis: Record<'products' | 'warehouses' | 'customers' | 'stockValue' | 'productsToReorder' | 'ordersToShip' | 'draftOrders'
    | 'ordersInPeriod' | 'salesValueInPeriod' | 'unitsShippedInPeriod' | 'receivablesOutstanding', number>;
  sales: Record<'draftOrders' | 'toShip' | 'draftInvoices' | 'awaitingPayment' | 'overdueInvoices', number>;
  purchases: Record<'awaitingDelivery' | 'draftInvoices' | 'awaitingPayment' | 'overdueInvoices', number>;
  daily: { date: string; orders: number; salesValue: number; unitsIn: number; unitsOut: number }[];
  topSellers: { sku: string; quantity: number; value: number }[];
  worstSellers: { sku: string; quantity: number; value: number }[];
}

export const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;

async function ok<T>(res: APIResponse, what: string): Promise<T> {
  if (!res.ok()) {
    throw new Error(`${what} failed: HTTP ${res.status()} ${await res.text()}`);
  }
  return res.json() as Promise<T>;
}

export class ErpApi {
  constructor(readonly http: APIRequestContext, readonly session: Session) {}

  // ---- products & stock ----

  async createProduct(overrides: Partial<{ sku: string; name: string; unit: string; costPrice: number; sellPrice: number; minStock: number }> = {}): Promise<Product> {
    const id = uid();
    return ok(await this.http.post('/api/products', {
      data: { sku: `E2E-${id}`, name: `E2E product ${id}`, unit: 'pcs', ...overrides },
    }), 'create product');
  }

  async createWarehouse(name = `E2E WH ${uid()}`): Promise<Warehouse> {
    return ok(await this.http.post('/api/warehouses', { data: { name } }), 'create warehouse');
  }

  /** Adds stock with an ADJUSTMENT_IN movement. */
  async addStock(product: Product, warehouse: Warehouse, quantity: number): Promise<void> {
    await ok(await this.http.post('/api/stock-movements', {
      data: { productId: product.id, warehouseId: warehouse.id, quantity, type: 'ADJUSTMENT_IN', notes: 'e2e seed' },
    }), 'add stock');
  }

  async stock(product: Product, warehouse: Warehouse): Promise<number> {
    const res = await ok<{ stock: number }>(
      await this.http.get('/api/stock', { params: { productId: product.id, warehouseId: warehouse.id } }),
      'get stock');
    return Number(res.stock);
  }

  // ---- purchase orders ----

  async createPurchaseOrder(warehouse: Warehouse, lines: { product: Product; quantity: number }[]): Promise<{ id: number }> {
    return ok(await this.http.post('/api/purchase-orders', {
      data: {
        supplierName: 'E2E Supplier',
        warehouseId: warehouse.id,
        lines: lines.map(l => ({ productId: l.product.id, quantity: l.quantity, unitCost: 1 })),
      },
    }), 'create purchase order');
  }

  // ---- customers ----

  async createCustomer(overrides: Partial<{ name: string; email: string }> = {}): Promise<Customer> {
    const { email, ...rest } = overrides;
    return ok(await this.http.post('/api/customers', {
      data: { name: `E2E Customer ${uid()}`, contacts: email ? [{ email, defaultContact: true }] : [], ...rest },
    }), 'create customer');
  }

  async getCustomer(id: number): Promise<Customer> {
    return ok(await this.http.get(`/api/customers/${id}`), 'get customer');
  }

  async findCustomers(search: string): Promise<Customer[]> {
    const page = await ok<{ content: Customer[] }>(
      await this.http.get('/api/customers', { params: { search, size: 100 } }), 'search customers');
    return page.content;
  }

  // ---- sale orders ----

  /** Pass a customer to order for it; otherwise the order goes to a customer named "E2E Customer". */
  async createSaleOrder(warehouse: Warehouse, lines: { product: Product; quantity: number }[], customer?: Customer): Promise<SaleOrder> {
    return ok(await this.http.post('/api/sale-orders', {
      data: {
        ...(customer ? { customerId: customer.id } : { customerName: 'E2E Customer' }),
        warehouseId: warehouse.id,
        lines: lines.map(l => ({ productId: l.product.id, quantity: l.quantity, unitPrice: 10 })),
      },
    }), 'create sale order');
  }

  async confirmSaleOrder(id: number): Promise<SaleOrder> {
    return ok(await this.http.post(`/api/sale-orders/${id}/confirm`), 'confirm sale order');
  }

  async fulfilSaleOrder(id: number, lines?: { lineId: number; quantity: number }[]): Promise<SaleOrder> {
    return ok(await this.http.post(`/api/sale-orders/${id}/fulfill`, {
      data: lines ? { lines: lines.map(l => ({ saleOrderLineId: l.lineId, quantity: l.quantity })) } : undefined,
    }), 'fulfil sale order');
  }

  async getSaleOrder(id: number): Promise<SaleOrder> {
    return ok(await this.http.get(`/api/sale-orders/${id}`), 'get sale order');
  }

  async dashboard(days = 30): Promise<Dashboard> {
    return ok(await this.http.get('/api/dashboard', { params: { days } }), 'get dashboard');
  }

  async dashboardReorder(search?: string): Promise<{ totalElements: number; content: { sku: string }[] }> {
    return ok(await this.http.get('/api/dashboard/reorder', { params: search ? { search } : {} }), 'get reorder list');
  }

  async upgradePlan(planTier: PlanTier): Promise<void> {
    await ok(await this.http.post('/api/subscription/upgrade', { data: { planTier } }), 'upgrade plan');
  }

  async dispose() {
    await this.http.dispose();
  }
}

/** API context with no credentials (login, register, unauthenticated checks). */
export function anonymousApi(baseURL: string) {
  return request.newContext({ baseURL });
}

export async function apiFor(baseURL: string, session: Session): Promise<ErpApi> {
  const http = await request.newContext({
    baseURL,
    extraHTTPHeaders: { Authorization: `Bearer ${session.token}` },
  });
  return new ErpApi(http, session);
}

interface LoginResponse {
  token: string; userId: number; username: string; email: string; role: AuthUser['role'];
  tenantId: number; tenantName: string; tenantSlug: string;
}

function toSession(r: LoginResponse, password: string): Session {
  return {
    token: r.token,
    password,
    user: {
      id: r.userId, username: r.username, email: r.email, role: r.role,
      tenantId: r.tenantId, tenantName: r.tenantName, tenantSlug: r.tenantSlug,
    },
  };
}

/** Signs up a brand-new organisation (tenant) with its first ADMIN user. */
export async function registerTenant(baseURL: string): Promise<Session> {
  const id = uid();
  const password = `E2e-${id}-pw`;
  const anon = await anonymousApi(baseURL);
  try {
    const res = await anon.post('/api/auth/register', {
      data: { organizationName: `E2E Org ${id}`, username: `e2e_${id}`, email: `e2e_${id}@example.test`, password },
    });
    return toSession(await ok<LoginResponse>(res, 'register tenant'), password);
  } finally {
    await anon.dispose();
  }
}

/** Adds a user with the given role to the session's tenant (needs an ADMIN session). */
export async function addUser(api: ErpApi, role: AuthUser['role']): Promise<Session> {
  const id = uid();
  const password = `E2e-${id}-pw`;
  const res = await api.http.post('/api/auth/register', {
    data: { username: `e2e_${role.toLowerCase()}_${id}`, email: `e2e_${id}@example.test`, password, role },
  });
  return toSession(await ok<LoginResponse>(res, `add ${role} user`), password);
}
