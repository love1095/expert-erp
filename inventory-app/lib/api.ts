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
// Stock per warehouse and on open purchase orders (the product page's Stock section)
export const getProductStock = (id: number) => api.get(`/products/${id}/stock`).then(r => r.data);
// Cin7's More actions → Delete deprecated products (only those without history)
export const deleteDeprecatedProducts = () => api.post("/products/delete-deprecated").then(r => r.data);

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
export const getCustomer    = (id: number) => api.get(`/customers/${id}`).then(r => r.data);
export const createCustomer = (data: unknown) => api.post("/customers", data).then(r => r.data);
export const updateCustomer = ({ id, data }: { id: number; data: unknown }) => api.put(`/customers/${id}`, data).then(r => r.data);
export const deleteCustomer = (id: number) => api.delete(`/customers/${id}`);
// Cin7 Core customer export (CSV)
// Cin7's customer files; a file of another type than the one chosen is refused
export type CustomerFileType = "CUSTOMERS" | "ADDRESSES" | "CONTACTS" | "CUSTOM_PRICES";
export const importCustomers = ({ file, type }: { file: File; type: CustomerFileType }) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/customers/import", body, { params: { type }, headers: { "Content-Type": "multipart/form-data" } }).then(r => r.data);
};
// Same files, in Cin7's columns; the file name comes from the server (e.g. CustomerAddresses_2026-11-04.csv)
export const exportCustomers = (type: CustomerFileType) =>
  api.get("/customers/export", { params: { type }, responseType: "blob" }).then(r => ({
    blob: r.data as Blob,
    fileName: /filename="([^"]+)"/.exec(String(r.headers["content-disposition"] ?? ""))?.[1] ?? `customers_${type.toLowerCase()}.csv`,
  }));

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
export const getStockOverviewPage = (params?: { categoryId?: number; q?: string; page?: number; size?: number; activeOnly?: boolean; nonZero?: boolean }) =>
  api.get("/stock/overview", { params: { size: 20, ...params } }).then(r => toPage(r.data));
export const getStockLevel    = (productId: number, warehouseId: number) =>
  api.get("/stock", { params: { productId, warehouseId } }).then(r => r.data);

// Purchase orders — Page
export const getPurchaseOrders    = (params?: { status?: string; q?: string; page?: number; size?: number }) =>
  api.get("/purchase-orders", { params: { size: 20, ...params } }).then(r => r.data);
export const getPurchaseOrder     = (id: number) => api.get(`/purchase-orders/${id}`).then(r => r.data);
export const createPurchaseOrder  = (data: unknown) => api.post("/purchase-orders", data).then(r => r.data);
export const receivePurchaseOrder = (id: number) => api.post(`/purchase-orders/${id}/receive`).then(r => r.data);
export const cancelPurchaseOrder  = (id: number) => api.post(`/purchase-orders/${id}/cancel`).then(r => r.data);
export const undoPurchaseOrder    = (id: number) => api.post(`/purchase-orders/${id}/undo`).then(r => r.data);
export const previewPurchaseOrder = (data: unknown) => api.post("/purchase-orders/preview", data).then(r => r.data);
export const updatePurchaseOrder  = ({ id, data }: { id: number; data: unknown }) => api.put(`/purchase-orders/${id}`, data).then(r => r.data);
// Placed with the supplier (Cin7's Authorise)
export const orderPurchaseOrder   = (id: number) => api.post(`/purchase-orders/${id}/order`).then(r => r.data);
// A receipt: the quantities that arrived (more than outstanding is accepted and flagged)
export const receivePurchaseLines = ({ id, data }: { id: number; data: unknown }) => api.post(`/purchase-orders/${id}/receipts`, data).then(r => r.data);
// The supplier's bill for the order (three-way match with the PO and the receipts)
export const billPurchaseOrder    = ({ id, data }: { id: number; data: unknown }) => api.post(`/purchase-orders/${id}/bills`, data).then(r => r.data);
// Nothing more is coming: the rest is closed short
export const closeShortPurchaseOrder = (id: number) => api.post(`/purchase-orders/${id}/close-short`).then(r => r.data);

