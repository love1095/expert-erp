package com.experterp.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockTransferRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "From warehouse is required")
    private Long fromWarehouseId;

    @NotNull(message = "To warehouse is required")
    private Long toWarehouseId;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private BigDecimal quantity;

    private String notes;
}
