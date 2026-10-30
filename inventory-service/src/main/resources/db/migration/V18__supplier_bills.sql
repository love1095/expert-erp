-- Phase E3: supplier bills and three-way match (docs/phase-e-purchasing.md). As in Cin7 (PO-19064's bill 006387333),
-- a bill is made from the purchase order: the supplier's own invoice number, a due date from the supplier's terms,
-- the lines received and not billed yet, and the supplier's extra charges (e.g. shipping) — an expense (decision 4).
-- Ordered vs received vs billed quantity and the PO price vs the bill price are compared on every bill line.

-- Bills are invoices of type PURCHASE; invoice_number holds the supplier's number, so it is unique per supplier only
ALTER TABLE invoices ADD COLUMN supplier_id bigint REFERENCES suppliers (id);
CREATE INDEX idx_invoices_purchase_order ON invoices (purchase_order_id);

-- The PO line a bill line pays for
ALTER TABLE invoice_items ADD COLUMN purchase_order_line_id bigint REFERENCES purchase_order_lines (id);

-- Per PO line: billed so far (bills not cancelled)
ALTER TABLE purchase_order_lines ADD COLUMN billed_quantity numeric(18,4) NOT NULL DEFAULT 0 CHECK (billed_quantity >= 0);
