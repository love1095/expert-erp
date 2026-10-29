package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sale_orders")
@Data
@NoArgsConstructor
public class SaleOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private String customerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfilmentStatus fulfilmentStatus = FulfilmentStatus.UNFULFILLED;

    @Column
    private String notes;

    @OneToMany(mappedBy = "saleOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderLine> lines = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime confirmedAt;

    // Set when every line is fully fulfilled
    @Column
    private LocalDateTime fulfilledAt;

    @Column
    private LocalDateTime cancelledAt;

    /**
     * Order lifecycle. Fulfilment progress is tracked separately in {@link FulfilmentStatus}.
     * DRAFT → CONFIRMED → COMPLETED (nothing left to ship); DRAFT/CONFIRMED → CANCELLED (nothing shipped).
     */
    public enum Status { DRAFT, CONFIRMED, COMPLETED, CANCELLED }

    public enum FulfilmentStatus { UNFULFILLED, PARTIALLY_FULFILLED, FULFILLED }

    public boolean hasFulfilledAnything() {
        return lines.stream().anyMatch(l -> l.getFulfilledQuantity().signum() > 0);
    }

    public boolean hasRemaining() {
        return lines.stream().anyMatch(l -> l.getRemainingQuantity().signum() > 0);
    }

    public void refreshFulfilmentStatus() {
        boolean allFulfilled = lines.stream()
                .allMatch(l -> l.getFulfilledQuantity().compareTo(l.getQuantity()) == 0);
        if (allFulfilled) {
            fulfilmentStatus = FulfilmentStatus.FULFILLED;
        } else if (hasFulfilledAnything()) {
            fulfilmentStatus = FulfilmentStatus.PARTIALLY_FULFILLED;
        } else {
            fulfilmentStatus = FulfilmentStatus.UNFULFILLED;
        }
    }
}
