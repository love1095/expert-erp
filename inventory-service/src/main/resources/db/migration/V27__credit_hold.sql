-- Phase G2 (spec §5): credit control. A customer is on credit hold when put on hold by hand, when what they owe
-- plus their confirmed orders not yet invoiced is over the credit limit, or when an invoice is past its due date.
-- Orders warn at entry; a held customer's order is confirmed only by a manager, with a reason.
ALTER TABLE customers ADD COLUMN on_hold     boolean      NOT NULL DEFAULT false;
ALTER TABLE customers ADD COLUMN hold_reason varchar(255);
ALTER TABLE customers ADD COLUMN held_by     varchar(100);
ALTER TABLE customers ADD COLUMN held_at     timestamp(6);

-- A confirmation past a credit hold, and why
ALTER TABLE sale_orders ADD COLUMN credit_override varchar(255);

CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices (customer_id) WHERE customer_id IS NOT NULL;
