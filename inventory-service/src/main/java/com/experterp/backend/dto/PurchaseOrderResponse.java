package com.experterp.backend.dto;

import com.experterp.backend.entity.PurchaseOrder.Status;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseOrderResponse {
    private Long id;
    // e.g. PO-19074 (absent on a preview)
    private String orderNumber;
    private Long supplierId;
    private String supplierName;
    private String supplierReference;
    private String paymentTerm;
    private LocalDate requiredBy;
    // Like Cin7's New purchase: supplier, accounting and shipping details
    private String contactName;
    private String contactPhone;
    private com.experterp.backend.entity.Address vendorAddress;
    private LocalDate orderDate;
    // The order's own tax rule (null = the supplier's), and the rule its lines start from
    private String taxRule;
    private String defaultTaxRule;
    // e.g. "631: Inventory Control", from the accounting settings
    private String inventoryAccount;
    private boolean blindReceipt;
    private String shipToCompany;
    private com.experterp.backend.entity.Address shippingAddress;
    private Long warehouseId;
    private String warehouseName;
    private Status status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime orderedAt;
    private LocalDateTime receivedAt;
    // NOT_RECEIVED, PARTIALLY_RECEIVED or RECEIVED (everything in, or the rest closed short)
    private String receivingStatus;
    private boolean overDelivered;
    // NOT_BILLED, PARTIALLY_BILLED or BILLED
    private String billingStatus;
    // Only on a single order: UNPAID, PARTIALLY_PAID or PAID across its bills (null when there are none)
    private String paymentStatus;
    private List<Bill> bills;
    private boolean closedShort;
    private List<Line> lines;
    private List<Cost> costs;
    // Only on a single order
    private List<Receipt> receipts;
    // Totals like Cin7: order lines, additional costs, and both together, before tax / tax
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal linesNet;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal linesTax;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal costsNet;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal costsTax;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal subtotal;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal taxTotal;
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal total;

    @Data
    public static class Line {
        private Long id;
        private Long productId;
        private String productSku;
        private String productName;
        private String unit;
        // Phase P3: the line's own unit (empty: the product's) and the base units one holds
        private String unitName;
        private BigDecimal unitFactor;
        private String supplierSku;
        private BigDecimal quantity;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal unitCost;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private boolean taxOverridden;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal netAmount;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal taxAmount;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal totalAmount;
        private BigDecimal receivedQuantity;
        private BigDecimal billedQuantity;
        private BigDecimal closedShortQuantity;
        private BigDecimal outstandingQuantity;
        // Received above what was ordered
        private BigDecimal overQuantity;
    }

    @Data
    public static class Bill {
        private Long id;
        // The supplier's invoice number
        private String invoiceNumber;
        private com.experterp.backend.entity.Invoice.InvoiceStatus status;
        private LocalDate issueDate;
        private LocalDate dueDate;
        private BigDecimal total;
        private BigDecimal amountPaid;
        private int matchIssueCount;
    }

    @Data
    public static class Receipt {
        private Long id;
        private LocalDate receivedOn;
        private String warehouseName;
        private String notes;
        private String createdBy;
        private LocalDateTime createdAt;
        private List<ReceiptLine> lines;
    }

    @Data
    public static class ReceiptLine {
        private Long purchaseOrderLineId;
        private String productSku;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal overQuantity;
    }

    @Data
    public static class Cost {
        private Long id;
        private String description;
        private String reference;
        private BigDecimal quantity;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal unitPrice;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal netAmount;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal taxAmount;
        @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.PURCHASE_PRICES) private BigDecimal totalAmount;
    }
}
