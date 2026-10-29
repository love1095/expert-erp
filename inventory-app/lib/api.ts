import api from "@/lib/apiInstance";
import type { FulfilmentRequest, SaleReturnRequest } from "@/types";

// Normalises a backend response that may be a flat array (old) or a Page object (new)
const toPage = (data: any) =>
  Array.isArray(data)
    ? { content: data, totalPages: 1, totalElements: data.length, size: data.length, number: 0, first: true, last: true }
    : data;

// Products — array for dropdowns (fetches all)
export const getProducts    = (params?: { categoryId?: number }) =>
  api.get("/products", { params: { size: 9999, ...params } }).then(r => r.data.content);
// Products — Page for the products list view
export const getProductsPage = (params?: { categoryId?: number; page?: number; size?: number }) =>
  api.get("/products", { params: { size: 20, ...params } }).then(r => r.data);

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
export const getSaleOrders    = (params?: { page?: number; size?: number }) =>
  api.get("/sale-orders", { params: { size: 20, ...params } }).then(r => r.data);
export const getSaleOrder     = (id: number) => api.get(`/sale-orders/${id}`).then(r => r.data);
export const createSaleOrder  = (data: unknown) => api.post("/sale-orders", data).then(r => r.data);
export const confirmSaleOrder = (id: number) => api.post(`/sale-orders/${id}/confirm`).then(r => r.data);
// No body = ship everything remaining
export const fulfillSaleOrder = ({ id, data }: { id: number; data?: FulfilmentRequest }) =>
  api.post(`/sale-orders/${id}/fulfill`, data).then(r => r.data);
export const cancelSaleOrder  = (id: number) => api.post(`/sale-orders/${id}/cancel`).then(r => r.data);
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

// Subscription / Plans
export const getSubscription = () => api.get("/subscription").then(r => r.data);
export const getPlans        = () => api.get("/plans").then(r => r.data);
export const upgradePlan     = (planTier: string) => api.post("/subscription/upgrade", { planTier }).then(r => r.data);