// Sale orders — Page
export const getSaleOrders    = (params?: { customerId?: number; status?: string; q?: string; page?: number; size?: number }) =>
  api.get("/sale-orders", { params: { size: 20, ...params } }).then(r => r.data);
export const getSaleOrder     = (id: number) => api.get(`/sale-orders/${id}`).then(r => r.data);
export const createSaleOrder  = (data: unknown) => api.post("/sale-orders", data).then(r => r.data);
// Quotes and draft orders only
export const updateSaleOrder  = ({ id, data }: { id: number; data: unknown }) => api.put(`/sale-orders/${id}`, data).then(r => r.data);
// Credit note & restock for a sales invoice
export const createCreditNote = ({ id, data }: { id: number; data: unknown }) => api.post(`/invoices/${id}/credit-notes`, data).then(r => r.data);
// Invoice (part of) an order; no lines = everything not invoiced yet
export const invoiceSaleOrder = ({ id, data }: { id: number; data: unknown }) => api.post(`/sale-orders/${id}/invoices`, data).then(r => r.data);
export const convertQuote     = (id: number) => api.post(`/sale-orders/${id}/convert`).then(r => r.data);
export const rejectQuote      = (id: number) => api.post(`/sale-orders/${id}/reject`).then(r => r.data);
export const confirmSaleOrder = (id: number, creditOverride?: string) =>
  api.post(`/sale-orders/${id}/confirm`, creditOverride ? { creditOverride } : undefined).then(r => r.data);
// No body = ship everything remaining
export const fulfillSaleOrder = ({ id, data }: { id: number; data?: FulfilmentRequest }) =>
  api.post(`/sale-orders/${id}/fulfill`, data).then(r => r.data);
export const cancelSaleOrder  = (id: number) => api.post(`/sale-orders/${id}/cancel`).then(r => r.data);
// Back to draft (Cin7's Undo)
export const undoSaleOrder    = (id: number) => api.post(`/sale-orders/${id}/undo`).then(r => r.data);
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
export const getAuditLog = (params?: { entityType?: string; entityId?: number; action?: string; username?: string; page?: number; size?: number }) =>
  api.get("/audit-log", { params: { size: 50, ...params } }).then(r => r.data);

// Invoices
export const getInvoices = (params?: { type?: string; status?: string; overdue?: boolean; q?: string; page?: number; size?: number }) =>
  api.get("/invoices", { params: { size: 20, ...params } }).then(r => r.data);
export const getInvoice     = (id: number) => api.get(`/invoices/${id}`).then(r => r.data);
export const createInvoice  = (data: unknown) => api.post("/invoices", data).then(r => r.data);
export const issueInvoice   = (id: number) => api.post(`/invoices/${id}/issue`).then(r => r.data);
export const cancelInvoice  = (id: number) => api.post(`/invoices/${id}/cancel`).then(r => r.data);
export const payInvoice     = (id: number, data: unknown) => api.post(`/invoices/${id}/pay`, data).then(r => r.data);

// Cin7 stock file → stock in one warehouse set to the file's OnHand; apply=false only previews
// Phase K1: chart of accounts, general ledger, manual journals, conversion balances, ledger reports
export const getLedgerAccounts = (archived = false) => api.get("/ledger/accounts", { params: { archived } }).then(r => r.data);
export const getLedgerAccountTypes = () => api.get("/ledger/account-types").then(r => r.data);
export const saveLedgerAccount = (id: number | null, body: object) =>
  (id ? api.put(`/ledger/accounts/${id}`, body) : api.post("/ledger/accounts", body)).then(r => r.data);
export const deleteLedgerAccount = (id: number) => api.delete(`/ledger/accounts/${id}`).then(r => r.data);
export const importLedgerAccounts = (file: File, apply: boolean) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/ledger/accounts/import", body, { params: { apply }, headers: { "Content-Type": "multipart/form-data" } }).then(r => r.data);
};
export const getLedgerSettings = () => api.get("/ledger/settings").then(r => r.data);
export const saveLedgerSettings = (body: object) => api.put("/ledger/settings", body).then(r => r.data);
export const getOpeningBalances = () => api.get("/ledger/opening-balances").then(r => r.data);
export const saveOpeningBalances = (body: object[]) => api.put("/ledger/opening-balances", body).then(r => r.data);
export const getManualJournals = (status?: string) => api.get("/ledger/manual-journals", { params: { status } }).then(r => r.data);
export const saveManualJournal = (id: number | null, body: object) =>
  (id ? api.put(`/ledger/manual-journals/${id}`, body) : api.post("/ledger/manual-journals", body)).then(r => r.data);
