package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A correction of a cost layer's value (a supplier bill at another price than the PO, or its cancellation). One row for
 * the part still in stock (no movement) and one per stock-out that had already taken from the layer (the COGS change).
 */
@Entity
@Table(name = "cost_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class CostAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "layer_id", nullable = false)
    private Long layerId;

    // The stock-out whose cost changes; empty for the part still in stock
    @Column(name = "movement_id")
    private Long movementId;

    @Column(name = "invoice_item_id")
    private Long invoiceItemId;

    // Units of the layer the bill priced (negative when a bill is cancelled); only on the row of the matched layer
    @Column(name = "billed_quantity", precision = 18, scale = 4)
    private BigDecimal billedQuantity;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
