package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/** A VAT rate of the organisation, named as in Cin7 / Xero UK (e.g. "20% (VAT on Income)"). */
@Entity
@Table(name = "tax_rates")
@Data
@NoArgsConstructor
public class TaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    // Percent, e.g. 20.000
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Usage usage;

    @Column(nullable = false)
    private boolean active = true;

    // Phase H3: which VAT return boxes the rate goes to, like Xero's tax types
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_type", nullable = false, length = 20)
    private VatType vatType = VatType.STANDARD;

    // The rate the VAT return counts: the same as `rate`, except reverse charge / EC acquisitions, which charge
    // nothing on the line but report a notional 20 % (box 1 or 2, and box 4)
    @Column(name = "report_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal reportRate = BigDecimal.ZERO;

    public enum Usage { SALE, PURCHASE }

    public enum VatType {
        STANDARD, REDUCED, ZERO, EXEMPT,
        // Outside the scope of VAT: not in the return at all
        NO_VAT,
        REVERSE_CHARGE, EC_ACQUISITION,
        // The whole line is VAT (import VAT from a courier or a C79): reclaimed, no net amount
        FULL_VAT
    }

    public TaxRate(String name, String rate, Usage usage) {
        this(name, rate, usage, null, null);
    }

    public TaxRate(String name, String rate, Usage usage, VatType type, String reportRate) {
        this.name = name;
        this.rate = new BigDecimal(rate);
        this.usage = usage;
        BigDecimal r = this.rate;
        this.vatType = type != null ? type : r.signum() == 0 ? VatType.ZERO : r.compareTo(BigDecimal.TEN) < 0 ? VatType.REDUCED : VatType.STANDARD;
        this.reportRate = reportRate != null ? new BigDecimal(reportRate) : r;
    }
}
