-- Phase 7.3: shipments of Shopify orders are sent back to Shopify.

-- Shopify's id for the order line, so a shipment can say which Shopify lines were shipped
ALTER TABLE sale_order_lines ADD COLUMN external_line_id varchar(100);

-- Optional tracking details, passed on to Shopify (and its shipping email)
ALTER TABLE fulfilments ADD COLUMN tracking_company varchar(100);
ALTER TABLE fulfilments ADD COLUMN tracking_number  varchar(255);

-- Sync of this shipment to the order's source system; NULL for orders that did not come from one
ALTER TABLE fulfilments ADD COLUMN external_sync_status  varchar(20);
ALTER TABLE fulfilments ADD COLUMN external_id           varchar(100);
-- Error when FAILED; a note when SYNCED with something worth knowing (e.g. already fulfilled in Shopify)
ALTER TABLE fulfilments ADD COLUMN external_sync_message text;
ALTER TABLE fulfilments ADD COLUMN external_synced_at    timestamp(6);
ALTER TABLE fulfilments ADD CONSTRAINT fulfilments_external_sync_status_check
    CHECK (external_sync_status IN ('PENDING', 'SYNCED', 'FAILED'));