export const postManualJournal = (id: number) => api.post(`/ledger/manual-journals/${id}/post`).then(r => r.data);
export const voidManualJournal = (id: number) => api.post(`/ledger/manual-journals/${id}/void`).then(r => r.data);
export const getTrialBalance = (date: string) => api.get("/ledger/trial-balance", { params: { date } }).then(r => r.data);
export const getAccountTransactions = (account: string, from: string, to: string) =>
  api.get("/ledger/account-transactions", { params: { account, from, to } }).then(r => r.data);
export const getJournalReport = (params: { from?: string; to?: string; source?: string }) =>
  api.get("/ledger/journals", { params }).then(r => r.data);

// Phase K2: Profit and Loss, Balance Sheet, financial year end and lock dates
export const getLedgerPeriods = () => api.get("/ledger/periods").then(r => r.data);
export const saveLedgerPeriods = (body: object) => api.put("/ledger/periods", body).then(r => r.data);
export const getProfitAndLoss = (params: { from: string; to: string; compare: number; step: string }) =>
  api.get("/ledger/profit-and-loss", { params }).then(r => r.data);
export const getBalanceSheet = (params: { date: string; compare: number; step: string }) =>
  api.get("/ledger/balance-sheet", { params }).then(r => r.data);

// Phase K3: bank accounts, statements, reconciliation, spend / receive money
export const getBankAccounts = () => api.get("/bank/accounts").then(r => r.data);
export const importBankStatement = (code: string, file: File, apply: boolean) => {
  const body = new FormData();
  body.append("file", file);
  return api.post(`/bank/accounts/${encodeURIComponent(code)}/statements`, body, { params: { apply }, headers: { "Content-Type": "multipart/form-data" } }).then(r => r.data);
};
export const getBankLines = (code: string, status: "UNRECONCILED" | "RECONCILED") =>
  api.get(`/bank/accounts/${encodeURIComponent(code)}/lines`, { params: { status } }).then(r => r.data);
export const getBankUnmatched = (code: string) => api.get(`/bank/accounts/${encodeURIComponent(code)}/unmatched`).then(r => r.data);
export const matchBankLine = (id: number, sourceKeys: string[]) => api.post(`/bank/lines/${id}/match`, { sourceKeys }).then(r => r.data);
export const createForBankLine = (id: number, body: object) => api.post(`/bank/lines/${id}/create`, body).then(r => r.data);
export const transferBankLine = (id: number, accountCode: string) => api.post(`/bank/lines/${id}/transfer`, { accountCode }).then(r => r.data);
export const unreconcileBankLine = (id: number) => api.post(`/bank/lines/${id}/unreconcile`).then(r => r.data);
export const deleteBankLine = (id: number) => api.delete(`/bank/lines/${id}`).then(r => r.data);
export const getBankTransactions = (account?: string) => api.get("/bank/transactions", { params: { account } }).then(r => r.data);
export const createBankTransaction = (body: object) => api.post("/bank/transactions", body).then(r => r.data);
export const voidBankTransaction = (id: number) => api.post(`/bank/transactions/${id}/void`).then(r => r.data);

// Phase K4: VAT returns and Making Tax Digital
export const getMtdStatus = () => api.get("/vat/mtd").then(r => r.data);
export const setMtdVrn = (vrn: string) => api.put("/vat/mtd/vrn", { vrn }).then(r => r.data);
export const connectMtd = () => api.post("/vat/mtd/connect").then(r => r.data as { url: string });
export const mtdCallback = (code: string, state: string) => api.post("/vat/mtd/callback", { code, state }).then(r => r.data);
export const disconnectMtd = () => api.delete("/vat/mtd").then(r => r.data);
export const getMtdObligations = (client: object) => api.post("/vat/mtd/obligations", { client }).then(r => r.data);
export const getVatReview = (from: string, to: string) => api.get("/vat/review", { params: { from, to } }).then(r => r.data);
export const submitVatReturn = (body: object) => api.post("/vat/mtd/submit", body).then(r => r.data);
export const recordVatReturn = (body: object) => api.post("/vat/returns/manual", body).then(r => r.data);
export const getFiledVatReturns = () => api.get("/vat/returns").then(r => r.data);

