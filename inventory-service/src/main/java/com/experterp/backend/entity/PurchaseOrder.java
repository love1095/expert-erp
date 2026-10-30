package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_orders")
@Data
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    // e.g. PO-19074
    @Column(nullable = false, length = 30)
    private String orderNumber;

    // The supplier record (older orders typed by name may have none); the name is kept as it was ordered
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(nullable = false)
    private String supplierName;

    // The supplier's own reference for the order (e.g. their order confirmation number)
    @Column(length = 100)
    private String supplierReference;

    @Column(length = 100)
    private String paymentTerm;

    private LocalDate requiredBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    // Freight, duty and other costs on the order: an expense, not stock cost
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PurchaseOrderCost> costs = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Placed with the supplier (Cin7's Authorise)
    private LocalDateTime orderedAt;

    @Column
    private LocalDateTime receivedAt;

    // DRAFT (editable) → ORDERED (placed with the supplier) → RECEIVED; or CANCELLED
    public enum Status { DRAFT, ORDERED, RECEIVED, CANCELLED }
}
