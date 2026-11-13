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
  status: "ACTIVE" | "DISCONTINUED" | "DEPRECATED";
  type: "STOCK" | "SERVICE" | "KIT";
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
  // Reserved for sale orders, and total − reserved (can be < 0)
  reservedStock: number;
  availableStock: number;
  lowStock: boolean;
  createdAt: string;
  updatedAt?: string;
}

// A product's stock per warehouse and what is on open purchase orders
export interface ProductStock {
  warehouses: { warehouseId: number; warehouseName: string; onHand: number; reserved: number; available: number }[];
  onOrder: { purchaseOrderId: number; orderNumber: string; supplierName: string; status: string; outstanding: number; requiredBy?: string }[];
  onHand: number;
  reserved: number;
  available: number;
  onOrderTotal: number;
  // FIFO: the cost layers still holding stock (oldest first per warehouse) and what they are worth
  costLayers: CostLayer[];
  stockValue: number;
}

// Stock that came in together at one unit cost (FIFO); unitCost differs from originalUnitCost after a supplier
// bill at another price than the PO
export interface CostLayer {
  id: number;
  warehouseId: number;
  warehouseName?: string;
  receivedAt: string;
  source?: string;
  quantity: number;
  remaining: number;
  unitCost: number;
  originalUnitCost: number;
  value: number;
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
  // Purchase tax rule for its orders when the product has none
  taxRule?: string;
  // One postal address for now; copied onto new purchase orders
  address?: PostalAddress | null;
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
  // Phase G2: put on credit hold by hand
  onHold?: boolean;
  holdReason?: string | null;
  id: number;
  name: string;
  status: "ACTIVE" | "DISCONTINUED" | "DEPRECATED";
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
  // The billing address on one line; the address book is in addresses (G4)
  address?: string;
  addresses?: CustomerAddress[];
  notes?: string;
  // Default contact first
  contacts: CustomerContact[];
  source: "MANUAL" | "SHOPIFY";
  externalId?: string;
  orderCount: number;
  createdAt: string;
  updatedAt: string;
}

// Product files without a result of their own (units of measure, assembly BOM, simple prices)
export interface FileImportResult {
  file: string;
  rows: number;
  changed: number;
  unchanged: number;
  warnings: string[];
}

// Cin7's Suppliers file
export interface SupplierImportResult {
  rows: number;
  created: number;
  updated: number;
  unchanged: number;
  warnings: string[];
}

export interface CustomerImportResult {
  rows: number;
  customersCreated: number;
  customersUpdated: number;
  contacts: number;
  addresses: number;
  prices: number;
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
  // Where purchases for this warehouse are shipped to
  address?: PostalAddress | null;
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
  // Stock in: the cost of one unit (its FIFO layer)
  unitCost?: number;
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
  // Reserved for sale orders shipping from this warehouse; available = stock − reserved (can be < 0)
  reserved: number;
  available: number;
  lowStock: boolean;
  // Like Cin7's availability: what is left in the FIFO cost layers; what ordered POs still bring in; their earliest required-by date
  stockValue?: number;
  onOrder: number;
  nextDelivery?: string;
}

// DRAFT (editable) → ORDERED (placed with the supplier, Cin7's Authorise) → RECEIVED; or CANCELLED
export type PurchaseOrderStatus = "DRAFT" | "ORDERED" | "RECEIVED" | "CANCELLED";
// QUOTE → DRAFT (converted) or REJECTED; DRAFT → CONFIRMED → COMPLETED; DRAFT/CONFIRMED → CANCELLED
export type SaleOrderStatus     = "QUOTE" | "REJECTED" | "DRAFT" | "CONFIRMED" | "COMPLETED" | "CANCELLED";
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

export interface PurchaseOrderLine extends OrderLine {
  unit?: string;
  // Phase P3: the line's own unit (empty: the product's)
  unitName?: string | null;
  unitFactor?: number;
  // The supplier's code for the product, e.g. 3200.BK.L
  supplierSku?: string;
  discountPercent: number;
  // Purchase VAT copied onto the line (empty on older orders)
  taxName?: string;
  taxRate: number;
  taxOverridden: boolean;
  netAmount: number;
  taxAmount: number;
  totalAmount: number;
  receivedQuantity: number;
  // On the supplier's bills (not cancelled)
  billedQuantity: number;
  // Not coming any more
  closedShortQuantity: number;
  // ordered − received − closed short
  outstandingQuantity: number;
  // Received above what was ordered
  overQuantity: number;
}

export interface PurchaseReceipt {
  id: number;
  receivedOn: string;
  warehouseName: string;
  notes?: string;
  createdBy?: string;
  createdAt: string;
  lines: { purchaseOrderLineId: number; productSku: string; productName: string; quantity: number; overQuantity: number }[];
}

// Freight, duty... on the order: an expense, not stock cost
export interface PurchaseOrderCost {
  id?: number;
  description: string;
  reference?: string;
  quantity: number;
  unitPrice: number;
  discountPercent: number;
  taxName?: string;
  taxRate: number;
  netAmount: number;
  taxAmount: number;
  totalAmount: number;
}

