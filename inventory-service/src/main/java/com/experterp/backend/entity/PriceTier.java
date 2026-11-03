package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * A price tier (phase G1), numbered like Cin7's PriceTier1..10 and named by the organisation (Retail, Contract,
 * Key account...). Without a discount it uses the product's own price for that tier (tiers 1-4); with one it is
 * that percentage off tier 1.
 */
@Entity
@Table(name = "price_tiers")
@Getter
@Setter
@NoArgsConstructor
public class PriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private short number;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent;
}
