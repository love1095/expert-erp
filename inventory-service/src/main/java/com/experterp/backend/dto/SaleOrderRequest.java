package com.experterp.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SaleOrderRequest {

    @NotBlank(message = "Customer name is required")
    private String customerName;

    @NotNull(message = "Warehouse is required")
    private Long warehouseId;

    private String notes;

    @NotEmpty(message = "At least one line is required")
    private List<Line> lines;

    @Data
    public static class Line {
        @NotNull private Long productId;
        @NotNull @Positive private BigDecimal quantity;
        private BigDecimal unitPrice;
    }
}