export interface PurchaseOrder {
  id: number;
  // e.g. PO-19074 (absent on a preview)
  orderNumber: string;
  supplierId?: number;
  supplierName: string;
  supplierReference?: string;
  paymentTerm?: string;
  requiredBy?: string;
  // Like Cin7's New purchase: supplier, accounting and shipping details
  contactName?: string | null;
  contactPhone?: string | null;
  vendorAddress?: PostalAddress | null;
  orderDate?: string | null;
  // The order's own tax rule (null = the supplier's) and the rule its lines start from
  taxRule?: string | null;
  defaultTaxRule?: string;
  // e.g. "631: Inventory Control"
  inventoryAccount?: string;
  blindReceipt: boolean;
  // Cin7's Approach: STOCK_FIRST = a bill covers only what came in; INVOICE_FIRST = only what is billed can be received
  approach: PurchaseApproach;
  shipToCompany?: string | null;
  shippingAddress?: PostalAddress | null;
  warehouseId: number;
  warehouseName: string;
  status: PurchaseOrderStatus;
  notes?: string;
  createdAt: string;
  orderedAt?: string;
  receivedAt?: string;
  receivingStatus: "NOT_RECEIVED" | "PARTIALLY_RECEIVED" | "RECEIVED";
  overDelivered: boolean;
  closedShort: boolean;
  billingStatus: "NOT_BILLED" | "PARTIALLY_BILLED" | "BILLED";
  // Only on a single order; absent when there are no bills
  paymentStatus?: "UNPAID" | "PARTIALLY_PAID" | "PAID";
  bills?: { id: number; invoiceNumber: string; status: InvoiceStatus; issueDate?: string; dueDate?: string; total: number; amountPaid: number; matchIssueCount: number }[];
  lines: PurchaseOrderLine[];
  costs: PurchaseOrderCost[];
  // Only on a single order
  receipts?: PurchaseReceipt[];
  // Like Cin7's totals grid: order lines, additional costs, total
  linesNet: number;
  linesTax: number;
  costsNet: number;
  costsTax: number;
  subtotal: number;
  taxTotal: number;
  total: number;
}

export interface SaleOrderLine extends OrderLine {
  // Phase P3: the line's unit (empty: the product's own) and the base units one holds
  unitName?: string | null;
  unitFactor?: number;
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
  // Over the FIFO cost of what has shipped (and the product's cost price for the rest); absent without a cost
  marginPercent?: number;
  // FIFO cost of the units shipped, less those returned to stock; absent until something ships
  costOfGoods?: number;
  // Reserving orders only: what the stock on hand covers (first confirmed, first served) and what is short
  allocatedQuantity?: number;
  backorderedQuantity?: number;
  invoicedQuantity: number;
  toInvoiceQuantity: number;
  // Phase G1: where the price came from: "Contract price", "Contract tier", "Retail", "Typed"
  priceSource?: string | null;
}

export interface SaleOrderInvoice {
  id: number;
  invoiceNumber: string;
  status: InvoiceStatus;
  issueDate?: string;
  dueDate?: string;
  total: number;
  amountPaid: number;
  fulfilmentId?: number;
  kind: "INVOICE" | "CREDIT_NOTE";
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
  invoiced: boolean;
}

export type VatType = "STANDARD" | "REDUCED" | "ZERO" | "EXEMPT" | "NO_VAT" | "REVERSE_CHARGE" | "EC_ACQUISITION" | "FULL_VAT";
export interface TaxRate { id: number; name: string; rate: number; usage: "SALE" | "PURCHASE"; active: boolean; vatType?: VatType; reportRate?: number }

export type PurchaseApproach = "STOCK_FIRST" | "INVOICE_FIRST";

/** Cin7's preset on a new purchase: the header saved under a name (no lines, no dates). */
export interface PurchasePreset {
  id: number;
  name: string;
  data: Record<string, unknown>;
  createdBy?: string;
  updatedAt: string;
}

