package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private LocalDateTime paidAt = LocalDateTime.now();

    // An invoice paid with a credit note: the credit note it came from
    @Column
    private Long creditNoteId;

    // e.g. the bank transaction reference
    @Column(length = 100)
    private String reference;

    // CREDIT_NOTE = paid with a credit note; a payment recorded on a credit note is a refund
    public enum PaymentMethod { CASH, BANK, STRIPE, CREDIT_NOTE }
}
