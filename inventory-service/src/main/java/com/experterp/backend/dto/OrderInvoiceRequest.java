package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** An invoice made from a sale order. */
@Data
public class OrderInvoiceRequest {

    // Quantities to invoice per order line; empty = everything not invoiced yet
    @Valid
    private List<Line> lines;

    // The order's additional charges not invoiced yet
    private boolean includeCharges = true;

    // Issue it straight away (otherwise it stays a draft)
    private boolean issue;

    private String notes;

    @Data
    public static class Line {
        @NotNull private Long saleOrderLineId;
        @NotNull @Positive private BigDecimal quantity;
    }
}
