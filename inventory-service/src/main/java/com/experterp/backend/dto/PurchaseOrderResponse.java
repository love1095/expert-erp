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
    private Long warehouseId;
    private String warehouseName;
    private Status status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime orderedAt;
    private LocalDateTime receivedAt;
    private List<Line> lines;
    private List<Cost> costs;
    // Totals like Cin7: order lines, additional costs, and both together, before tax / tax
    private BigDecimal linesNet;
    private BigDecimal linesTax;
    private BigDecimal costsNet;
    private BigDecimal costsTax;
    private BigDecimal subtotal;
    private BigDecimal taxTotal;
    private BigDecimal total;

    @Data
    public static class Line {
        private Long id;
        private Long productId;
        private String productSku;
        private String productName;
        private String unit;
        private String supplierSku;
        private BigDecimal quantity;
        private BigDecimal unitCost;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private boolean taxOverridden;
        private BigDecimal netAmount;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
    }

    @Data
    public static class Cost {
        private Long id;
        private String description;
        private String reference;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private BigDecimal netAmount;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
    }
}
