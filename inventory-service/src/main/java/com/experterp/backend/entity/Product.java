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
@Table(name = "products",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"tenant_id", "sku"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @NotBlank
    @Column(nullable = false)
    private String sku;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @NotBlank
    @Column(nullable = false)
    private String unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(precision = 18, scale = 4)
    private BigDecimal costPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal minStock;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
