export interface Page<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description?: string;
  unit: string;
  categoryId?: number;
  categoryName?: string;
  costPrice?: number;
  minStock?: number;
  totalStock: number;
  lowStock: boolean;
  createdAt: string;
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
