package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A FIFO cost layer (docs/business-rules.md section 2): stock that came in together at one unit cost, and how much of
 * it is left. Stock-outs take from the oldest layers first. Quantities still come from the movements.
 */
@Entity
@Table(name = "cost_layers")
@Getter
@Setter
@NoArgsConstructor
public class CostLayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    // The stock-in that opened it
    @Column(name = "movement_id", nullable = false)
    private Long movementId;

    // FIFO order; a transfer keeps the date of the layer it came from
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal remaining;

    // Cost of one unit now (after bill corrections), and when it came in
    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "original_unit_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal originalUnitCost;

    @Column(name = "purchase_order_line_id")
    private Long purchaseOrderLineId;

    // Receipts: units a supplier bill has priced so far
    @Column(name = "billed_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal billedQuantity = BigDecimal.ZERO;

    @Column(name = "source_layer_id")
    private Long sourceLayerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
