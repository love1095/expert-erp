-- Phase 7.2: Shopify orders become sale orders.

-- Where an order came from. (tenant, source, external_id) identifies an imported order, so a repeated
-- webhook or import updates it instead of creating a duplicate.
ALTER TABLE sale_orders ADD COLUMN source             varchar(20)  NOT NULL DEFAULT 'MANUAL';
ALTER TABLE sale_orders ADD COLUMN external_id        varchar(100);
-- The number people see in Shopify, e.g. #1001
ALTER TABLE sale_orders ADD COLUMN external_reference varchar(100);
-- Imported, but something needs a person (unknown SKU, other currency, ...); the order stays DRAFT
ALTER TABLE sale_orders ADD COLUMN needs_review       boolean      NOT NULL DEFAULT false;
ALTER TABLE sale_orders ADD COLUMN review_reason      text;
ALTER TABLE sale_orders ADD CONSTRAINT sale_orders_source_check CHECK (source IN ('MANUAL', 'SHOPIFY'));
ALTER TABLE sale_orders ADD CONSTRAINT uk_sale_orders_tenant_source_external UNIQUE (tenant_id, source, external_id);

-- Order import settings on the connection
ALTER TABLE shopify_connections ADD COLUMN default_warehouse_id   bigint REFERENCES warehouses (id);
-- Public HTTPS address of this backend that Shopify sends webhooks to (a tunnel while developing)
ALTER TABLE shopify_connections ADD COLUMN public_url             varchar(500);
ALTER TABLE shopify_connections ADD COLUMN webhooks_registered_at timestamp(6);
ALTER TABLE shopify_connections ADD COLUMN webhook_topics         text;
ALTER TABLE shopify_connections ADD COLUMN last_import_at         timestamp(6);

-- Every webhook received, stored before processing: a record of what Shopify sent, duplicate detection
-- (Shopify may deliver the same event more than once) and a basis for retries.
CREATE TABLE shopify_webhook_events (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint       NOT NULL,
    event_id      varchar(255) NOT NULL,
    topic         varchar(100) NOT NULL,
    shop_domain   varchar(255) NOT NULL,
    payload       text         NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'RECEIVED',
    error         text,
    received_at   timestamp(6) NOT NULL DEFAULT now(),
    processed_at  timestamp(6),
    CONSTRAINT uk_shopify_webhook_events_event UNIQUE (tenant_id, event_id),
    CONSTRAINT shopify_webhook_events_status_check CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED'))
);
CREATE INDEX idx_shopify_webhook_events_tenant_received ON shopify_webhook_events (tenant_id, received_at DESC);
