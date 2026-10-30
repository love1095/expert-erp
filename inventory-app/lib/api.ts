import api from "@/lib/apiInstance";
import type { FulfilmentRequest, SaleReturnRequest } from "@/types";

// Normalises a backend response that may be a flat array (old) or a Page object (new)
const toPage = (data: any) =>
  Array.isArray(data)
    ? { content: data, totalPages: 1, totalElements: data.length, size: data.length, number: 0, first: true, last: true }
    : data;

// Products — every active product, minimal fields, for dropdowns
export const getProducts    = (params?: { status?: "ACTIVE" | "DEPRECATED" | "ALL" }) =>
  api.get("/products/options", { params }).then(r => r.data);
// Products — Page for the products list view; status omitted = all, q = part of SKU or name
export const getProductsPage = (params?: { categoryId?: number; status?: string; q?: string; page?: number; size?: number }) =>
  api.get("/products", { params: { size: 20, ...params } }).then(r => r.data);
// Cin7 Core InventoryList CSV; deprecateMissing marks active products not in the file as deprecated
export const importProducts = ({ file, deprecateMissing }: { file: File; deprecateMissing: boolean }) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/products/import", body, {
    params: { deprecateMissing },
    headers: { "Content-Type": "multipart/form-data" },
    // ~15,000 rows take a while
    timeout: 10 * 60 * 1000,
  }).then(r => r.data);
};
export const exportProducts = (params?: { status?: string }) =>
  api.get("/products/export", { params, responseType: "blob" }).then(r => r.data);

export const getProduct     = (id: number) => api.get(`/products/${id}`).then(r => r.data);
export const createProduct  = (data: unknown) => api.post("/products", data).then(r => r.data);
export const updateProduct  = (id: number, data: unknown) => api.put(`/products/${id}`, data).then(r => r.data);
export const deleteProduct  = (id: number) => api.delete(`/products/${id}`);

// Categories
export const getCategories  = () => api.get("/categories").then(r => r.data);

// Warehouses
export const getWarehouses     = () => api.get("/warehouses", { params: { size: 9999 } }).then(r => Array.isArray(r.data) ? r.data : r.data.content);
export const getWarehousesPage = (params?: { page?: number; size?: number }) =>
  api.get("/warehouses", { params: { size: 20, ...params } }).then(r => toPage(r.data));
export const createWarehouse = (data: unknown) => api.post("/warehouses", data).then(r => r.data);
export const updateWarehouse = (id: number, data: unknown) => api.put(`/warehouses/${id}`, data).then(r => r.data);
export const deleteWarehouse = (id: number) => api.delete(`/warehouses/${id}`);

// Suppliers
export const getSuppliers     = () => api.get("/suppliers", { params: { size: 9999 } }).then(r => Array.isArray(r.data) ? r.data : r.data.content);
export const getSuppliersPage = (params?: { search?: string; page?: number; size?: number }) =>
  api.get("/suppliers", { params: { size: 20, ...params } }).then(r => toPage(r.data));
export const createSupplier = (data: unknown) => api.post("/suppliers", data).then(r => r.data);
export const updateSupplier = (id: number, data: unknown) => api.put(`/suppliers/${id}`, data).then(r => r.data);
export const deleteSupplier = (id: number) => api.delete(`/suppliers/${id}`).then(r => r.data);

// Customers
export const getCustomers     = () => api.get("/customers", { params: { size: 9999 } }).then(r => r.data.content);
export const getCustomersPage = (params?: { search?: string; page?: number; size?: number }) =>
  api.get("/customers", { params: { size: 20, ...params } }).then(r => r.data);
export const createCustomer = (data: unknown) => api.post("/customers", data).then(r => r.data);
export const updateCustomer = ({ id, data }: { id: number; data: unknown }) => api.put(`/customers/${id}`, data).then(r => r.data);
export const deleteCustomer = (id: number) => api.delete(`/customers/${id}`);
// Cin7 Core customer export (CSV)
export const importCustomers = (file: File) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/customers/import", body, { headers: { "Content-Type": "multipart/form-data" } }).then(r => r.data);
};

