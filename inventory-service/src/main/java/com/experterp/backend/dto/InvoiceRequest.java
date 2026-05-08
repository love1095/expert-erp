package com.experterp.backend.dto;

import com.experterp.backend.entity.Invoice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class InvoiceRequest {

    @NotNull(message = "Type is required")
    private Invoice.InvoiceType type;

    @NotBlank(message = "Party name is required")
    private String partyName;

    private Long saleOrderId;
    private Long purchaseOrderId;

    @NotNull(message = "Issue date is required")
    private LocalDate issueDate;

    private LocalDate dueDate;

    private String currency = "USD";
    private String notes;

    @NotEmpty(message = "At least one item is required")
    private List<ItemRequest> items;

    @Data
    public static class ItemRequest {
        private Long productId;
        private String description;
        @NotNull @Positive private BigDecimal quantity;
        @NotNull @Positive private BigDecimal unitPrice;
    }
}
