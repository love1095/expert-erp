package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SaleReturnRequest {

    private String idempotencyKey;

    private String reason;

    // Defaults to the order's warehouse
    private Long warehouseId;

    @NotEmpty(message = "At least one line is required")
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        @NotNull private Long saleOrderLineId;
        @NotNull @Positive private BigDecimal quantity;
        // true = goods go back into sellable stock; false = recorded only (e.g. damaged)
        private boolean restock = true;
    }
}
