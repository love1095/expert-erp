package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Products chosen on the reorder screen: one draft PO per default supplier (Cin7's "Reorder"). */
@Data
public class ReorderRequest {

    @NotNull(message = "Choose the warehouse to deliver to")
    private Long warehouseId;

    @NotEmpty(message = "Choose at least one product")
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        @NotNull private Long productId;
        @NotNull @Positive private BigDecimal quantity;
    }
}
