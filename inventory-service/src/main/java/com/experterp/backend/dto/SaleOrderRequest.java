package com.experterp.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class SaleOrderRequest {

    // An existing customer; or leave it empty and give customerName to reuse/create a customer by name
    private Long customerId;

    private String customerName;

    @NotNull(message = "Warehouse is required")
    private Long warehouseId;

    private String notes;

    // The customer's own reference (their PO number)
    @Size(max = 100)
    private String customerReference;

    // Empty = the customer's sales rep
    @Size(max = 100)
    private String salesRep;

    // Empty = the customer's payment terms
    @Size(max = 100)
    private String paymentTerm;

    private LocalDate shipBy;

    @Size(max = 100)
    private String carrier;

    private String shippingNotes;

    @NotEmpty(message = "At least one line is required")
    @Valid
    private List<Line> lines;

    @Valid
    private List<Charge> charges = new ArrayList<>();

    @JsonIgnore
    @AssertTrue(message = "Customer is required")
    public boolean isCustomerGiven() {
        return customerId != null || (customerName != null && !customerName.isBlank());
    }

    @Data
    public static class Line {
        @NotNull private Long productId;
        @NotNull @Positive private BigDecimal quantity;
        private BigDecimal unitPrice;
        // Empty = the customer's discount
        @DecimalMin("0") @DecimalMax("100") private BigDecimal discountPercent;
        // Empty = the product's / customer's / default tax rule; a different one needs MANAGER or above
        private String taxRule;
        // Required when taxRule gives less VAT than the rule that would apply
        private String taxReason;
    }

    @Data
    public static class Charge {
        @NotBlank @Size(max = 255) private String description;
        @Positive private BigDecimal quantity = BigDecimal.ONE;
        @NotNull @DecimalMin("0") private BigDecimal unitPrice;
        @DecimalMin("0") @DecimalMax("100") private BigDecimal discountPercent;
        // Empty = the customer's / default tax rule; a different one needs MANAGER or above
        private String taxRule;
        private String taxReason;
    }
}
