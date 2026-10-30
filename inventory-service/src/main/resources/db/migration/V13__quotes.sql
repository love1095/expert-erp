-- Phase D3: quotes (docs/phase-d-sales.md). A quote is the first stage of the same sale document (same number,
-- as in Cin7): it reserves nothing, is not a sale, and becomes the order when converted.

ALTER TABLE sale_orders DROP CONSTRAINT IF EXISTS sale_orders_status_check;
ALTER TABLE sale_orders ADD CONSTRAINT sale_orders_status_check
    CHECK (status IN ('QUOTE', 'REJECTED', 'DRAFT', 'CONFIRMED', 'COMPLETED', 'CANCELLED'));

-- When the sale became an order (created as one, or converted from a quote); empty for quotes.
-- Sales figures are dated by it, so a quote counts from the day it is accepted.
ALTER TABLE sale_orders ADD COLUMN ordered_at timestamp(6);
UPDATE sale_orders SET ordered_at = created_at;
CREATE INDEX idx_sale_orders_tenant_ordered ON sale_orders (tenant_id, ordered_at);

-- Quotes only: the last day the quote can be turned into an order
ALTER TABLE sale_orders ADD COLUMN quote_valid_until date;

-- Default validity of a new quote, in days
ALTER TABLE tenants ADD COLUMN quote_valid_days integer NOT NULL DEFAULT 30 CHECK (quote_valid_days BETWEEN 1 AND 365);
