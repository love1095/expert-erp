package com.experterp.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SaleOrderRequest {

    // An existing customer; or leave it empty and give customerName to reuse/create a customer by name
    private Long customerId;

    private String customerName;

    @NotNull(message = "Warehouse is required")
    private Long warehouseId;

    private String notes;

    @NotEmpty(message = "At least one line is required")
    private List<Line> lines;

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
    }
}