// Stock movements — array for dropdowns (fetches all)
export const getMovements   = (params?: { productId?: number; warehouseId?: number }) =>
  api.get("/stock-movements", { params: { size: 9999, ...params } }).then(r => r.data.content);
// Stock movements — Page for list views
export const getMovementsPage = (params?: { productId?: number; warehouseId?: number; categoryId?: number; page?: number; size?: number }) =>
  api.get("/stock-movements", { params: { size: 20, ...params } }).then(r => r.data);

export const createMovement = (data: unknown) => api.post("/stock-movements", data).then(r => r.data);
export const createTransfer = (data: unknown) => api.post("/stock-movements/transfer", data).then(r => r.data);
export const exportMovementsCsv = (params?: { productId?: number; warehouseId?: number }) =>
  api.get("/stock-movements/export/csv", { params, responseType: "blob" }).then(r => r.data);

// Stock levels
export const getStockOverview = () => api.get("/stock/overview").then(r => r.data);
export const getStockOverviewPage = (params?: { categoryId?: number; page?: number; size?: number }) =>
  api.get("/stock/overview", { params: { size: 20, ...params } }).then(r => toPage(r.data));
export const getStockLevel    = (productId: number, warehouseId: number) =>
  api.get("/stock", { params: { productId, warehouseId } }).then(r => r.data);

// Purchase orders — Page
export const getPurchaseOrders    = (params?: { page?: number; size?: number }) =>
  api.get("/purchase-orders", { params: { size: 20, ...params } }).then(r => r.data);
export const getPurchaseOrder     = (id: number) => api.get(`/purchase-orders/${id}`).then(r => r.data);
export const createPurchaseOrder  = (data: unknown) => api.post("/purchase-orders", data).then(r => r.data);
export const receivePurchaseOrder = (id: number) => api.post(`/purchase-orders/${id}/receive`).then(r => r.data);
export const cancelPurchaseOrder  = (id: number) => api.post(`/purchase-orders/${id}/cancel`).then(r => r.data);

// Sale orders — Page
export const getSaleOrders    = (params?: { customerId?: number; status?: string; page?: number; size?: number }) =>
  api.get("/sale-orders", { params: { size: 20, ...params } }).then(r => r.data);
export const getSaleOrder     = (id: number) => api.get(`/sale-orders/${id}`).then(r => r.data);
export const createSaleOrder  = (data: unknown) => api.post("/sale-orders", data).then(r => r.data);
// Quotes and draft orders only
export const updateSaleOrder  = ({ id, data }: { id: number; data: unknown }) => api.put(`/sale-orders/${id}`, data).then(r => r.data);
export const convertQuote     = (id: number) => api.post(`/sale-orders/${id}/convert`).then(r => r.data);
export const rejectQuote      = (id: number) => api.post(`/sale-orders/${id}/reject`).then(r => r.data);
export const confirmSaleOrder = (id: number) => api.post(`/sale-orders/${id}/confirm`).then(r => r.data);
// No body = ship everything remaining
export const fulfillSaleOrder = ({ id, data }: { id: number; data?: FulfilmentRequest }) =>
  api.post(`/sale-orders/${id}/fulfill`, data).then(r => r.data);
export const cancelSaleOrder  = (id: number) => api.post(`/sale-orders/${id}/cancel`).then(r => r.data);
// Send a shipment of a Shopify order to Shopify again
export const retryShopifyFulfilment = ({ id, fulfilmentId }: { id: number; fulfilmentId: number }) =>
  api.post(`/sale-orders/${id}/fulfilments/${fulfilmentId}/shopify-sync`).then(r => r.data);
export const createSaleReturn = ({ id, data }: { id: number; data: SaleReturnRequest }) =>
  api.post(`/sale-orders/${id}/returns`, data).then(r => r.data);

// Auth / Users
export const getUsersPage  = (params?: { page?: number; size?: number }) =>
  api.get("/auth/users", { params: { size: 20, ...params } }).then(r => toPage(r.data));
