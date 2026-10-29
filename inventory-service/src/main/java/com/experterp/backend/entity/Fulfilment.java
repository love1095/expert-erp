package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One shipment against a sale order. Each line creates one SALE_OUT movement. */
@Entity
@Table(name = "fulfilments")
@Data
@NoArgsConstructor
public class Fulfilment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_order_id", nullable = false)
    private SaleOrder saleOrder;

    // Unique per tenant: a retried request with the same key is applied only once
    @Column
    private String idempotencyKey;

    @Column
    private String notes;

    @Column
    private String createdBy;

    @OneToMany(mappedBy = "fulfilment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FulfilmentLine> lines = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
