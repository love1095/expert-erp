package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.TenantId;

/** One address in a customer's address book, like Cin7's Addresses tab (phase G4). */
@Entity
@Table(name = "customer_addresses")
@Data
@NoArgsConstructor
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Customer customer;

    @Embedded
    private Address address = new Address();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type = Type.BILLING;

    // One default per type: the address an order starts with
    @Column(name = "default_for_type", nullable = false)
    private boolean defaultForType;

    // Cin7's address types
    public enum Type { BILLING, BUSINESS, SHIPPING }
}