// Phase K5: exports for the accountant (CSV that opens in Excel) and the year-end pack
export const ledgerExport = (name: string, params: object) =>
  api.get(`/ledger/export/${name}`, { params, responseType: "blob" }).then(r => r.data as Blob);

// Phase J: Cin7's sale / purchase task CSV import (preview with apply=false), its logs, and the go-live check
export const importTasks = ({ kind, file, options, apply }:
  { kind: "sales" | "purchases"; file: File; options: Record<string, string | number | boolean | undefined>; apply: boolean }) => {
  const body = new FormData();
  body.append("file", file);
  return api.post(`/import/${kind}`, body, {
    params: { ...options, apply },
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 5 * 60 * 1000,
  }).then(r => r.data);
};
export const getImportLogs = () => api.get("/import/logs").then(r => r.data);
export const getImportTemplate = (kind: "sales" | "purchases") =>
  api.get(`/import/${kind}/template`, { responseType: "blob" }).then(r => r.data as Blob);
export const getCutoverCheck = () => api.get("/cutover/check").then(r => r.data);

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
// Reorder screen: products at or below their minimum by supplier, raised as one draft PO per supplier
export const getReorder = (params: { search?: string; supplierId?: number; noSupplier?: boolean; page?: number; size?: number }) =>
  api.get("/reorder", { params }).then(r => r.data);
export const getReorderSuppliers = () => api.get("/reorder/suppliers").then(r => r.data);
export const raiseReorderPurchaseOrders = (data: unknown) => api.post("/reorder/purchase-orders", data).then(r => r.data);

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
export const getShopifyEvents        = (status?: string) =>
  api.get("/integrations/shopify/events", { params: { size: status ? 100 : 20, status } }).then(r => r.data);
// 7.5: run a failed webhook event now; the daily check now; how the link is doing (204 = no store)
export const retryShopifyEvent       = (id: number) => api.post(`/integrations/shopify/events/${id}/retry`).then(r => r.data);
export const reconcileShopify        = () => api.post("/integrations/shopify/reconcile").then(r => r.data);
export const getShopifyHealth        = () => api.get("/integrations/shopify/health").then(r => (r.status === 204 ? null : r.data));

// Subscription / Plans
// null for the super admin (no organisation, no subscription: 204)
export const getSubscription = () => api.get("/subscription").then(r => r.data || null);
export const getPlans        = () => api.get("/plans").then(r => r.data);

// ---- Documents as PDF (D5) ----
// One document: /sale-orders/{id}/pdf, /invoices/{id}/pdf, /purchase-orders/{id}/pdf
export const getPdf = (path: string) => api.get(path, { responseType: "blob" }).then(r => r.data as Blob);
// Ticked rows of a list in one PDF
export const getDocumentsPdf = (kind: "sale-orders" | "invoices" | "purchase-orders", ids: number[]) =>
  api.get(`/documents/${kind}/pdf`, { params: { ids: ids.join(",") }, responseType: "blob" }).then(r => r.data as Blob);

// Company details printed on documents
export const getCompanyDetails = () => api.get("/settings/company").then(r => r.data);
export const updateCompanyDetails = (body: unknown) => api.put("/settings/company", body).then(r => r.data);
export const uploadCompanyLogo = (file: File) => {
  const body = new FormData();
  body.append("file", file);
  return api.post("/settings/company/logo", body, { headers: { "Content-Type": "multipart/form-data" } }).then(r => r.data);
};
export const deleteCompanyLogo = () => api.delete("/settings/company/logo").then(r => r.data);
export const getCompanyLogo = () => api.get("/settings/company/logo", { responseType: "blob" }).then(r => r.data as Blob);

// ---- API access: Account ID + Application Keys (admins) ----
export const getApiAccess = () => api.get("/settings/api-keys").then(r => r.data);
export const createApiKey = (body: { name: string; role: string }) => api.post("/settings/api-keys", body).then(r => r.data);
export const revokeApiKey = (id: number) => api.delete(`/settings/api-keys/${id}`).then(r => r.data);

