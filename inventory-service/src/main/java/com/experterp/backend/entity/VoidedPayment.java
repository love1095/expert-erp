package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A payment a manager voided (phase H1): out of every total, kept with who, when and why. */
@Entity
@Table(name = "voided_payments")
@Data
@NoArgsConstructor
public class VoidedPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.PaymentMethod method;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @Column(name = "payment_account_id")
    private Long paymentAccountId;

    @Column(length = 100)
    private String reference;

    @Column(name = "credit_note_id")
    private Long creditNoteId;

    @Column(name = "customer_credit_id")
    private Long customerCreditId;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "voided_by", nullable = false, length = 100)
    private String voidedBy;

    @Column(name = "voided_at", nullable = false)
    private LocalDateTime voidedAt = LocalDateTime.now();
}
