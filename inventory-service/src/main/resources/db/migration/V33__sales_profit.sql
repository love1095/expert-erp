-- Phase H4: sales profit and inventory movement reports.

-- A credit note that took goods back into stock keeps its return, so "All COGS" can give the credit note the cost
-- of the goods it brought back (Cin7: a credit note that unstocks items reverses their COGS)
ALTER TABLE invoices ADD COLUMN sale_return_id bigint REFERENCES sale_returns (id);

-- Credit notes made before: their return's reason starts with "Credit note <number>"
UPDATE invoices i SET sale_return_id = r.id
FROM sale_returns r
WHERE i.kind = 'CREDIT_NOTE' AND i.type = 'SALES' AND r.tenant_id = i.tenant_id AND r.sale_order_id = i.sale_order_id
  AND (r.reason = 'Credit note ' || i.invoice_number OR r.reason LIKE 'Credit note ' || i.invoice_number || ': %');

-- The reports read shipments and returns by order line and by date
CREATE INDEX IF NOT EXISTS idx_sm_source_line ON stock_movements (source_line_id) WHERE source_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_issue ON invoices (tenant_id, type, issue_date);
