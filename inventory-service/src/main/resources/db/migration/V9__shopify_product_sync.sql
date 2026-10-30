-- Products are sent from the ERP to Shopify (title, description, SKU, price, cost, stock).

-- Selling price (Shopify's variant price); cost_price stays the purchase cost
ALTER TABLE products ADD COLUMN sell_price numeric(18,4);

-- Which Shopify product/variant an ERP product was created as or matched to (by SKU)
CREATE TABLE shopify_product_links (
    id                         bigserial PRIMARY KEY,
    tenant_id                  bigint       NOT NULL,
    product_id                 bigint       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    shopify_product_id         varchar(100) NOT NULL,
    shopify_variant_id         varchar(100) NOT NULL,
    shopify_inventory_item_id  varchar(100) NOT NULL,
    -- The Shopify product has only this variant: the ERP may also set its title and description
    single_variant             boolean      NOT NULL DEFAULT true,
    -- Hash of the product details last sent, so unchanged products are not sent again
    content_hash               varchar(64),
    synced_at                  timestamp(6),
    CONSTRAINT uk_shopify_product_links_product UNIQUE (tenant_id, product_id)
);

ALTER TABLE shopify_connections ADD COLUMN last_product_sync_at timestamp(6);
