package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_movements", indexes = {
    @Index(name = "idx_sm_product",   columnList = "product_id"),
    @Index(name = "idx_sm_warehouse", columnList = "warehouse_id"),
    @Index(name = "idx_sm_type",      columnList = "type"),
    @Index(name = "idx_sm_created",   columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type")
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    @Column
    private String notes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum MovementType {
        PURCHASE_IN,
        SALE_OUT,
        ADJUSTMENT_IN,
        ADJUSTMENT_OUT,
        TRANSFER_IN,
        TRANSFER_OUT;

        public boolean isIn() {
            return name().endsWith("_IN");
        }
    }

    public enum ReferenceType {
        PURCHASE_ORDER,
        SALE_ORDER,
        TRANSFER,
        ADJUSTMENT,
        MANUAL
    }
}
