package com.experterp.backend.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockLevelResponse {
    private Long productId;
    private String productSku;
    private String productName;
    private String unit;
    private BigDecimal minStock;
    private Long warehouseId;
    private String warehouseName;
    private Long categoryId;
    private String categoryName;
    private BigDecimal stock;
    // Reserved for sale orders shipping from this warehouse; available = stock − reserved (can be < 0)
    private BigDecimal reserved = BigDecimal.ZERO;
    private BigDecimal available;
    private boolean lowStock; // true when stock <= minStock (and minStock is set)
}
