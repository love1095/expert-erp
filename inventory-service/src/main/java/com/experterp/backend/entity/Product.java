package com.experterp.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** A stock item; columns follow the Cin7 Core inventory list so it can be imported and exported. */
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

    // HTML in Cin7 exports
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String shortDescription;

    @NotBlank
    @Column(nullable = false)
    private String unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // Deprecated products are kept for history, hidden by default and never sent to Shopify
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private Type type = Type.STOCK;

    @Column(nullable = false)
    private boolean sellable = true;

    @Column(length = 100)
    private String brand;

    @Column(length = 100)
    private String barcode;

    @Column(precision = 18, scale = 4)
    private BigDecimal costPrice;

    // Shop price (Cin7 PriceTier1), sent to Shopify; null = not set
    @Column(precision = 18, scale = 4)
    private BigDecimal sellPrice;

    // Cin7 PriceTier2
    @Column(precision = 18, scale = 4)
    private BigDecimal tradePrice;

    // Reorder when stock falls to this (Cin7 MinimumBeforeReorder)
    @Column(precision = 18, scale = 4)
    private BigDecimal minStock;

    @Column(precision = 18, scale = 4)
    private BigDecimal reorderQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_supplier_id")
    private Supplier defaultSupplier;

    @Column(length = 100)
    private String supplierProductCode;

    // Product family: the same item in several sizes/colours, one Shopify product with variants
    @Column(length = 100)
    private String familySku;

    private String familyName;

    // Up to 3, in order, e.g. [Colour = Navy, Size = XL]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<VariantOption> variantOptions;

    // Reference data from Cin7 that nothing searches on
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private ProductDetails details = new ProductDetails();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public enum Status { ACTIVE, DEPRECATED }

    public enum Type { STOCK, SERVICE }
}
