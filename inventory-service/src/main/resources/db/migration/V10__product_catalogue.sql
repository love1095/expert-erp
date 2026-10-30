-- Products follow the Cin7 Core inventory list (InventoryList_YYYY-MM-DD.csv), so it can be imported and exported.
--
-- Design: fields the ERP searches, filters, groups or syncs on are real columns (indexed where queried);
-- the rest of Cin7's reference data (tax rules, size/weight, extra price tiers, notes...) is one JSONB
-- column, `details`, so adding such a field later needs no migration.

-- Cin7 descriptions are HTML, up to several KB
ALTER TABLE products ALTER COLUMN description TYPE text;
ALTER TABLE products ADD COLUMN short_description text;

-- ACTIVE or DEPRECATED (kept for history, hidden by default, never sent to Shopify)
ALTER TABLE products ADD COLUMN status varchar(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE products ADD CONSTRAINT products_status_check CHECK (status IN ('ACTIVE', 'DEPRECATED'));
-- STOCK or SERVICE (a service has no stock and is not sent to Shopify)
ALTER TABLE products ADD COLUMN product_type varchar(20) NOT NULL DEFAULT 'STOCK';
ALTER TABLE products ADD CONSTRAINT products_product_type_check CHECK (product_type IN ('STOCK', 'SERVICE'));
ALTER TABLE products ADD COLUMN sellable boolean NOT NULL DEFAULT true;

ALTER TABLE products ADD COLUMN brand   varchar(100);
ALTER TABLE products ADD COLUMN barcode varchar(100);

-- sell_price (V9) = Cin7 PriceTier1, the shop price; trade_price = PriceTier2
ALTER TABLE products ADD COLUMN trade_price numeric(18,4);

-- Purchasing: min_stock (V1) = Cin7 MinimumBeforeReorder; this is how many to order then
ALTER TABLE products ADD COLUMN reorder_quantity      numeric(18,4);
ALTER TABLE products ADD COLUMN default_supplier_id   bigint REFERENCES suppliers (id) ON DELETE SET NULL;
ALTER TABLE products ADD COLUMN supplier_product_code varchar(100);

-- Product family: the same item in several sizes/colours = one Shopify product with variants.
-- variant_options: up to 3 of {"name": "Size", "value": "XL"}, in Cin7's option order
ALTER TABLE products ADD COLUMN family_sku      varchar(100);
ALTER TABLE products ADD COLUMN family_name     varchar(255);
ALTER TABLE products ADD COLUMN variant_options jsonb;

-- Everything else from Cin7, e.g. {"tags": "...", "priceTier3": 40, "weight": 35, "weightUnit": "g",
-- "saleTaxRule": "20% (VAT on Income)", "supplierPrice": 9.32, "internalNote": "..."}
ALTER TABLE products ADD COLUMN details jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE products ADD COLUMN updated_at timestamp(6);

-- ---- indexes ----

-- Product list: per tenant, by status, newest first (the default view)
CREATE INDEX idx_products_list ON products (tenant_id, status, created_at DESC, id);
CREATE INDEX idx_products_category ON products (tenant_id, category_id);
CREATE INDEX idx_products_supplier ON products (default_supplier_id) WHERE default_supplier_id IS NOT NULL;
-- Shopify sync groups a family's products
CREATE INDEX idx_products_family ON products (tenant_id, family_sku) WHERE family_sku IS NOT NULL;
-- Search box: "contains" on SKU and name stays fast with ~15,000+ products
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_products_sku_trgm  ON products USING gin (lower(sku) gin_trgm_ops);
CREATE INDEX idx_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);

-- Stock per product and warehouse (product list, Shopify stock) is summed from movements
CREATE INDEX idx_sm_product_warehouse ON stock_movements (product_id, warehouse_id);

-- Shopify: whether the ERP created the Shopify product (so may rebuild or delete it) or matched one made by hand.
-- NULL = linked before this was recorded: treated as ERP-made while the Shopify product is still a draft.
ALTER TABLE shopify_product_links ADD COLUMN created_by_erp boolean;
CREATE INDEX idx_shopify_product_links_product ON shopify_product_links (shopify_product_id);
