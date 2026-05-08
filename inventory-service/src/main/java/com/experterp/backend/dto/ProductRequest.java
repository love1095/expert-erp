package com.experterp.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductRequest {

    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    @NotBlank(message = "Unit is required")
    private String unit;

    private Long categoryId;
    private BigDecimal costPrice;
    private BigDecimal minStock;
}
