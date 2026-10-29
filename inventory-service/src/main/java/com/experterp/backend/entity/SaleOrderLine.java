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

    // Invariant (DB check constraint): fulfilled + cancelled <= quantity, returned <= fulfilled
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal fulfilledQuantity = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    /** Quantity still to be shipped. */
    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(fulfilledQuantity).subtract(cancelledQuantity);
    }

    /** Quantity shipped and not yet returned. */
    public BigDecimal getReturnableQuantity() {
        return fulfilledQuantity.subtract(returnedQuantity);
    }
}