// ---- Platform (SUPER_ADMIN): every organisation and its subscription ----
export const getOrganisations = () => api.get("/tenants").then(r => r.data);
export const setOrganisationSubscription = (id: number, body: { planTier: string; status?: string; endDate?: string | null }) =>
  api.put(`/tenants/${id}/subscription`, body).then(r => r.data);
export const setOrganisationActive = (id: number, active: boolean) => api.put(`/tenants/${id}/active`, { active }).then(r => r.data);

// ---- Shopify refunds to review (7.4) ----
export const getShopifyRefunds = (orderId: number) => api.get(`/sale-orders/${orderId}/shopify-refunds`).then(r => r.data);
export const applyShopifyRefund = (orderId: number, refundId: number, restockLineIds: number[]) =>
  api.post(`/sale-orders/${orderId}/shopify-refunds/${refundId}/apply`, { restockLineIds }).then(r => r.data);
export const dismissShopifyRefund = (orderId: number, refundId: number, reason: string) =>
  api.post(`/sale-orders/${orderId}/shopify-refunds/${refundId}/dismiss`, { reason }).then(r => r.data);
export const getShopifyRefundsToReview = () => api.get("/integrations/shopify/refunds").then(r => r.data);

// ---- Stock to Shopify (7.6): available over all warehouses ----
export const syncShopifyStock = () => api.post("/integrations/shopify/stock/sync").then(r => r.data);
// 204 / null when the product is not in Shopify
export const getProductShopifyStock = (productId: number) =>
  api.get(`/products/${productId}/shopify-stock`).then(r => (r.status === 204 ? null : r.data));

// ---- Price tiers and contract prices (G1) ----
export const getPriceTiers = () => api.get("/settings/price-tiers").then(r => r.data);
export const updatePriceTiers = (tiers: { number: number; name: string; discountPercent: number | null }[]) =>
  api.put("/settings/price-tiers", tiers).then(r => r.data);
export const getContractPrices = (customerId: number) => api.get(`/customers/${customerId}/contract-prices`).then(r => r.data);
export const addContractPrice = (customerId: number, body: { productId: number; unitPrice: number; validFrom: string; validTo: string | null; note: string }) =>
  api.post(`/customers/${customerId}/contract-prices`, body).then(r => r.data);
export const updateContractPrice = (customerId: number, id: number, body: { unitPrice: number; validFrom: string; validTo: string | null; note: string | null }) =>
  api.put(`/customers/${customerId}/contract-prices/${id}`, body).then(r => r.data);
export const deleteContractPrice = (customerId: number, id: number) => api.delete(`/customers/${customerId}/contract-prices/${id}`);

// ---- Credit control (G2) ----
export const getCustomerCredit = (customerId: number) => api.get(`/customers/${customerId}/credit`).then(r => r.data);
export const setCustomerHold = (customerId: number, hold: boolean, reason?: string) =>
  api.put(`/customers/${customerId}/credit/hold`, { hold, reason }).then(r => r.data);

// ---- Statements and overdue reminders (G3) ----
export type StatementType = "ACTIVITY" | "OUTSTANDING";
export interface StatementParams { type: StatementType; from?: string; to?: string }
export const getStatement = (customerId: number, p: StatementParams) =>
  api.get(`/customers/${customerId}/statement`, { params: p }).then(r => r.data);
export const getStatementPdf = (customerId: number, p: StatementParams) =>
  api.get(`/customers/${customerId}/statement/pdf`, { params: p, responseType: "blob" }).then(r => r.data as Blob);
export const getStatementsPdf = (ids: number[], p: StatementParams) =>
  api.get(`/statements/pdf`, { params: { ...p, ids: ids.join(",") }, responseType: "blob" }).then(r => r.data as Blob);
export const getOverdueCustomers = () => api.get(`/customers/overdue`).then(r => r.data);
export const getReminder = (customerId: number) => api.get(`/customers/${customerId}/reminder`).then(r => r.data);
export const getReminderPreviewPdf = (customerId: number) =>
  api.get(`/customers/${customerId}/reminder/pdf`, { responseType: "blob" }).then(r => r.data as Blob);
