package com.experterp.backend.dto;

import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.ProductDetails;
import com.experterp.backend.entity.VariantOption;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductRequest {

    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;
    private String shortDescription;

    @NotBlank(message = "Unit is required")
    private String unit;

    private Long categoryId;
    // null = ACTIVE / STOCK / sellable
    private Product.Status status;
    private Product.Type type;
    private Boolean sellable;
    private String brand;
    private String barcode;

    @DecimalMin(value = "0", message = "Cost price cannot be negative")
    private BigDecimal costPrice;
    @DecimalMin(value = "0", message = "Selling price cannot be negative")
    private BigDecimal sellPrice;
    @DecimalMin(value = "0", message = "Trade price cannot be negative")
    private BigDecimal tradePrice;

    private BigDecimal minStock;
    @DecimalMin(value = "0", message = "Reorder quantity cannot be negative")
    private BigDecimal reorderQuantity;

    private Long defaultSupplierId;
    private String supplierProductCode;
    // Family: products sharing family_sku become one Shopify product; options e.g. [Size = XL]
    private String familySku;
    private String familyName;
    private List<VariantOption> variantOptions;

    // Cin7 reference fields (tax rules, size/weight, tiers 3-4, notes...); null = keep what is saved
    private ProductDetails details;
}
