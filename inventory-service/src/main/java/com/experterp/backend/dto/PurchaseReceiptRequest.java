package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Goods received against a purchase order. No lines = everything still outstanding. */
@Data
public class PurchaseReceiptRequest {

    // One key per attempt: a retried request is applied only once
    @Size(max = 100)
    private String idempotencyKey;

    // Empty = today
    private LocalDate receivedOn;

    // Empty = the order's warehouse
    private Long warehouseId;

    private String notes;

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Data
    public static class Line {
        @NotNull private Long purchaseOrderLineId;
        // More than outstanding is accepted and flagged as an over-delivery
        @NotNull @Positive private BigDecimal quantity;
    }
}
