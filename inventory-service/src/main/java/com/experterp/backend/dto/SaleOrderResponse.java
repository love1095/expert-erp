package com.experterp.backend.dto;

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
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime fulfilledAt;
    private List<Line> lines;

    @Data
    public static class Line {
        private Long id;
        private Long productId;
        private String productSku;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
    }
}
