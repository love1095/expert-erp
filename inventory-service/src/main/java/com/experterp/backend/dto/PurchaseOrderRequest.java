package com.experterp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class PurchaseOrderRequest {

    // The supplier record; its terms, tax rule and name are copied onto the order
    private Long supplierId;

    // Only when there is no supplier record (older clients); ignored when supplierId is given
    @Size(max = 255)
    private String supplierName;

    @NotNull(message = "Warehouse is required")
    private Long warehouseId;

    @Size(max = 100)
    private String supplierReference;

    // Empty = the supplier's terms
    @Size(max = 100)
    private String paymentTerm;

    private LocalDate requiredBy;

    private String notes;

    // Place it with the supplier straight away (Cin7's Authorise) instead of saving a draft
    private boolean ordered;

    @NotEmpty(message = "At least one line is required")
    @Valid
    private List<Line> lines;

    @Valid
    private List<Cost> costs = new ArrayList<>();

    @Data
    public static class Line {
        @NotNull private Long productId;
        @NotNull @Positive private BigDecimal quantity;
        // Empty = the product's cost price
        @DecimalMin("0") private BigDecimal unitCost;
        @DecimalMin("0") @DecimalMax("100") private BigDecimal discountPercent;
        // Empty = the product's supplier code
        @Size(max = 100) private String supplierSku;
        // Empty = the rule (product, supplier, organisation default); another rate needs a manager
        @Size(max = 100) private String taxRule;
        @Size(max = 255) private String taxReason;
    }

    @Data
    public static class Cost {
        @NotBlank @Size(max = 255) private String description;
        @Size(max = 100) private String reference;
        @Positive private BigDecimal quantity = BigDecimal.ONE;
        @NotNull @DecimalMin("0") private BigDecimal unitPrice;
        @DecimalMin("0") @DecimalMax("100") private BigDecimal discountPercent;
        @Size(max = 100) private String taxRule;
        @Size(max = 255) private String taxReason;
    }
}
