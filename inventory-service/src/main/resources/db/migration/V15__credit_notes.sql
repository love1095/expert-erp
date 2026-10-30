-- Phase D4b: credit notes & restock, refunds (docs/phase-d-sales.md). As in Cin7 (CR-00231): a credit note is
-- for an invoice, credits some of its quantities and/or extra charges (e.g. a shipping refund), may restock the
-- goods, and is settled by a refund, by paying off the invoice, or kept as customer credit.

ALTER TABLE invoices ADD COLUMN kind varchar(15) NOT NULL DEFAULT 'INVOICE' CHECK (kind IN ('INVOICE', 'CREDIT_NOTE'));
-- Credit notes: the invoice they credit
ALTER TABLE invoices ADD COLUMN credited_invoice_id bigint REFERENCES invoices (id);

-- Invoice items: how much has been credited; credit note items: the invoice item they credit
ALTER TABLE invoice_items ADD COLUMN credited_quantity numeric(18,4) NOT NULL DEFAULT 0 CHECK (credited_quantity >= 0);
ALTER TABLE invoice_items ADD COLUMN credited_item_id  bigint REFERENCES invoice_items (id);

-- Payments: CREDIT_NOTE = an invoice paid with a credit note; a payment on a credit note = a refund
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_method_check;
ALTER TABLE payments ADD CONSTRAINT payments_method_check CHECK (method IN ('CASH', 'BANK', 'STRIPE', 'CREDIT_NOTE'));
ALTER TABLE payments ADD COLUMN credit_note_id bigint REFERENCES invoices (id);
ALTER TABLE payments ADD COLUMN reference      varchar(100);
CREATE INDEX idx_payments_credit_note ON payments (credit_note_id);

-- Credit note numbers: own series, as in Cin7 (CR-00231)
ALTER TABLE tenants ADD COLUMN credit_note_prefix      varchar(10) NOT NULL DEFAULT 'CR-';
ALTER TABLE tenants ADD COLUMN next_credit_note_number bigint      NOT NULL DEFAULT 1;
