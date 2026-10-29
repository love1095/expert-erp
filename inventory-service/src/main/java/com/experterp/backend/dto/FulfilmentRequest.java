package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

    // Optional; sent to Shopify for orders that came from there
    @Size(max = 100)
    private String trackingCompany;
    @Size(max = 255)
    private String trackingNumber;

    // Empty or missing = ship everything still remaining
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        @NotNull private Long saleOrderLineId;
        @NotNull @Positive private BigDecimal quantity;
    }
}
