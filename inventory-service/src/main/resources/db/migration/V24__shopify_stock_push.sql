-- Phase 7.6 (F): Shopify shows each product's AVAILABLE stock over all warehouses (on hand - reserved, never below 0;
-- business-rules.md §1 and §5). What was last sent is kept per product, so only changes are sent.
ALTER TABLE shopify_product_links ADD COLUMN stock_sent    int;
ALTER TABLE shopify_product_links ADD COLUMN stock_sent_at timestamp(6);

-- The Shopify location that holds the stock, and how the last stock sync went
ALTER TABLE shopify_connections ADD COLUMN location_id       varchar(100);
ALTER TABLE shopify_connections ADD COLUMN stock_synced_at   timestamp(6);
ALTER TABLE shopify_connections ADD COLUMN stock_sync_result text;
ALTER TABLE shopify_connections ADD COLUMN stock_sync_error  text;
