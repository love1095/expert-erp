package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "purchase_order_lines")
@Data
@NoArgsConstructor
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Positive
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal unitCost;

    // The supplier's code for the product, e.g. 3200.BK.L
    @Column(length = 100)
    private String supplierSku;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    // Purchase VAT copied onto the line (empty on older orders: no VAT recorded)
    @Column(length = 100)
    private String taxName;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean taxOverridden;

    // Received so far (may be more than ordered: an over-delivery)
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    // Closed short: not coming any more
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal closedShortQuantity = BigDecimal.ZERO;

    /** Still to come: ordered − received − closed short, never below 0. */
    public BigDecimal getOutstandingQuantity() {
        return quantity.subtract(receivedQuantity).subtract(closedShortQuantity).max(BigDecimal.ZERO);
    }

    /** Received above what was ordered. */
    public BigDecimal getOverQuantity() {
        return receivedQuantity.subtract(quantity).max(BigDecimal.ZERO);
    }
}
