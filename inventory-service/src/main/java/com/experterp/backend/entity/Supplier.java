package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "suppliers",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"tenant_id", "name"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = "Active";

    private String currency;
    private String paymentTerm;
    private String taxNumber;

    @Column(precision = 10, scale = 2)
    private BigDecimal discount;

    private String website;
    private String contactName;
    private String phone;
    private String email;

    @Column(columnDefinition = "TEXT")
    private String comments;

    // Purchase tax rule for this supplier's orders when the product has none, e.g. "20% (VAT on Expenses)"
    @Column(length = 100)
    private String taxRule;

    // One postal address for now (Cin7 keeps a list); copied onto new purchase orders as the vendor address
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "line1", column = @Column(name = "address_line1")),
            @AttributeOverride(name = "line2", column = @Column(name = "address_line2")),
            @AttributeOverride(name = "city", column = @Column(name = "address_city")),
            @AttributeOverride(name = "state", column = @Column(name = "address_state")),
            @AttributeOverride(name = "postcode", column = @Column(name = "address_postcode")),
            @AttributeOverride(name = "country", column = @Column(name = "address_country"))})
    private Address address;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
