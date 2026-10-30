export interface Page<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

export interface VariantOption {
  name: string;
  value: string;
}

// Cin7 reference fields (JSON in the database)
export interface ProductDetails {
  tags?: string;
  priceTier3?: number;
  priceTier4?: number;
  supplierProductName?: string;
  supplierPrice?: number;
  weight?: number;
  weightUnit?: string;
  length?: number;
  width?: number;
  height?: number;
  dimensionUnit?: string;
  purchaseTaxRule?: string;
  saleTaxRule?: string;
  hsCode?: string;
  countryOfOrigin?: string;
  internalNote?: string;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  // Lists leave out description and details; GET /products/{id} has them
  description?: string;
  shortDescription?: string;
  unit: string;
  categoryId?: number;
  categoryName?: string;
  status: "ACTIVE" | "DEPRECATED";
  type: "STOCK" | "SERVICE";
  sellable: boolean;
  brand?: string;
  barcode?: string;
  costPrice?: number;
  // Shop price (Cin7 PriceTier1), sent to Shopify
  sellPrice?: number;
  // Cin7 PriceTier2
  tradePrice?: number;
  minStock?: number;
  reorderQuantity?: number;
  defaultSupplierId?: number;
  defaultSupplierName?: string;
  supplierProductCode?: string;
  familySku?: string;
  familyName?: string;
  variantOptions?: VariantOption[];
  details?: ProductDetails;
  totalStock: number;
  lowStock: boolean;
  createdAt: string;
  updatedAt?: string;
}

// Minimal product for dropdowns (GET /products/options)
export interface ProductOption {
  id: number;
  sku: string;
  name: string;
  unit: string;
  categoryId?: number;
  totalStock: number;
}

export interface ProductImportResult {
  rows: number;
  created: number;
  updated: number;
  unchanged: number;
  deprecated: number;
  categoriesCreated: number;
  suppliersCreated: number;
  warnings: string[];
}

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: "SUPER_ADMIN" | "ADMIN" | "MANAGER" | "STAFF";
  tenantId: number;
  tenantName: string;
  tenantSlug: string;
}

export type AuditActorType = "USER" | "SYSTEM" | "INTEGRATION";

export interface AuditLog {
  id: number;
  entityType: string;
  entityId?: number;
  action: string;
  userId?: number;
  username?: string;
  actorType: AuditActorType;
  actorId?: string;
  changes?: string;
  createdAt: string;
}

export interface Supplier {
  id: number;
  name: string;
  status: string;
  currency?: string;
  paymentTerm?: string;
  taxNumber?: string;
  discount?: number;
  website?: string;
  contactName?: string;
  phone?: string;
  email?: string;
  comments?: string;
  createdAt: string;
}

export type MarketingConsent = "UNKNOWN" | "OPT_IN" | "OPT_OUT";

export interface CustomerContact {
  id?: number;
  name?: string;
  jobTitle?: string;
  phone?: string;
  mobilePhone?: string;
  fax?: string;
  email?: string;
  website?: string;
  comment?: string;
  defaultContact: boolean;
  includeInEmail: boolean;
  marketingConsent: MarketingConsent;
}

