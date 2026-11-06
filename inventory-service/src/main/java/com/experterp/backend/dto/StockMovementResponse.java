package com.experterp.backend.dto;

import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StockMovementResponse {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private Long categoryId;
    private String categoryName;
    private Long warehouseId;
    private String warehouseName;
    private BigDecimal quantity;
    private MovementType type;
    private ReferenceType referenceType;
    private String referenceId;
    private String notes;
    // Stock in: the cost of one unit its FIFO layer opened at
    @com.experterp.backend.security.Restricted(com.experterp.backend.security.Permission.STOCK_VALUE) private BigDecimal unitCost;
    private LocalDateTime createdAt;
}
