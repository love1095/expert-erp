package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/** The Shopify product/variant an ERP product was created as, or matched to by SKU. */
@Entity
@Table(name = "shopify_product_links")
@Data
@NoArgsConstructor
public class ShopifyProductLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    // Full Shopify GIDs, e.g. gid://shopify/ProductVariant/123
    @Column(nullable = false, length = 100)
    private String shopifyProductId;

    @Column(nullable = false, length = 100)
    private String shopifyVariantId;

    @Column(nullable = false, length = 100)
    private String shopifyInventoryItemId;

    // Only then does the ERP set the product's title and description (not on a product with sizes/colours)
    @Column(nullable = false)
    private boolean singleVariant = true;

    // The ERP created the Shopify product (may rebuild or delete it); false = matched one made by hand.
    // null = linked before this was recorded: treated as ERP-made while the Shopify product is a draft
    private Boolean createdByErp;

    // Of the details last sent; unchanged products are skipped
    @Column(length = 64)
    private String contentHash;

    private LocalDateTime syncedAt;
}
