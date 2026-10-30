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
    private String purchaseOrderNumber;
    private Long supplierId;
    // Bills: number of lines that don't match the PO or the receipts
    private int matchIssueCount;
    private Invoice.InvoiceStatus status;
    private LocalDate issueDate;
    private LocalDate dueDate;
    // To pay, VAT included = subtotal + taxTotal
    private BigDecimal totalAmount;
    private BigDecimal subtotal;
    private BigDecimal taxTotal;
    private Long customerId;
    // Made for this shipment (invoiced on shipment)
    private Long fulfilmentId;
    private Invoice.Kind kind;
    // Credit notes: the invoice credited, and how much of the credit paid off invoices (amountPaid includes it)
    private Long creditedInvoiceId;
    private String creditedInvoiceNumber;
    private BigDecimal creditApplied;
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
        // Net amount; VAT copied from the order line
        private BigDecimal totalPrice;
        private BigDecimal discountPercent;
        private String taxName;
        private BigDecimal taxRate;
        private BigDecimal taxAmount;
        private Long saleOrderLineId;
        private Long saleOrderChargeId;
        private Long purchaseOrderLineId;
        // Bills, three-way match: what differs from the PO and the receipts (empty = matches)
        private List<String> matchIssues;
        // Invoice items: quantity already on credit notes
        private BigDecimal creditedQuantity;
    }

    @Data
    public static class PaymentResponse {
        private Long id;
        private BigDecimal amount;
        private Payment.PaymentMethod method;
        private LocalDateTime paidAt;
        private String reference;
        // Paid with this credit note
        private Long creditNoteId;
    }
}