export const addUser       = (data: unknown) => api.post("/auth/register", data).then(r => r.data);
export const getCurrentUser = () => api.get("/auth/me").then(r => r.data);

// Audit log
export const getAuditLog = (params?: { entityType?: string; action?: string; username?: string; page?: number; size?: number }) =>
  api.get("/audit-log", { params: { size: 50, ...params } }).then(r => r.data);

// Invoices
export const getInvoices = (params?: { type?: string; page?: number; size?: number }) =>
  api.get("/invoices", { params: { size: 20, ...params } }).then(r => r.data);
export const getInvoice     = (id: number) => api.get(`/invoices/${id}`).then(r => r.data);
export const createInvoice  = (data: unknown) => api.post("/invoices", data).then(r => r.data);
export const issueInvoice   = (id: number) => api.post(`/invoices/${id}/issue`).then(r => r.data);
export const cancelInvoice  = (id: number) => api.post(`/invoices/${id}/cancel`).then(r => r.data);
export const payInvoice     = (id: number, data: unknown) => api.post(`/invoices/${id}/pay`, data).then(r => r.data);

// Cin7 stock file → stock in one warehouse set to the file's OnHand; apply=false only previews
export const importStock = ({ file, warehouseId, apply }: { file: File; warehouseId: number; apply: boolean }) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/stock/import", body, {
    params: { warehouseId, apply },
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 5 * 60 * 1000,
  }).then(r => r.data);
};

// Sale order as it would be created (rep, VAT, totals) — nothing saved
export const previewSaleOrder = (data: unknown) => api.post("/sale-orders/preview", data).then(r => r.data);
export const getTaxRates = (usage: "SALE" | "PURCHASE" = "SALE") => api.get("/tax-rates", { params: { usage } }).then(r => r.data);
export const getOrganisationSettings    = () => api.get("/settings/organisation").then(r => r.data);
export const updateOrganisationSettings = (data: unknown) => api.put("/settings/organisation", data).then(r => r.data);

// Dashboard
export const getDashboard = (period: { from: string; to: string }) => api.get("/dashboard", { params: period }).then(r => r.data);
export const getDashboardReorder = (params: { search?: string; page?: number; size?: number }) =>
  api.get("/dashboard/reorder", { params }).then(r => r.data);

// Shopify connection (ADMIN) — GET returns 204 / empty when no store is connected
export const getShopifyConnection    = () => api.get("/integrations/shopify").then(r => (r.status === 204 ? null : r.data));
export const saveShopifyConnection   = (data: { shopDomain: string; clientId: string; clientSecret: string }) =>
  api.put("/integrations/shopify", data).then(r => r.data);
export const testShopifyConnection   = () => api.post("/integrations/shopify/test").then(r => r.data);
export const deleteShopifyConnection = () => api.delete("/integrations/shopify");
export const saveShopifySettings     = (data: { defaultWarehouseId: number | null; publicUrl: string }) =>
  api.put("/integrations/shopify/settings", data).then(r => r.data);
export const registerShopifyWebhooks = () => api.post("/integrations/shopify/webhooks").then(r => r.data);
export const importShopifyOrders     = (days: number) => api.post("/integrations/shopify/import", null, { params: { days } }).then(r => r.data);
// Send all ERP products (details, price, stock) to Shopify; runs in the background — poll the GET (204 = never run)
export const syncShopifyProducts     = () => api.post("/integrations/shopify/products/sync").then(r => r.data);
export const getShopifyProductSync   = () => api.get("/integrations/shopify/products/sync").then(r => (r.status === 204 ? null : r.data));
export const getShopifyEvents        = () => api.get("/integrations/shopify/events", { params: { size: 20 } }).then(r => r.data);

// Subscription / Plans
export const getSubscription = () => api.get("/subscription").then(r => r.data);
export const getPlans        = () => api.get("/plans").then(r => r.data);
export const upgradePlan     = (planTier: string) => api.post("/subscription/upgrade", { planTier }).then(r => r.data);
