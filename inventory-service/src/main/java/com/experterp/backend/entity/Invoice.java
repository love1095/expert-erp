package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices")
@Data
@NoArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceType type;

    @Column(nullable = false)
    private String partyName;

    @Column
    private Long saleOrderId;

    @Column
    private Long purchaseOrderId;

    // Bills: the supplier record (the invoice number is the supplier's own)
    @Column
    private Long supplierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column
    private LocalDate issueDate;

    @Column
    private LocalDate dueDate;

    // Amount to pay, VAT included = subtotal (net) + taxTotal
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column
    private Long customerId;

    // The shipment it was made for, when invoiced on shipment
    @Column
    private Long fulfilmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Kind kind = Kind.INVOICE;

    // Credit notes: the invoice they credit
    @Column
    private Long creditedInvoiceId;

    @Column(nullable = false)
    private String currency = "USD";

    @Column
    private String notes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Payment> payments = new ArrayList<>();

    public enum InvoiceType   { SALES, PURCHASE }
    public enum Kind          { INVOICE, CREDIT_NOTE }
    public enum InvoiceStatus { DRAFT, ISSUED, PAID, CANCELLED }
}
