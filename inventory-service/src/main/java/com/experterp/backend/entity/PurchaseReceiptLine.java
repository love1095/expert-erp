package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "purchase_receipt_lines")
@Data
@NoArgsConstructor
public class PurchaseReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private PurchaseReceipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_line_id", nullable = false)
    private PurchaseOrderLine purchaseOrderLine;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    // The part above what was still outstanding (an over-delivery, accepted and flagged)
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal overQuantity = BigDecimal.ZERO;
}
