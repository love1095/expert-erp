package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** An additional charge on a sale order (carriage, delivery...): no product, no stock, its own VAT. */
@Entity
@Table(name = "sale_order_charges")
@Data
@NoArgsConstructor
public class SaleOrderCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_order_id", nullable = false)
    private SaleOrder saleOrder;

    @Column(nullable = false)
    private String description;

    @Positive
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(length = 100)
    private String taxName;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean taxOverridden;
}
