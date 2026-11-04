package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * Where money is paid in or out (phase H1), like Cin7's payment accounts from the Xero chart: e.g. 650 Current
 * Account - RBS, 643 Card Payments. Per organisation, one default. The code is what the accounting export uses (H5).
 */
@Entity
@Table(name = "payment_accounts")
@Data
@NoArgsConstructor
public class PaymentAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind = Kind.BANK;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAccount;

    public enum Kind {
        BANK, CARD, CASH, PAYPAL, SHOPIFY, OTHER;

        /** The payment method a payment to an account of this kind is recorded with. */
        public Payment.PaymentMethod method() {
            return switch (this) {
                case BANK, OTHER -> Payment.PaymentMethod.BANK;
                case CARD -> Payment.PaymentMethod.CARD;
                case CASH -> Payment.PaymentMethod.CASH;
                case PAYPAL -> Payment.PaymentMethod.PAYPAL;
                case SHOPIFY -> Payment.PaymentMethod.SHOPIFY;
            };
        }
    }

    public String label() {
        return code + ": " + name;
    }
}
