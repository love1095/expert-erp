package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A supplier's bill for a purchase order. No lines = what was received and not billed yet. */
@Data
public class BillRequest {

    // The supplier's own invoice number, e.g. 006387333
    @NotBlank(message = "The supplier's invoice number is required")
    @Size(max = 100)
    private String supplierInvoiceNumber;

    // Empty = today
    private LocalDate issueDate;

    // Empty = from the order's terms
    private LocalDate dueDate;

    private String notes;

    // Entered as awaiting payment (default) or kept as a draft
    private boolean draft;

    @Valid
    private List<Line> lines = new ArrayList<>();

    // The supplier's extra charges on the bill (shipping, duty...): an expense
    @Valid
    private List<Charge> charges = new ArrayList<>();

    @Data
    public static class Line {
        @NotNull private Long purchaseOrderLineId;
        @NotNull @Positive private BigDecimal quantity;
        // Empty = the PO price; a different price is flagged
        @DecimalMin("0") private BigDecimal unitPrice;
    }

    @Data
    public static class Charge {
        @NotBlank @Size(max = 255) private String description;
        @Positive private BigDecimal quantity = BigDecimal.ONE;
        @NotNull @DecimalMin("0") private BigDecimal unitPrice;
        // Empty = the supplier's / default purchase tax rule
        @Size(max = 100) private String taxRule;
    }
}