// Prints the reminder and records it on the overdue invoices (the next one is firmer)
export const sendReminderPdf = (customerId: number) =>
  api.post(`/customers/${customerId}/reminder`, undefined, { responseType: "blob" }).then(r => r.data as Blob);

// ---- Sales ledger (H1) ----
export const getPaymentAccounts = () => api.get("/settings/payment-accounts").then(r => r.data);
export const savePaymentAccounts = (rows: unknown[]) => api.put("/settings/payment-accounts", rows).then(r => r.data);
export const getCustomerCredits = (customerId: number) => api.get(`/customers/${customerId}/credits`).then(r => r.data);
export const receiveOnAccount = (customerId: number, body: { amount: number; receivedOn?: string; accountId?: number; reference?: string; note?: string }) =>
  api.post(`/customers/${customerId}/credits`, body).then(r => r.data);
export const voidCustomerCredit = (customerId: number, creditId: number, reason: string) =>
  api.post(`/customers/${customerId}/credits/${creditId}/void`, { reason });
export const getOpenCreditNotes = (customerId: number) => api.get(`/customers/${customerId}/open-credit-notes`).then(r => r.data);
export const applyCustomerCredit = (invoiceId: number, amount?: number) => api.post(`/invoices/${invoiceId}/use-credit`, { amount }).then(r => r.data);
export const allocateCreditNote = (invoiceId: number, creditNoteId: number, amount?: number) =>
  api.post(`/invoices/${invoiceId}/allocate-credit-note`, { creditNoteId, amount }).then(r => r.data);
export const voidPayment = (invoiceId: number, paymentId: number, reason: string) =>
  api.post(`/invoices/${invoiceId}/payments/${paymentId}/void`, { reason }).then(r => r.data);
export const getAgedDebtors = (asAt?: string) => api.get("/reports/aged-debtors", { params: { asAt } }).then(r => r.data);
export const getAgedDebtorsCsv = (asAt?: string) => api.get("/reports/aged-debtors/csv", { params: { asAt }, responseType: "blob" }).then(r => r.data as Blob);

// ---- Purchase ledger (H2) ----
export const createSupplierCreditNote = ({ id, data }: { id: number; data: unknown }) =>
  api.post(`/invoices/${id}/supplier-credit-notes`, data).then(r => r.data);
export const applySupplierCredit = (billId: number, amount?: number) => api.post(`/invoices/${billId}/use-supplier-credit`, { amount }).then(r => r.data);
export const allocateSupplierCreditNote = (billId: number, creditNoteId: number) =>
  api.post(`/invoices/${billId}/allocate-supplier-credit-note`, { creditNoteId }).then(r => r.data);
export const getSupplierCredits = (supplierId: number) => api.get(`/suppliers/${supplierId}/credits`).then(r => r.data);
export const paySupplierDeposit = (supplierId: number, body: { amount: number; paidOn?: string; accountId?: number; reference?: string; purchaseOrderId?: number }) =>
  api.post(`/suppliers/${supplierId}/credits`, body).then(r => r.data);
export const voidSupplierCredit = (supplierId: number, creditId: number, reason: string) =>
  api.post(`/suppliers/${supplierId}/credits/${creditId}/void`, { reason });
export const getSupplierOpenCreditNotes = (supplierId: number) => api.get(`/suppliers/${supplierId}/open-credit-notes`).then(r => r.data);
export const getAgedCreditors = (asAt?: string) => api.get("/reports/aged-creditors", { params: { asAt } }).then(r => r.data);
export const getAgedCreditorsCsv = (asAt?: string) => api.get("/reports/aged-creditors/csv", { params: { asAt }, responseType: "blob" }).then(r => r.data as Blob);

// ---- VAT return (H3) ----
export const getVatReturn = (from: string, to: string) => api.get("/reports/vat-return", { params: { from, to } }).then(r => r.data);
export const getVatReturnCsv = (from: string, to: string) =>
  api.get("/reports/vat-return/csv", { params: { from, to }, responseType: "blob" }).then(r => r.data as Blob);

