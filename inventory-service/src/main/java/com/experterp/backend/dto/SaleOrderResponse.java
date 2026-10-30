package com.experterp.backend.dto;

import com.experterp.backend.entity.Fulfilment;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.entity.SaleOrder.FulfilmentStatus;
import com.experterp.backend.entity.SaleOrder.Status;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SaleOrderResponse {
    private Long id;
    private String orderNumber;
    private String customerReference;
    private String salesRep;
    private String paymentTerm;
    private LocalDate shipBy;
    private String carrier;
    private String shippingNotes;
    // Σ lines and charges, cancelled quantities left out
    private BigDecimal subtotal;
    private BigDecimal taxTotal;
    private BigDecimal total;
    private List<Charge> charges;
    // A reserving order (confirmed, or imported from Shopify) the stock on hand cannot cover in full
    private boolean backordered;
    private Long customerId;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
    private Status status;
    private FulfilmentStatus fulfilmentStatus;
    private String notes;
    private SaleOrder.Source source;
    private String externalReference;
    private boolean needsReview;
    private String reviewReason;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime fulfilledAt;
    private LocalDateTime cancelledAt;
    private List<Line> lines;
    // Only populated on single-order responses (detail, actions); null in lists
    private List<FulfilmentEntry> fulfilments;
    private List<ReturnEntry> returns;

    @Data
    public static class Line {
        private Long id;
        private Long productId;
        private String productSku;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal fulfilledQuantity;
        private BigDecimal cancelledQuantity;
        private BigDecimal returnedQuantity;
        private BigDecimal remainingQuantity;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private boolean taxOverridden;
        // For the quantity not cancelled
        private BigDecimal netAmount;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
        // Over the product's cost price (FIFO cost once costing exists); null without a cost
        private BigDecimal marginPercent;
        // Reserving orders only (null otherwise): of the quantity still to ship, what the stock on hand covers
        // (first confirmed, first served) and what is short
        private BigDecimal allocatedQuantity;
        private BigDecimal backorderedQuantity;
    }

    @Data
    public static class Charge {
        private Long id;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private boolean taxOverridden;
        private BigDecimal netAmount;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
    }

    @Data
    public static class FulfilmentEntry {
        private Long id;
        private String notes;
        private String trackingCompany;
        private String trackingNumber;
        // Shopify orders only: PENDING / SYNCED / FAILED, with the error or a note
        private Fulfilment.SyncStatus syncStatus;
        private String syncMessage;
        private String createdBy;
        private LocalDateTime createdAt;
        private List<EntryLine> lines;
    }

    @Data
    public static class ReturnEntry {
        private Long id;
        private String reason;
        private Long warehouseId;
        private String warehouseName;
        private String createdBy;
        private LocalDateTime createdAt;
        private List<EntryLine> lines;
    }

    @Data
    public static class EntryLine {
        private Long saleOrderLineId;
        private String productSku;
        private String productName;
        private BigDecimal quantity;
        // Returns only
        private Boolean restock;
    }
}