// Fields follow the Cin7 Core customer export
export interface Customer {
  id: number;
  name: string;
  status: "ACTIVE" | "DEPRECATED";
  currency?: string;
  paymentTerm?: string;
  taxRule?: string;
  taxNumber?: string;
  priceTier?: string;
  discount?: number;
  creditLimit?: number;
  accountReceivable?: string;
  saleAccount?: string;
  carrier?: string;
  salesRepresentative?: string;
  location?: string;
  tags?: string;
  address?: string;
  notes?: string;
  // Default contact first
  contacts: CustomerContact[];
  source: "MANUAL" | "SHOPIFY";
  externalId?: string;
  orderCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CustomerImportResult {
  rows: number;
  customersCreated: number;
  customersUpdated: number;
  contacts: number;
  warnings: string[];
}

export interface Category {
  id: number;
  name: string;
  createdAt: string;
}

export interface Warehouse {
  id: number;
  name: string;
  location?: string;
  createdAt: string;
}

export type MovementType =
  | "PURCHASE_IN" | "SALE_OUT"
  | "ADJUSTMENT_IN" | "ADJUSTMENT_OUT"
  | "TRANSFER_IN" | "TRANSFER_OUT"
  | "SALE_RETURN_IN";

export type ReferenceType =
  | "PURCHASE_ORDER" | "SALE_ORDER" | "SALE_RETURN" | "TRANSFER" | "ADJUSTMENT" | "MANUAL";

export interface StockMovement {
  id: number;
  productId: number;
  productName: string;
  productSku: string;
  categoryId?: number;
  categoryName?: string;
  warehouseId: number;
  warehouseName: string;
  quantity: number;
  type: MovementType;
  referenceType?: ReferenceType;
  referenceId?: string;
  notes?: string;
  createdAt: string;
}

export interface StockLevel {
  productId: number;
  productSku: string;
  productName: string;
  unit: string;
  minStock?: number;
  warehouseId: number;
  warehouseName: string;
  categoryId?: number;
  categoryName?: string;
  stock: number;
  lowStock: boolean;
}

export type PurchaseOrderStatus = "DRAFT" | "RECEIVED" | "CANCELLED";
export type SaleOrderStatus     = "DRAFT" | "CONFIRMED" | "COMPLETED" | "CANCELLED";
export type FulfilmentStatus    = "UNFULFILLED" | "PARTIALLY_FULFILLED" | "FULFILLED";

export interface OrderLine {
  id: number;
  productId: number;
  productSku: string;
  productName: string;
  quantity: number;
  unitCost?: number;
  unitPrice?: number;
}

export interface PurchaseOrder {
  id: number;
  supplierName: string;
  warehouseId: number;
  warehouseName: string;
  status: PurchaseOrderStatus;
  notes?: string;
  createdAt: string;
  receivedAt?: string;
  lines: OrderLine[];
}

export interface SaleOrderLine extends OrderLine {
  fulfilledQuantity: number;
  cancelledQuantity: number;
  returnedQuantity: number;
  remainingQuantity: number;
  discountPercent: number;
  // Copied onto the line when the order was made; empty = no VAT recorded (older and Shopify orders)
  taxName?: string;
  taxRate: number;
  taxOverridden: boolean;
  // For the quantity not cancelled
  netAmount: number;
  taxAmount: number;
  totalAmount: number;
  // Over the product's cost price; absent without a cost
  marginPercent?: number;
}

// Carriage, delivery... (no product, no stock)
export interface SaleOrderCharge {
  id?: number;
  description: string;
  quantity: number;
  unitPrice: number;
  discountPercent: number;
  taxName?: string;
  taxRate: number;
  taxOverridden: boolean;
  netAmount: number;
  taxAmount: number;
  totalAmount: number;
}

export interface TaxRate { id: number; name: string; rate: number; usage: "SALE" | "PURCHASE"; active: boolean }

export interface OrganisationSettings {
  saleOrderPrefix: string;
  nextSaleOrderNumber: number;
  webSalesRep: string;
  defaultSaleTaxRule: string;
}

export interface SaleOrderEntryLine {
  saleOrderLineId: number;
  productSku: string;
  productName: string;
  quantity: number;
  restock?: boolean;
}

export interface SaleOrderFulfilment {
  id: number;
  notes?: string;
  trackingCompany?: string;
  trackingNumber?: string;
  // Shopify orders only
  syncStatus?: "PENDING" | "SYNCED" | "FAILED";
  syncMessage?: string;
  createdBy?: string;
  createdAt: string;
  lines: SaleOrderEntryLine[];
}

export interface SaleOrderReturn {
  id: number;
  reason?: string;
  warehouseId: number;
  warehouseName: string;
  createdBy?: string;
  createdAt: string;
  lines: SaleOrderEntryLine[];
}

export interface SaleOrder {
  id: number;
  // e.g. SO-66006 (absent on a preview)
  orderNumber: string;
  // The customer's own reference (their PO number)
  customerReference?: string;
  salesRep?: string;
  paymentTerm?: string;
  shipBy?: string;
  carrier?: string;
  shippingNotes?: string;
  charges: SaleOrderCharge[];
  // Lines + charges, cancelled quantities left out
  subtotal: number;
  taxTotal: number;
  total: number;
  source: "MANUAL" | "SHOPIFY";
  // Shopify order number, e.g. #1001
  externalReference?: string;
  // Imported, but something needs a person before shipping
  needsReview: boolean;
  reviewReason?: string;
  customerId: number;
  // Name when the order was placed
  customerName: string;
  warehouseId: number;
  warehouseName: string;
  status: SaleOrderStatus;
  fulfilmentStatus: FulfilmentStatus;
  notes?: string;
  createdAt: string;
  confirmedAt?: string;
  fulfilledAt?: string;
  cancelledAt?: string;
  lines: SaleOrderLine[];
  // Only on single-order responses
  fulfilments?: SaleOrderFulfilment[];
  returns?: SaleOrderReturn[];
}

export interface FulfilmentRequest {
  idempotencyKey?: string;
  notes?: string;
  trackingCompany?: string;
  trackingNumber?: string;
  lines?: { saleOrderLineId: number; quantity: number }[];
}

export interface SaleReturnRequest {
  idempotencyKey?: string;
  reason?: string;
  warehouseId?: number;
  lines: { saleOrderLineId: number; quantity: number; restock: boolean }[];
}

export type InvoiceType   = "SALES" | "PURCHASE";
export type InvoiceStatus = "DRAFT" | "ISSUED" | "PAID" | "CANCELLED";
export type PaymentMethod = "CASH" | "BANK" | "STRIPE";

export interface InvoiceItem {
  id: number;
  productId?: number;
  productName?: string;
  productSku?: string;
  description?: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
}

export interface InvoicePayment {
  id: number;
  amount: number;
  method: PaymentMethod;
  paidAt: string;
}

export interface Invoice {
  id: number;
  invoiceNumber: string;
  type: InvoiceType;
  partyName: string;
  saleOrderId?: number;
  purchaseOrderId?: number;
  status: InvoiceStatus;
  issueDate?: string;
  dueDate?: string;
  totalAmount: number;
  amountPaid: number;
  currency: string;
  notes?: string;
  createdAt: string;
  items: InvoiceItem[];
  payments: InvoicePayment[];
}

export type PlanTier = "FREE" | "PRO" | "ENTERPRISE";

export interface Plan {
  id: number;
  tier: PlanTier;
  displayName: string;
  maxProducts: number;   // -1 = unlimited
  maxUsers: number;
  maxWarehouses: number;
  monthlyPrice: number;
}

export interface SubscriptionInfo {
  subscriptionId: number;
  planTier: PlanTier;
  planDisplayName: string;
  maxProducts: number;
  maxUsers: number;
  maxWarehouses: number;
  monthlyPrice: number;
  status: "ACTIVE" | "CANCELLED" | "EXPIRED";
  startDate: string;
  endDate?: string;
  productCount: number;
  userCount: number;
  warehouseCount: number;
}

// GET /dashboard — figures for the last `days` days, today included
export interface StockImportResult {
  applied: boolean;
  warehouseName: string;
  fileLocation?: string;
  rows: number;
  changed: number;
  unchanged: number;
  unknownSkus: number;
  unitsBefore: number;
  unitsAfter: number;
  valueAfter: number;
  changes: { sku: string; name: string; before: number; after: number; unitCost?: number }[];
  warnings: string[];
}

export interface DashboardSeller { productId: number; sku: string; name: string; quantity: number; value: number }

export interface Dashboard {
  from: string;
  to: string;
  days: number;
  kpis: {
    products: number;
    warehouses: number;
    customers: number;
    stockValue: number;
    productsToReorder: number;
    ordersToShip: number;
    draftOrders: number;
    ordersInPeriod: number;
    salesValueInPeriod: number;
    unitsShippedInPeriod: number;
    receivablesOutstanding: number;
  };
  ordersByStatus: Record<SaleOrderStatus, number>;
  sales: { draftOrders: number; toShip: number; draftInvoices: number; awaitingPayment: number; overdueInvoices: number };
  purchases: { awaitingDelivery: number; draftInvoices: number; awaitingPayment: number; overdueInvoices: number };
  daily: { date: string; orders: number; salesValue: number; unitsIn: number; unitsOut: number }[];
  topSellers: DashboardSeller[];
  worstSellers: DashboardSeller[];
  salesByRep: { salesRep: string; orders: number; net: number; tax: number; total: number }[];
  recentOrders: { id: number; orderNumber: string; customerName: string; status: SaleOrderStatus; fulfilmentStatus: FulfilmentStatus; total: number; createdAt: string }[];
}

export interface ReorderLine {
  productId: number;
  sku: string;
  name: string;
  supplierName?: string;
  stock: number;
  onOrder: number;
  minStock: number;
  suggestedQuantity: number;
}

export interface ShopifyConnection {
  shopDomain: string;
  clientId: string;
  secretSaved: boolean;
  status: "NOT_TESTED" | "CONNECTED" | "ERROR";
  shopName?: string;
  shopCurrency?: string;
  grantedScopes: string[];
  missingScopes: string[];
  lastError?: string;
  lastCheckedAt?: string;
  tokenExpiresAt?: string;
  apiVersion: string;
  defaultWarehouseId?: number;
  defaultWarehouseName?: string;
  // Used when no warehouse is chosen: the oldest one
  fallbackWarehouseName?: string;
  publicUrl?: string;
  webhookUrl?: string;
  webhooksRegisteredAt?: string;
  webhookTopics: string[];
  lastImportAt?: string;
  lastProductSyncAt?: string;
}

export interface ShopifyProductSync {
  state: "RUNNING" | "DONE" | "FAILED";
  // Step of a run and its own progress
  phase: "SENDING" | "REMOVING" | "STOCK";
  phaseTotal: number;
  phaseDone: number;
  // Shopify products (a family is one)
  total: number;
  processed: number;
  created: number;
  updated: number;
  unchanged: number;
  // Deleted (ERP-made) or archived (hand-made)
  removed: number;
  failed: number;
  // SKUs sent
  variants: number;
  // Deprecated, services, not sellable
  notSent: number;
  stockUpdated: number;
  // Created at £0.00 because the ERP has no selling price
  withoutPrice: number;
  warehouseName?: string;
  locationName?: string;
  problems: string[];
  error?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface ShopifyImportResult {
  fetched: number;
  created: number;
  updated: number;
  unchanged: number;
  skipped: number;
  needsReview: number;
  problems: string[];
}

export interface ShopifyWebhookEvent {
  id: number;
  topic: string;
  eventId: string;
  status: "RECEIVED" | "PROCESSED" | "IGNORED" | "FAILED";
  error?: string;
  receivedAt: string;
  processedAt?: string;
}