// ---- Sales profit and inventory movement (H4) ----
export type SalesProfitParams = { from: string; to: string; cogs: "WITHIN_DATES" | "ALL"; groupBy: string };
export const getSalesProfit = (params: SalesProfitParams) => api.get("/reports/sales-profit", { params }).then(r => r.data);
export const getSalesProfitCsv = (params: SalesProfitParams) =>
  api.get("/reports/sales-profit/csv", { params, responseType: "blob" }).then(r => r.data as Blob);
export type InventoryMovementParams = { from: string; to: string; warehouseId?: number; categoryId?: number };
export const getInventoryMovement = (params: InventoryMovementParams) => api.get("/reports/inventory-movement", { params }).then(r => r.data);
export const getInventoryMovementCsv = (params: InventoryMovementParams) =>
  api.get("/reports/inventory-movement/csv", { params, responseType: "blob" }).then(r => r.data as Blob);

// ---- Accounting export, Xero CSV (H5) ----
export const getAccountingSettings = () => api.get("/accounting/settings").then(r => r.data);
export const updateAccountingSettings = (data: unknown) => api.put("/accounting/settings", data).then(r => r.data);
export const getAccountingPending = () => api.get("/accounting/export/pending").then(r => r.data);
export const getAccountingChanged = () => api.get("/accounting/export/changed").then(r => r.data);
export const getAccountingExports = () => api.get("/accounting/exports").then(r => r.data);
export const runAccountingExport = () => api.post("/accounting/export", null, { responseType: "blob" })
  .then(r => ({ blob: r.data as Blob, filename: /filename="([^"]+)"/.exec(String(r.headers["content-disposition"] ?? ""))?.[1] ?? "Xero_export.zip" }));
export const getAccountingExportFile = (id: number) => api.get(`/accounting/exports/${id}/file`, { responseType: "blob" }).then(r => r.data as Blob);
export const undoAccountingExport = (id: number) => api.post(`/accounting/exports/${id}/undo`).then(r => r.data);

// ---- Users & Roles (I1) ----
export const getPermissionCatalogue = () => api.get("/settings/permissions").then(r => r.data);
export const getAccessRoles = () => api.get("/settings/roles").then(r => r.data);
export const createAccessRole = (data: { name: string; permissions: string[] }) => api.post("/settings/roles", data).then(r => r.data);
export const updateAccessRole = (id: number, data: { name: string; permissions: string[] }) => api.put(`/settings/roles/${id}`, data).then(r => r.data);
export const deleteAccessRole = (id: number) => api.delete(`/settings/roles/${id}`);
export const assignAccessRole = (userId: number, accessRoleId: number | null) => api.put(`/settings/users/${userId}/access-role`, { accessRoleId });

// ---- Global search (I2) ----
export const searchEverything = (q: string) => api.get("/search", { params: { q } }).then(r => r.data);

// ---- Core reports (I3) ----
export const getCoreReports = () => api.get("/reports/core").then(r => r.data);
export const runCoreReport = (key: string, from?: string, to?: string) =>
  api.get(`/reports/core/${key}`, { params: { from, to } }).then(r => r.data);

// ---- Document templates (I4) ----
export const getDocumentTemplates = () => api.get("/settings/document-templates").then(r => r.data);
export const saveDocumentTemplate = (t: { id?: number | null }) =>
  (t.id ? api.put(`/settings/document-templates/${t.id}`, t) : api.post("/settings/document-templates", t)).then(r => r.data);
export const deleteDocumentTemplate = (id: number) => api.delete(`/settings/document-templates/${id}`);

// ---- Two-step sign-in (I5) ----
export const getTwoFactorStatus = () => api.get("/account/two-factor").then(r => r.data);
export const startTwoFactor = () => api.post("/account/two-factor/start").then(r => r.data);
export const confirmTwoFactor = (code: string) => api.post("/account/two-factor/confirm", { code }).then(r => r.data);
export const newRecoveryCodes = (code: string) => api.post("/account/two-factor/recovery-codes", { code }).then(r => r.data);
export const disableTwoFactor = (code: string) => api.post("/account/two-factor/disable", { code });
export const getSecuritySettings = () => api.get("/settings/security").then(r => r.data);
export const setTwoFactorRequired = (twoFactorRequired: boolean) => api.put("/settings/security", { twoFactorRequired }).then(r => r.data);
export const resetTwoFactor = (userId: number) => api.post(`/settings/users/${userId}/two-factor/reset`);
