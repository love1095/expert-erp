package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FulfilmentRequest {

    // Optional: a retry with the same key returns the original result instead of shipping again
    private String idempotencyKey;

    private String notes;

    // Empty or missing = ship everything still remaining
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        @NotNull private Long saleOrderLineId;
        @NotNull @Positive private BigDecimal quantity;
    }
}
