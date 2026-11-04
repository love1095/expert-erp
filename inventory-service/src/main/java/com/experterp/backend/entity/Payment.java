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

    // Phase H1: the payment account the money went to (e.g. 650 Current Account - RBS)
    @Column(name = "payment_account_id")
    private Long paymentAccountId;

    // Phase H1: paid with customer credit (an earlier overpayment or prepayment): the credit it came from
    @Column(name = "customer_credit_id")
    private Long customerCreditId;

    // Phase H2: a bill paid with supplier credit (a deposit or an earlier overpayment)
    @Column(name = "supplier_credit_id")
    private Long supplierCreditId;

    // CREDIT_NOTE = paid with a credit note; CUSTOMER_CREDIT = paid with customer credit; a payment recorded on a
    // credit note is a refund. STRIPE is the old name for a card payment (CARD).
    public enum PaymentMethod { CASH, BANK, STRIPE, CARD, PAYPAL, CREDIT_NOTE, CUSTOMER_CREDIT, SUPPLIER_CREDIT, SHOPIFY;

        /** Paid with credit the customer already had, not with new money. */
        public boolean isCredit() {
            return this == CREDIT_NOTE || this == CUSTOMER_CREDIT || this == SUPPLIER_CREDIT;
        }
    }
}
