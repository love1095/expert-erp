package com.experterp.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductResponse {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private String unit;
    private Long categoryId;
    private String categoryName;
    private BigDecimal costPrice;
    private BigDecimal minStock;
    private LocalDateTime createdAt;
    private BigDecimal totalStock;
    private boolean lowStock;
}
