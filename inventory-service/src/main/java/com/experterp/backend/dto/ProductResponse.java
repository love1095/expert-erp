package com.experterp.backend.dto;

import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.ProductDetails;
import com.experterp.backend.entity.VariantOption;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductResponse {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private String shortDescription;
    private String unit;
    private Long categoryId;
    private String categoryName;
    private Product.Status status;
    private Product.Type type;
    private boolean sellable;
    private String brand;
    private String barcode;
    private BigDecimal costPrice;
    private BigDecimal sellPrice;
    private BigDecimal tradePrice;
    private BigDecimal minStock;
    private BigDecimal reorderQuantity;
    private Long defaultSupplierId;
    private String defaultSupplierName;
    private String supplierProductCode;
    private String familySku;
    private String familyName;
    private List<VariantOption> variantOptions;
    private ProductDetails details;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal totalStock;
    // Reserved for sale orders (all warehouses) and what is left to promise: total − reserved, can be < 0
    private BigDecimal reservedStock = BigDecimal.ZERO;
    private BigDecimal availableStock;
    private boolean lowStock;
}
