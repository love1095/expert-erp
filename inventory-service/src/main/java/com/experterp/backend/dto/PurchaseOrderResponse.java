package com.experterp.backend.dto;

import com.experterp.backend.entity.PurchaseOrder.Status;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseOrderResponse {
    private Long id;
    private String supplierName;
    private Long warehouseId;
    private String warehouseName;
    private Status status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime receivedAt;
    private List<Line> lines;

    @Data
    public static class Line {
        private Long id;
        private Long productId;
        private String productSku;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal unitCost;
    }
}
