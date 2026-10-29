package com.experterp.backend.dto;

import com.experterp.backend.entity.SaleOrder.FulfilmentStatus;
import com.experterp.backend.entity.SaleOrder.Status;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SaleOrderResponse {
    private Long id;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
    private Status status;
    private FulfilmentStatus fulfilmentStatus;
    private String notes;
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
    }

    @Data
    public static class FulfilmentEntry {
        private Long id;
        private String notes;
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
