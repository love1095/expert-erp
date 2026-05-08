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

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
