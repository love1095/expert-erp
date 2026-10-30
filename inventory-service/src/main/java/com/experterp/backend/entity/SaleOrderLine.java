package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_order_lines")
@Data
@NoArgsConstructor
public class SaleOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_order_id", nullable = false)
    private SaleOrder saleOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Positive
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal unitPrice;

    // The source system's id for this line (Shopify line item id), for sending shipments back
    @Column(length = 100)
    private String externalLineId;

    // Invariant (DB check constraint): fulfilled + cancelled <= quantity, returned <= fulfilled
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal fulfilledQuantity = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    // Percent off the unit price
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    // VAT copied onto the line when the order is made, so later rate changes never alter it.
    // Empty name = no VAT recorded (orders made before VAT existed, Shopify orders until phase F)
    @Column(length = 100)
    private String taxName;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal taxRate = BigDecimal.ZERO;

    // Chosen by hand instead of the product / customer / default rule
    @Column(nullable = false)
    private boolean taxOverridden;

    /** Quantity still to be shipped. */
    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(fulfilledQuantity).subtract(cancelledQuantity);
    }

    /** Quantity shipped and not yet returned. */
    public BigDecimal getReturnableQuantity() {
        return fulfilledQuantity.subtract(returnedQuantity);
    }
}
