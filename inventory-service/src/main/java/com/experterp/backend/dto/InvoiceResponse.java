package com.experterp.backend.dto;

import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.Payment;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class InvoiceResponse {

    private Long id;
    private String invoiceNumber;
    private Invoice.InvoiceType type;
    private String partyName;
    private Long saleOrderId;
    private Long purchaseOrderId;
    private Invoice.InvoiceStatus status;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal totalAmount;
    private BigDecimal amountPaid;
    private String currency;
    private String notes;
    private LocalDateTime createdAt;
    private List<ItemResponse> items;
    private List<PaymentResponse> payments;

    @Data
    public static class ItemResponse {
        private Long id;
        private Long productId;
        private String productName;
        private String productSku;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    @Data
    public static class PaymentResponse {
        private Long id;
        private BigDecimal amount;
        private Payment.PaymentMethod method;
        private LocalDateTime paidAt;
    }
}
