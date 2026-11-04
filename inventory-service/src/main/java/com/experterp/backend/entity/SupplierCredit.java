package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money paid to a supplier that is not on a bill yet (phase H2, Cin7's supplier deposit): a deposit (e.g. a proforma
 * paid before the goods), or the rest of an overpaid bill. Used on bills later ("Use supplier deposit").
 */
@Entity
@Table(name = "supplier_credits")
@Data
@NoArgsConstructor
public class SupplierCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.PaymentMethod method;

    @Column(name = "payment_account_id")
    private Long paymentAccountId;

    @Column(length = 100)
    private String reference;

    @Column(name = "source_invoice_id")
    private Long sourceInvoiceId;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(length = 255)
    private String note;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