export interface OrganisationSettings {
  saleOrderPrefix: string;
  nextSaleOrderNumber: number;
  webSalesRep: string;
  defaultSaleTaxRule: string;
  // Default validity of a new quote, in days
  quoteValidDays: number;
  invoicePrefix: string;
  nextInvoiceNumber: number;
  // Every shipment creates and issues an invoice for what it shipped
  autoInvoiceOnShipment: boolean;
  // Q2: hand-typed supplier invoices
  billPrefix?: string;
  nextBillNumber?: number;
  creditNotePrefix: string;
  nextCreditNoteNumber: number;
  purchaseOrderPrefix: string;
  nextPurchaseOrderNumber: number;
  defaultPurchaseTaxRule: string;
  defaultPurchaseApproach?: PurchaseApproach;
  defaultWarehouseId?: number | null;
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
  // Phase G2: the customer's credit hold as a warning; a manager's reason for confirming past it
  creditHold?: boolean;
  creditWarning?: string | null;
  creditOverride?: string | null;
  // Phase G1: the tier the order is priced at (the customer's unless changed on the order)
  priceTier?: string | null;
  id: number;
  // e.g. SO-66006 (absent on a preview)
  orderNumber: string;
  // The customer's own reference (their PO number)
  customerReference?: string;
  salesRep?: string;
  // The customer's contact for this order (the default contact unless another is chosen)
  contactName?: string | null;
  contactPhone?: string | null;
  contactEmail?: string | null;
  paymentTerm?: string;
  shipBy?: string;
  carrier?: string;
  shippingNotes?: string;
  // Phase G4: copied onto the order from the customer's defaults, changeable per order
  billingAddress?: PostalAddress | null;
  shippingAddress?: PostalAddress | null;
  shipToCompany?: string | null;
  shipToContact?: string | null;
  charges: SaleOrderCharge[];
  // Lines + charges, cancelled quantities left out
  subtotal: number;
  taxTotal: number;
  total: number;
  // Order page: FIFO cost of everything shipped; absent until something ships
  costOfGoods?: number;
  // Confirmed (or imported from Shopify) and the stock on hand can't cover it all
  backordered: boolean;
  // When it became an order (absent for quotes); quotes: last day it can be converted
  orderedAt?: string;
  quoteValidUntil?: string;
  quoteExpired: boolean;
  // VAT included; detail responses only
  toInvoiceTotal?: number;
  invoicedTotal?: number;
  creditedTotal?: number;
  invoices?: SaleOrderInvoice[];
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
  // Q1: a manager's reason to ship past a credit hold
  creditOverride?: string;
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
// CREDIT_NOTE = an invoice paid with a credit note; a payment on a credit note is a refund
export type PaymentMethod = "CASH" | "BANK" | "STRIPE" | "CARD" | "PAYPAL" | "CREDIT_NOTE" | "CUSTOMER_CREDIT" | "SUPPLIER_CREDIT" | "SHOPIFY";

export interface InvoiceItem {
  id: number;
  productId?: number;
  productName?: string;
  productSku?: string;
  description?: string;
  quantity: number;
  unitPrice: number;
  // Net; VAT copied from the order line
  totalPrice: number;
  discountPercent: number;
  taxName?: string;
  taxRate: number;
  taxAmount: number;
  saleOrderLineId?: number;
  // Invoice items: quantity already on credit notes
  creditedQuantity: number;
  // Bills: the purchase order line, and what differs from the PO and the receipts (three-way match)
  purchaseOrderLineId?: number;
  matchIssues?: string[];
  // Bill lines: units matched to stock received so far (the rest is priced when it arrives)
  costMatchedQuantity?: number;
}

export interface InvoicePayment {
  id: number;
  amount: number;
  method: PaymentMethod;
  paidAt: string;
  reference?: string;
  creditNoteId?: number;
  // Phase H1
  accountId?: number | null;
  account?: string | null;
  customerCreditId?: number | null;
}

export interface VoidedPayment {
  paymentId: number;
  amount: number;
  method: PaymentMethod;
  paidAt: string;
  reference?: string | null;
  reason: string;
  voidedBy: string;
  voidedAt: string;
}

export interface Invoice {
  id: number;
  invoiceNumber: string;
  type: InvoiceType;
  partyName: string;
  saleOrderId?: number;
  purchaseOrderId?: number;
  purchaseOrderNumber?: string;
  supplierId?: number;
  // Bills: lines that don't match the PO or the receipts
  matchIssueCount: number;
  // Bills: what the bill's prices changed on the stock received and the COGS already taken (FIFO); 0 = the PO's prices
  costAdjustment?: number;
  status: InvoiceStatus;
  issueDate?: string;
  dueDate?: string;
  // To pay, VAT included = subtotal + taxTotal
  totalAmount: number;
  subtotal: number;
  taxTotal: number;
  customerId?: number;
  // Made for this shipment (invoiced on shipment)
  fulfilmentId?: number;
  kind: "INVOICE" | "CREDIT_NOTE";
  // Credit notes: the invoice credited, and how much of the credit paid off invoices (in amountPaid too)
  creditedInvoiceId?: number;
  creditedInvoiceNumber?: string;
  creditApplied?: number;
  amountPaid: number;
  currency: string;
  notes?: string;
  createdAt: string;
  items: InvoiceItem[];
  payments: InvoicePayment[];
  // Phase H1: payments a manager voided
  voidedPayments?: VoidedPayment[];
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
  sales: { openQuotes: number; draftOrders: number; toShip: number; backordered: number; draftInvoices: number; awaitingPayment: number; overdueInvoices: number };
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
  // Default supplier (absent = none), their code for it, cost (supplier price, else cost price)
  supplierId?: number;
  supplierSku?: string;
  unitCost?: number;
  unit?: string;
}

// Reorder screen: suppliers with products to reorder (no supplierId = products without a supplier)
export interface ReorderSupplier {
  supplierId?: number;
  supplierName?: string;
  products: number;
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
  // 7.5: runs so far; when a failed one is tried again (none = given up)
  attempts: number;
  nextAttemptAt?: string | null;
}

// How the Shopify link is doing (7.5)
export interface ShopifyHealth {
  ok: boolean;
  lastEventAt?: string | null;
  processedLast24h: number;
  failedLast24h: number;
  waitingRetry: number;
  gaveUp: number;
  lastReconciledAt?: string | null;
  lastReconcileSummary?: string | null;
  lastReconcileError?: string | null;
  refundsToReview: number;
  // 7.6: stock sent to Shopify
  stockSyncedAt?: string | null;
  stockSyncResult?: string | null;
  stockSyncError?: string | null;
  stockWaiting: number;
  warnings: string[];
}

export interface ShopifyReconcileResult {
  checkedAt: string;
  summary: string;
  error?: string | null;
  problems: string[];
}

// The company details printed on documents (D5); empty fields are left out of the page
export interface CompanyDetails {
  legalName?: string | null;
  address?: string | null;
  phone?: string | null;
  email?: string | null;
  website?: string | null;
  vatNumber?: string | null;
  companyNumber?: string | null;
  bankDetails?: string | null;
  documentFooter?: string | null;
  hasLogo: boolean;
}

// API access (Settings → Integrations → API): the organisation's Account ID and Application Keys, like Cin7 Core
export interface ApiKey {
  id: number;
  name: string;
  keyPrefix: string;
  role: "ADMIN" | "MANAGER" | "STAFF";
  createdBy: string;
  createdAt: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  revokedBy?: string | null;
}

export interface ApiAccess {
  accountId: string;
  keys: ApiKey[];
}

// A new key: applicationKey is shown this once only
export interface ApiKeyCreated {
  accountId: string;
  key: ApiKey;
  applicationKey: string;
}

// Platform → Organisations (SUPER_ADMIN): one organisation = one subscription
export interface PlatformOrganisation {
  id: number;
  name: string;
  slug: string;
  active: boolean;
  createdAt: string;
  planTier: "FREE" | "PRO" | "ENTERPRISE";
  planName: string;
  monthlyPrice: number;
  status: "ACTIVE" | "CANCELLED" | "EXPIRED";
  endDate?: string | null;
  users: number;
  products: number;
  warehouses: number;
}

// A refund made in Shopify, reviewed on its order (7.4): never credited by itself
export interface ShopifyRefundLine {
  lineItemId?: string | null;
  saleOrderLineId?: number | null;
  sku?: string | null;
  title?: string | null;
  quantity: number;
  amount: number;
  restockType?: string | null;
}

export interface ShopifyRefund {
  id: number;
  refundId: string;
  refundedAt?: string | null;
  amount: number;
  otherAmount: number;
  note?: string | null;
  status: "TO_REVIEW" | "DONE" | "DISMISSED";
  outcome?: string | null;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  lines: ShopifyRefundLine[];
}

export interface ShopifyRefundWaiting {
  id: number;
  saleOrderId: number;
  orderNumber?: string | null;
  shopifyOrder?: string | null;
  amount: number;
  refundedAt?: string | null;
}

// What Shopify was last told about a product's stock (7.6)
export interface ProductShopifyStock {
  sent?: number | null;
  sentAt?: string | null;
  available: number;
  upToDate: boolean;
}

export interface ShopifyStockSyncResult {
  checked: number;
  sent: number;
  unchanged: number;
  failed: number;
  problems: string[];
}

// Phase G1: price tiers and contract prices
export interface PriceTier {
  number: number;
  name: string;
  discountPercent?: number | null;
  customers: number;
}

export interface ContractPrice {
  id: number;
  productId: number;
  sku: string;
  productName: string;
  unitPrice: number;
  tierPrice?: number | null;
  validFrom: string;
  validTo?: string | null;
  note?: string | null;
  createdBy: string;
  current: boolean;
}

// A customer's credit standing (G2)
export interface CreditStatus {
  customerId: number;
  creditLimit?: number | null;
  balance: number;
  notInvoiced: number;
  exposure: number;
  available?: number | null;
  overdue: number;
  oldestDueDate?: string | null;
  daysOverdue?: number | null;
  manualHold: boolean;
  holdReason?: string | null;
  heldBy?: string | null;
  heldAt?: string | null;
  held: boolean;
  reasons: string[];
}

// ---- Statements and overdue reminders (G3) ----
export interface StatementEntry {
  date: string;
  kind: "INVOICE" | "CREDIT_NOTE" | "PAYMENT" | "REFUND" | "CREDIT";
  invoiceId?: number | null;
  number: string;
  reference?: string | null;
  dueDate?: string | null;
  debit: number;
  credit: number;
  balance: number;
}
export interface StatementOpenItem {
  invoiceId?: number | null;
  kind: "INVOICE" | "CREDIT_NOTE" | "CREDIT";
  number: string;
  date: string;
  dueDate?: string | null;
  total: number;
  settled: number;
  open: number;          // credit is negative
  daysOverdue: number;
  reminderCount: number;
  lastReminderAt?: string | null;
}
export interface StatementAgeing { current: number; days1to30: number; days31to60: number; days61to90: number; over90: number; total: number }
export interface Statement {
  type: "ACTIVITY" | "OUTSTANDING";
  customerId: number;
  customerName: string;
  from?: string | null;
  to: string;
  opening?: number | null;
  entries: StatementEntry[];
  closing: number;
  openItems: StatementOpenItem[];
  ageing: StatementAgeing;
}
export interface OverdueCustomer {
  customerId: number;
  customerName: string;
  overdue: number;
  invoices: number;
  oldestDue: string;
  daysOverdue: number;
  balance: number;
  nextLevel: number;     // 1 = first reminder, 2 = second, 3 = final notice
  lastReminderAt?: string | null;
  onHold: boolean;
}

// ---- Addresses (G4), Cin7's fields ----
export interface PostalAddress {
  line1?: string | null;
  line2?: string | null;
  city?: string | null;
  state?: string | null;
  postcode?: string | null;
  country?: string | null;
}
export type AddressType = "BILLING" | "BUSINESS" | "SHIPPING";
export interface CustomerAddress extends PostalAddress {
  id?: number;
  type: AddressType;
  defaultForType: boolean;
}

// ---- Sales ledger (H1) ----
export type PaymentAccountKind = "BANK" | "CARD" | "CASH" | "PAYPAL" | "SHOPIFY" | "OTHER";
export interface PaymentAccount {
  id?: number;
  code: string;
  name: string;
  kind: PaymentAccountKind;
  active: boolean;
  defaultAccount: boolean;
  used?: boolean;
}
export interface CustomerCredit {
  id: number;
  receivedOn: string;
  amount: number;
  used: number;
  available: number;
  method: PaymentMethod;
  account?: string | null;
  reference?: string | null;
  sourceInvoiceId?: number | null;
  sourceInvoiceNumber?: string | null;
  note?: string | null;
  createdBy: string;
}
export interface OpenCreditNote { id: number; number: string; date: string; available: number }
export interface AgedDebtor {
  customerId?: number | null;
  customerName: string;
  current: number;
  days1to30: number;
  days31to60: number;
  days61to90: number;
  over90: number;
  total: number;
  credit: number;
  creditLimit?: number | null;
  onHold: boolean;
}
export interface AgedDebtors { asAt: string; customers: AgedDebtor[]; totals: AgedDebtor }

// ---- Purchase ledger (H2) ----
export interface SupplierCredit {
  id: number;
  paidOn: string;
  amount: number;
  used: number;
  available: number;
  method: PaymentMethod;
  account?: string | null;
  reference?: string | null;
  sourceInvoiceId?: number | null;
  sourceInvoiceNumber?: string | null;
  purchaseOrderId?: number | null;
  note?: string | null;
  createdBy: string;
}
export interface AgedCreditor {
  supplierId?: number | null;
  supplierName: string;
  current: number;
  days1to30: number;
  days31to60: number;
  days61to90: number;
  over90: number;
  credit: number;
  total: number;
}
export interface AgedCreditors { asAt: string; suppliers: AgedCreditor[]; totals: AgedCreditor }

// ---- VAT return (H3) ----
export interface VatBoxes { box1: number; box2: number; box3: number; box4: number; box5: number; box6: number; box7: number; box8: number; box9: number }
export interface VatRateRow { side: "SALES" | "PURCHASES"; taxName: string; vatType: VatType; rate: number; net: number; vat: number; lines: number }
// K4: spend / receive money (sourceKey BT:id) has no invoice
export interface VatDocument { invoiceId: number | null; side: "SALES" | "PURCHASES"; kind: "INVOICE" | "CREDIT_NOTE" | "SPEND_MONEY" | "RECEIVE_MONEY"; number: string; date: string; party: string | null; net: number; vat: number; sourceKey: string }
export interface VatReturn { from: string; to: string; boxes: VatBoxes; rates: VatRateRow[]; documents: VatDocument[]; refund: boolean }

// ---- Sales profit and inventory movement (H4) ----
export type ProfitGroupBy = "CUSTOMER" | "ORDER" | "REP" | "REP_CUSTOMER" | "REP_ORDER" | "PRODUCT";
export interface SalesProfitRow {
  customerId: number | null; customer: string | null; salesRep: string | null; saleOrderId: number | null; orderNumber: string | null;
  invoices: string; creditNotes: string; productId: number | null; sku: string | null; productName: string | null; category: string | null;
  quantity: number; invoiced: number; sale: number; cogs: number; profit: number; grossMargin: number | null; markup: number | null;
}
export interface SalesProfitTotals { quantity: number; invoiced: number; sale: number; cogs: number; profit: number; grossMargin: number | null; markup: number | null }
export interface SalesProfitReport { from: string; to: string; cogs: "WITHIN_DATES" | "ALL"; groupBy: ProfitGroupBy; rows: SalesProfitRow[]; totals: SalesProfitTotals }
export interface InventoryMovementRow {
  warehouseId: number; warehouse: string; productId: number; sku: string; product: string; unit: string | null;
  categoryId: number | null; category: string | null; openingQuantity: number; openingValue: number; inQuantity: number; inValue: number;
  outQuantity: number; outValue: number; closingQuantity: number; unitCost: number | null; closingValue: number;
}
export interface InventoryMovementReport { from: string; to: string; rows: InventoryMovementRow[]; totals: { openingValue: number; inValue: number; outValue: number; closingValue: number } }

// ---- Accounting export (H5) ----
export interface AccountingSettings {
  exportStartDate: string | null; revenueAccount: string | null; inventoryAccount: string | null; cogsAccount: string | null;
  discrepancyAccount: string | null; purchaseChargesAccount: string | null; customerCreditAccount: string | null; supplierDepositsAccount: string | null;
}
export type ExportKind = "INVOICE" | "CREDIT_NOTE" | "BILL" | "SUPPLIER_CREDIT" | "JOURNAL" | "PAYMENT";
export interface ExportItem { key: string; kind: ExportKind; date: string; number: string; party: string; amount: number }
export interface ExportPending { startDate: string | null; missing: string[]; items: ExportItem[]; counts: Partial<Record<ExportKind, number>> }
export interface ExportBatch {
  id: number; createdAt: string; createdBy: string; invoices: number; creditNotes: number; bills: number; supplierCredits: number;
  journals: number; payments: number; undoneAt: string | null; undoneBy: string | null;
}
export interface ExportChanged { key: string; number: string; party: string; what: string; date: string }

// ---- Users & Roles (I1) ----
export interface PermissionInfo { key: string; group: string; label: string }
export interface AccessRole { id: number; name: string; permissions: string[]; users: number }

// ---- Global search (I2) ----
export type SearchKind = "SALE_ORDER" | "INVOICE" | "BILL" | "PURCHASE_ORDER" | "CUSTOMER" | "SUPPLIER" | "PRODUCT";
export interface SearchHit { kind: SearchKind; id: number; title: string; detail: string }

// ---- Core reports (I3) ----
export interface CoreReportInfo { key: string; group: string; title: string; description: string; period: boolean }
export interface CoreReportColumn { key: string; label: string; type: "TEXT" | "DATE" | "NUMBER" | "MONEY" | "PERCENT" | "LINK"; link: string | null }
export interface CoreReport {
  key: string; title: string; description: string; from: string | null; to: string | null;
  columns: CoreReportColumn[]; rows: Record<string, string | number | null>[]; totals: Record<string, number>;
}

// ---- Document templates (I4) ----
export type DocType = "QUOTE" | "SALES_ORDER" | "INVOICE" | "CREDIT_NOTE" | "PURCHASE_ORDER";
export interface DocumentTemplate {
  id: number | null; docType: DocType; name: string; isDefault: boolean; title: string | null; accentColor: string;
  showLogo: boolean; showPrices: boolean; showSku: boolean; showDiscount: boolean; showVatSummary: boolean;
  introText: string | null; paymentText: string | null; footerText: string | null; emailSubject: string | null;
}

// Phase J: Cin7 sale / purchase task import and the go-live check
export interface TaskImportDoc {
  number: string; party: string; orderNumber: string | null; invoiceNumber: string | null; creditNoteNumber: string | null; lines: number;
  total: number; paid: number; date: string;
}
export interface TaskImportResult {
  applied: boolean; kind: "SALES" | "PURCHASES"; documents: number; total: number; paid: number; newParties: string[];
  docs: TaskImportDoc[]; errors: string[]; warnings: string[];
}
export interface ImportLog {
  id: number; kind: string; fileName: string | null; options: string; documents: number; total: number; createdAt: string; createdBy: string;
}
export interface CutoverFigure { key: string; group: string; label: string; value: number; money: boolean; cin7: string }
export interface CutoverCheck { asAt: string; figures: CutoverFigure[]; warnings: string[] }

// Phase K1: the general ledger
export type AccountClass = "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
export interface LedgerAccount {
  id: number; code: string; name: string; type: string; accountClass: AccountClass; typeLabel: string;
  taxType: string | null; description: string | null; archived: boolean; used: boolean;
}
export interface LedgerAccountType { type: string; label: string; accountClass: AccountClass }
export interface LedgerSettings {
  conversionDate: string | null; revenue: string; inventory: string; cogs: string; discrepancy: string; purchaseCharges: string;
  customerCredit: string; supplierDeposits: string; receivable: string; payable: string; vat: string; retainedEarnings: string;
  suspense: string; rounding: string; historical: string;
}
export interface OpeningBalance { accountCode: string; debit: number | null; credit: number | null }
export interface OpeningBalances { conversionDate: string | null; balances: OpeningBalance[]; debits: number; credits: number; historicalAdjustment: number }
export interface ManualLine { accountCode: string; description: string | null; debit: number | null; credit: number | null }
export interface ManualJournal {
  id: number; date: string; narration: string; status: "DRAFT" | "POSTED" | "VOIDED"; total: number; lines: ManualLine[];
  createdAt: string; createdBy: string | null; voidedAt: string | null; voidedBy: string | null;
}
export interface TrialBalanceRow { code: string; name: string; type: string; accountClass: AccountClass; debit: number | null; credit: number | null }
export interface TrialBalance { asAt: string; rows: TrialBalanceRow[]; debits: number; credits: number; warnings: string[] }
export interface LedgerTransaction {
  date: string; journalNumber: number; sourceType: string; sourceKey: string; reference: string | null; narration: string;
  description: string | null; contact: string | null; debit: number | null; credit: number | null; balance: number;
}
export interface AccountTransactions {
  code: string; name: string; type: string; from: string; to: string; opening: number; rows: LedgerTransaction[];
  debits: number; credits: number; closing: number;
}
export interface JournalLine { account: string; amount: number; description: string | null; contact: string | null; taxName: string | null }
export interface LedgerJournal {
  number: number; date: string; sourceType: string; sourceKey: string; reference: string | null; narration: string;
  reversal: boolean; createdAt: string; lines: JournalLine[];
}

// Phase K2: financial statements
export interface LedgerPeriods { yearEndDay: number; yearEndMonth: number; lockDate: string | null; hardLockDate: string | null }
export interface StatementColumn { label: string; from: string | null; to: string }
export interface StatementRow { code: string | null; name: string; values: number[] }
export interface StatementSection { title: string; rows: StatementRow[]; totals: number[] }
export interface StatementTotal { title: string; values: number[] }
export interface ProfitAndLoss {
  columns: StatementColumn[]; tradingIncome: StatementSection; costOfSales: StatementSection; grossProfit: StatementTotal;
  otherIncome: StatementSection; operatingExpenses: StatementSection; netProfit: StatementTotal;
}
export interface BalanceSheet {
  columns: StatementColumn[]; assets: StatementSection[]; totalAssets: StatementTotal; liabilities: StatementSection[];
  totalLiabilities: StatementTotal; netAssets: StatementTotal; equity: StatementSection; warnings: string[];
}

// Phase K3: bank reconciliation
export interface BankAccountCard {
  code: string; name: string; ledgerBalance: number; statementBalance: number | null; statementDate: string | null;
  toReconcile: number; unreconciledStatement: number; unmatchedLedger: number;
}
export interface BankParsedLine { date: string; amount: number; payee: string | null; reference: string | null; description: string | null; balance: number | null; duplicate: boolean }
export interface BankImportResult {
  applied: boolean; format: string; lines: number; duplicates: number; moneyIn: number; moneyOut: number; from: string | null; to: string | null;
  preview: BankParsedLine[]; warnings: string[];
}
export interface BankItem { sourceKey: string; sourceType: string; date: string | null; amount: number; reference: string | null; narration: string; contact: string | null }
export interface BankSuggestion { item: BankItem; score: number }
export interface BankStatementLine {
  id: number; date: string; amount: number; payee: string | null; reference: string | null; description: string | null; balance: number | null;
  status: "UNRECONCILED" | "RECONCILED"; suggestions: BankSuggestion[]; matched: BankItem[]; reconciledAt: string | null; reconciledBy: string | null;
}
export interface BankTransactionLine { accountCode: string; description: string | null; net: number; taxRule: string | null; tax: number }
export interface BankTransaction {
  id: number; kind: "SPEND" | "RECEIVE" | "TRANSFER"; date: string; accountCode: string; toAccountCode: string | null; contact: string | null;
  reference: string | null; total: number; status: "ACTIVE" | "VOIDED"; reconciled: boolean; lines: BankTransactionLine[];
}

// Phase K4: VAT returns and Making Tax Digital
export interface MtdStatus { configured: boolean; vrn: string | null; connected: boolean; connectedAt: string | null; connectedBy: string | null }
export interface VatPeriod { periodKey: string; start: string; end: string; due: string; status: "O" | "F"; filedReturnId: number | null }
export interface VatBoxes { box1: number; box2: number; box3: number; box4: number; box5: number; box6: number; box7: number; box8: number; box9: number }
export interface VatReview {
  vatReturn: { from: string; to: string; boxes: VatBoxes; refund: boolean; documents: { sourceKey: string; number: string; date: string; party: string | null; net: number; vat: number; side: string }[] };
  ledgerVatMovement: number; warnings: string[]; declaration: string;
}
export interface FiledVatReturn {
  id: number; periodKey: string | null; from: string; to: string; due: string | null; boxes: VatBoxes; how: "MTD" | "MANUAL"; filedAt: string; filedBy: string | null;
  processingDate: string | null; formBundleNumber: string | null; paymentIndicator: string | null; chargeRefNumber: string | null; note: string | null;
}

// Phase L: the Xero API link
export interface XeroOrganisation { tenantId: string; tenantName: string; tenantType: string }
export interface XeroConnection {
  mode: "fake" | "live"; configured: boolean; status: "NOT_CONNECTED" | "CHOOSE_ORGANISATION" | "CONNECTED" | "EXPIRED";
  xeroTenantName: string | null; organisations: XeroOrganisation[]; connectedAt: string | null; connectedBy: string | null; lastError: string | null;
}
export interface XeroQueueItem {
  id: number; key: string; kind: string; label: string | null; dependsOn: string | null; xeroId: string | null; status: "PENDING" | "SENT" | "FAILED";
  attempts: number; lastError: string | null; nextAttemptAt: string; createdAt: string; sentAt: string | null;
}
export interface XeroQueue { counts: Record<string, number>; items: XeroQueueItem[] }
export interface XeroPulledPayment {
  id: number; xeroPaymentId: string; xeroStatus: string | null; docKey: string | null; number: string | null; erpPaymentId: number | null;
  amount: number | null; paidOn: string | null; status: "RECORDED" | "VOIDED" | "UNMATCHED" | "ERROR"; message: string | null; pulledAt: string;
}

// Phase M: e-mail
export interface MailDraft { docType: string; docKey: string; docLabel: string; to: string | null; cc: string | null; subject: string; body: string;
  attachmentName: string; missingRecipient: string | null }
export interface SentMail { id: number; docType: string; docKey: string; docLabel: string | null; to: string; cc: string | null; subject: string; body: string;
  attachmentName: string | null; mode: "fake" | "live"; status: "KEPT" | "SENT" | "FAILED"; error: string | null; createdAt: string; createdBy: string | null }
export interface MailSettings { mode: "fake" | "live"; fromName: string | null; fromAddress: string | null; replyTo: string | null; smtpHost: string | null;
  smtpPort: number | null; smtpUsername: string | null; smtpPasswordSet: boolean; smtpStartTls: boolean }
export interface MailTemplate { docType: string; subject: string; body: string; custom: boolean }
export interface MailBulkResult { customerId: number; customer: string; status: "KEPT" | "SENT" | "FAILED" | "SKIPPED"; message: string | null }

// Phase N1: bins
export interface Bin { id: number; warehouseId: number; code: string; walkOrder: number; active: boolean; units: number; products: number }
export interface BinStock { binId: number; code: string; walkOrder: number; quantity: number }
export interface ProductBins { productId: number; warehouseId: number; warehouseStock: number; unbinned: number; defaultBinId: number | null; bins: BinStock[] }

// Phase N2 / N3: pick, pack and ship
export interface PickLine { id: number; saleOrderId: number; orderNumber: string; saleOrderLineId: number; productId: number; sku: string; name: string;
  barcode: string | null; binId: number | null; bin: string | null; walkOrder: number; quantity: number; picked: number }
export interface PickList { id: number; number: string; warehouseId: number; warehouse: string; status: "OPEN" | "PICKED" | "CANCELLED"; orders: string[];
  lines: PickLine[]; createdAt: string; createdBy: string | null; pickedAt: string | null; pickedBy: string | null }
export interface OrderLineState { saleOrderLineId: number; sku: string; name: string; ordered: number; allocated: number; picked: number; packed: number; shipped: number }
export interface WarehousePackage { id: number; number: number; weightKg: number | null; lengthCm: number | null; widthCm: number | null; heightCm: number | null;
  status: "PACKED" | "SHIPPED"; fulfilmentId: number | null; lines: { sale_order_line_id: number; sku: string; name: string; quantity: number }[] }
export interface OrderWarehouse { saleOrderId: number; orderNumber: string; stage: string; lines: OrderLineState[]; packages: WarehousePackage[] }

// Phase N5: stock adjustments with reasons and approval; stock takes
export interface StockAdjustment { id: number; productId: number; sku: string; productName: string; warehouseId: number; warehouse: string; binId: number | null;
  bin: string | null; quantity: number; reasonCode: string; note: string | null; unitCost: number; value: number; status: "PENDING" | "POSTED" | "REJECTED";
  movementId: number | null; requestedBy: string | null; requestedAt: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null }
export interface StockTakeLine { id: number; productId: number; sku: string; name: string; barcode: string | null; binId: number | null; bin: string | null;
  walkOrder: number | null; expected: number; counted: number | null; variance: number | null; unitCost: number | null; varianceValue: number | null }
export interface StockTake { id: number; reference: string; warehouseId: number; warehouse: string; scope: "FULL" | "PARTIAL"; note: string | null;
  status: "COUNTING" | "POSTED" | "CANCELLED"; createdAt: string; createdBy: string | null; postedAt: string | null; postedBy: string | null;
  lineCount: number; countedLines: number; varianceValue: number; lines: StockTakeLine[] }
export interface FoundProduct { id: number; sku: string; name: string; barcode: string | null }

// Phase O: couriers
export interface CourierService { carrier: string; serviceCode: string; name: string; price: number; days: number }
export interface CourierQuote { mode: string; cartons: number[]; services: CourierService[] }
export interface CourierShipment { id: number; saleOrderId: number; mode: string; carrier: string; serviceCode: string; serviceName: string; price: number | null;
  consignmentId: string; trackingNumber: string | null; trackingUrl: string | null; parcels: number; status: "BOOKED" | "SHIPPED" | "DELIVERED" | "CANCELLED";
  trackingStatus: string | null; trackingText: string | null; trackedAt: string | null; fulfilmentId: number | null; createdAt: string; createdBy: string | null; cartons: number[] }

// Phase P1 / P4: product compliance and files
export type ProductFileKind = "IMAGE" | "DATASHEET" | "DECLARATION" | "CERTIFICATE" | "OTHER";
export interface ProductFile { id: number; productId: number; kind: ProductFileKind; fileName: string; contentType: string; sizeBytes: number; position: number;
  createdAt: string; createdBy: string | null }
export interface ProductCompliance { productId: number; standards: string | null; ceMarked: boolean; ukcaMarked: boolean; protectionClass: string | null;
  certificateNumber: string | null; certificateIssuer: string | null; certificateExpiry: string | null; notes: string | null; certificateFile: ProductFile | null }
export interface ExpiringCertificate { productId: number; sku: string; name: string; standards: string | null; certificateNumber: string | null;
  certificateIssuer: string | null; certificateExpiry: string; daysLeft: number; hasFile: boolean }

// Phase P2: kits
export interface KitComponent { componentId: number; sku: string; name: string; unit: string; quantity: number }
export interface KitBuildable { warehouseId: number; warehouse: string; onHand: number; available: number }

// Phase P3: units of measure
export interface ProductUnit { id: number; name: string; factor: number; barcode: string | null; sellPrice: number | null; buyPrice: number | null }

// Review R9: one row of the product selection list (GET /products/picker)
export interface PickerProduct {
  id: number; sku: string; name: string; unit?: string; barcode?: string | null;
  sellPrice?: number | null; costPrice?: number | null;
  onHand: number; available: number; onOrder: number; supplierProduct: boolean;
}
export interface PickerPage { rows: PickerProduct[]; more: boolean }
