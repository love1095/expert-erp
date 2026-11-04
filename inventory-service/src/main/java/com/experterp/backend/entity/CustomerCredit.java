package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money a customer paid that is not on an invoice yet (phase H1, Cin7's Customer credit): the rest of an
 * overpayment, or a payment before the invoice. Used on invoices later; what is used is the payments made with it.
 */
@Entity
@Table(name = "customer_credits")
@Data
@NoArgsConstructor
public class CustomerCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.PaymentMethod method;

    @Column(name = "payment_account_id")
    private Long paymentAccountId;

    @Column(length = 100)
    private String reference;

    @Column(name = "source_invoice_id")
    private Long sourceInvoiceId;

    @Column(length = 255)
    private String note;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
