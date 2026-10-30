package com.experterp.backend.dto;

import com.experterp.backend.entity.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A credit note for an invoice (Cin7's "Credit note & restock"). */
@Data
public class CreditNoteRequest {

    // Quantities credited per invoice item
    @Valid
    private List<Line> lines = new ArrayList<>();

    // Amounts credited that are not on the invoice, e.g. a shipping refund
    @Valid
    private List<Charge> charges = new ArrayList<>();

    @Size(max = 255)
    private String reason;

    // Where restocked goods go back to (default: the order's warehouse)
    private Long warehouseId;

    // How the credit is settled; empty = pay off what is still due on the invoice, keep the rest as credit
    private Settle settle;

    // Refunds: how the money went back and its reference (e.g. the bank transaction)
    private Payment.PaymentMethod refundMethod = Payment.PaymentMethod.BANK;

    @Size(max = 100)
    private String refundReference;

    public enum Settle { APPLY_TO_INVOICE, REFUND, KEEP_AS_CREDIT }

    @Data
    public static class Line {
        @NotNull private Long invoiceItemId;
        @NotNull @Positive private BigDecimal quantity;
        // Goods come back into stock (they must have been shipped); otherwise only the money is credited
        private boolean restock;
    }

    @Data
    public static class Charge {
        @NotBlank @Size(max = 255) private String description;
        @Positive private BigDecimal quantity = BigDecimal.ONE;
        @NotNull @DecimalMin("0") private BigDecimal unitPrice;
        // Empty = the customer's / default tax rule
        private String taxRule;
    }
}
