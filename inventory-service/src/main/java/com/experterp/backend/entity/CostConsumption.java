package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** What a stock-out took from a cost layer, at the layer's unit cost of that moment (the COGS of a shipment). */
@Entity
@Table(name = "cost_consumptions")
@Getter
@Setter
@NoArgsConstructor
public class CostConsumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "layer_id", nullable = false)
    private Long layerId;

    @Column(name = "movement_id", nullable = false)
    private Long movementId;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal unitCost;

    // Transfers: the layer this quantity opened in the other warehouse
    @Column(name = "child_layer_id")
    private Long childLayerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
