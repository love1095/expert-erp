package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_items")
@Data
@NoArgsConstructor
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column
    private String description;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal totalPrice;

    // What it bills on a sale order (a product line or an additional charge)
    @Column
    private Long saleOrderLineId;

    @Column
    private Long saleOrderChargeId;

    // Bills: the purchase order line this pays for
    @Column
    private Long purchaseOrderLineId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    // VAT copied from the order line; totalPrice is the net amount
    @Column(length = 100)
    private String taxName;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    // Invoice items: quantity already on credit notes
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal creditedQuantity = BigDecimal.ZERO;

    // Credit note items: the invoice item they credit
    @Column
    private Long creditedItemId;
}
