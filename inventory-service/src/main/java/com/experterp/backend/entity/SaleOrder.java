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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    // The customer's name when the order was placed; kept even if the customer is renamed later
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

    // Where the order came from; imported orders keep the other system's id and order number
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(length = 100)
    private String externalId;

    // e.g. Shopify's #1001
    @Column(length = 100)
    private String externalReference;

    // Imported but something needs a person (unknown SKU, other currency, ...); stays DRAFT until fixed
    @Column(nullable = false)
    private boolean needsReview;

    @Column(columnDefinition = "TEXT")
    private String reviewReason;

    @OneToMany(mappedBy = "saleOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderLine> lines = new ArrayList<>();

    // Carriage, delivery... (no product, no stock)
    @OneToMany(mappedBy = "saleOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderCharge> charges = new ArrayList<>();

    // e.g. SO-66006, from the organisation's sequence
    @Column(nullable = false, length = 30)
    private String orderNumber;

    // The customer's own reference (their PO number)
    @Column(length = 100)
    private String customerReference;

    @Column(length = 100)
    private String salesRep;

    @Column(length = 100)
    private String paymentTerm;

    @Column
    private LocalDate shipBy;

    @Column(length = 100)
    private String carrier;

    @Column(columnDefinition = "TEXT")
    private String shippingNotes;

    // When the sale became an order (created as one, or converted from a quote); null for quotes.
    // Sales figures are dated by it.
    @Column
    private LocalDateTime orderedAt;

    // Quotes only: the last day the quote can be turned into an order
    @Column
    private LocalDate quoteValidUntil;

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
     * QUOTE → DRAFT (converted) or REJECTED; DRAFT → CONFIRMED → COMPLETED (nothing left to ship);
     * DRAFT/CONFIRMED → CANCELLED (nothing shipped).
     */
    public enum Status { QUOTE, REJECTED, DRAFT, CONFIRMED, COMPLETED, CANCELLED }

    public enum Source { MANUAL, SHOPIFY }

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
