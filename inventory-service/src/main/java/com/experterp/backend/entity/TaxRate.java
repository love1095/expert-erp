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

    public enum Usage { SALE, PURCHASE }

    public TaxRate(String name, String rate, Usage usage) {
        this.name = name;
        this.rate = new BigDecimal(rate);
        this.usage = usage;
    }
}
