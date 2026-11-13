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

    // ---- like Cin7's New purchase: supplier, accounting and shipping details ----
    @Column(length = 255)
    private String contactName;

    @Column(length = 100)
    private String contactPhone;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line1", column = @Column(name = "vendor_line1")),
            @AttributeOverride(name = "line2", column = @Column(name = "vendor_line2")),
            @AttributeOverride(name = "city", column = @Column(name = "vendor_city")),
            @AttributeOverride(name = "state", column = @Column(name = "vendor_state")),
            @AttributeOverride(name = "postcode", column = @Column(name = "vendor_postcode")),
            @AttributeOverride(name = "country", column = @Column(name = "vendor_country"))})
    private Address vendorAddress;

    // The day the order is dated (Cin7's Date); created_at is when it was typed in
    private LocalDate orderDate;

    // The order's tax rule: each line starts from it unless the product has its own (empty = the supplier's)
    @Column(length = 100)
    private String taxRule;

    // Receiving does not show what was ordered: the warehouse counts what arrived
    @Column(nullable = false)
    private boolean blindReceipt;

    // Cin7's Approach. STOCK_FIRST: a bill covers only what came in. INVOICE_FIRST: only what is billed can be received.
    public enum Approach { STOCK_FIRST, INVOICE_FIRST }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Approach approach = Approach.STOCK_FIRST;

    @Column(length = 255)
    private String shipToCompany;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line1", column = @Column(name = "shipping_line1")),
            @AttributeOverride(name = "line2", column = @Column(name = "shipping_line2")),
            @AttributeOverride(name = "city", column = @Column(name = "shipping_city")),
            @AttributeOverride(name = "state", column = @Column(name = "shipping_state")),
            @AttributeOverride(name = "postcode", column = @Column(name = "shipping_postcode")),
            @AttributeOverride(name = "country", column = @Column(name = "shipping_country"))})
    private Address shippingAddress;

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
