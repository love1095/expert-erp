package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.TenantId;

/** A person at a customer. Exactly one contact per customer should be the default. */
@Entity
@Table(name = "customer_contacts")
@Data
@NoArgsConstructor
public class CustomerContact {

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

    private String name;
    private String jobTitle;
    private String phone;
    private String mobilePhone;
    private String fax;
    private String email;
    private String website;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "is_default", nullable = false)
    private boolean defaultContact;

    @Column(nullable = false)
    private boolean includeInEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketingConsent marketingConsent = MarketingConsent.UNKNOWN;

    public enum MarketingConsent { UNKNOWN, OPT_IN, OPT_OUT }
}
