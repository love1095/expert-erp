package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Fields follow the Cin7 Core customer export, so a Cin7 CSV can be imported as-is. */
@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(length = 3)
    private String currency;
    private String paymentTerm;
    private String taxRule;
    private String taxNumber;
    private String priceTier;

    @Column(precision = 5, scale = 2)
    private BigDecimal discount;

    @Column(precision = 18, scale = 2)
    private BigDecimal creditLimit;

    // Accounting codes, e.g. 610 / 200
    private String accountReceivable;
    private String saleAccount;

    private String carrier;
    private String salesRepresentative;
    // Default warehouse/location name
    private String location;

    // Comma-separated, as in Cin7
    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("defaultContact DESC, id")
    @BatchSize(size = 100)
    private List<CustomerContact> contacts = new ArrayList<>();

    // Where the customer came from; integrations look customers up by (source, externalId)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(length = 100)
    private String externalId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum Source { MANUAL, SHOPIFY }

    // Cin7 calls an inactive customer "Deprecated"
    public enum Status { ACTIVE, DEPRECATED }

    public void replaceContacts(List<CustomerContact> newContacts) {
        contacts.clear();
        newContacts.forEach(c -> c.setCustomer(this));
        contacts.addAll(newContacts);
    }
}
