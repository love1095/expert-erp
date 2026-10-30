-- Phase D4a: sales invoices made from orders, with VAT (docs/phase-d-sales.md).

-- ---------------------------------------------------------------------------
-- Invoices: customer, the shipment it was made for (auto-invoicing), totals split into net and VAT
-- (total_amount stays the amount to pay, VAT included)
-- ---------------------------------------------------------------------------
ALTER TABLE invoices ADD COLUMN customer_id   bigint REFERENCES customers (id);
ALTER TABLE invoices ADD COLUMN fulfilment_id bigint REFERENCES fulfilments (id);
ALTER TABLE invoices ADD COLUMN subtotal      numeric(18,2);
ALTER TABLE invoices ADD COLUMN tax_total     numeric(18,2) NOT NULL DEFAULT 0;
UPDATE invoices SET subtotal = total_amount;
ALTER TABLE invoices ALTER COLUMN subtotal SET NOT NULL;
CREATE INDEX idx_invoices_sale_order ON invoices (sale_order_id);

-- Items: which order line / charge they bill, and their VAT (total_price stays the net amount)
ALTER TABLE invoice_items ADD COLUMN sale_order_line_id   bigint REFERENCES sale_order_lines (id);
ALTER TABLE invoice_items ADD COLUMN sale_order_charge_id bigint REFERENCES sale_order_charges (id);
ALTER TABLE invoice_items ADD COLUMN discount_percent     numeric(5,2)  NOT NULL DEFAULT 0;
ALTER TABLE invoice_items ADD COLUMN tax_name             varchar(100);
ALTER TABLE invoice_items ADD COLUMN tax_rate             numeric(6,3)  NOT NULL DEFAULT 0;
ALTER TABLE invoice_items ADD COLUMN tax_amount           numeric(18,2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- Orders: how much of each line / charge is on a (not cancelled) invoice
-- ---------------------------------------------------------------------------
ALTER TABLE sale_order_lines ADD COLUMN invoiced_quantity numeric(18,4) NOT NULL DEFAULT 0
    CHECK (invoiced_quantity >= 0);
ALTER TABLE sale_order_charges ADD COLUMN invoiced boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- Organisation settings: invoice numbering (to carry on Cin7's series) and invoicing on shipment
-- ---------------------------------------------------------------------------
ALTER TABLE tenants ADD COLUMN invoice_prefix           varchar(10) NOT NULL DEFAULT 'INV-';
ALTER TABLE tenants ADD COLUMN next_invoice_number      bigint      NOT NULL DEFAULT 1;
-- Every shipment creates and issues an invoice for what it shipped (and is not invoiced yet)
ALTER TABLE tenants ADD COLUMN auto_invoice_on_shipment boolean     NOT NULL DEFAULT false;
UPDATE tenants t SET next_invoice_number = COALESCE((SELECT s.current_number + 1 FROM invoice_sequences s WHERE s.tenant_id = t.id), 1);
