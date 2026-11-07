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

    // Phase P3: the unit the line is in (empty: the product's own unit) and the base units one of it holds;
    // quantity and price are in this unit, stock moves quantity × factor
    @Column(name = "unit_name", length = 50)
    private String unitName;

    @Column(name = "unit_factor", nullable = false, precision = 18, scale = 4)
    private BigDecimal unitFactor = BigDecimal.ONE;

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

    // Where the price came from (phase G1): "Contract price", "Contract tier", "Retail", "Typed"
    @Column(name = "price_source", length = 100)
    private String priceSource;

    // On invoices that are not cancelled
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal invoicedQuantity = BigDecimal.ZERO;

    /** Quantity sold (not cancelled) and not on an invoice yet. */
    public BigDecimal getToInvoiceQuantity() {
        return quantity.subtract(cancelledQuantity).subtract(invoicedQuantity).max(BigDecimal.ZERO);
    }

    /** Quantity still to be shipped. */
    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(fulfilledQuantity).subtract(cancelledQuantity);
    }

    /** Quantity shipped and not yet returned. */
    public BigDecimal getReturnableQuantity() {
        return fulfilledQuantity.subtract(returnedQuantity);
    }
}
