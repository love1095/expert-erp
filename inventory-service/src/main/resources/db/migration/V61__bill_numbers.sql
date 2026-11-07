-- Phase Q2: hand-typed supplier invoices (no purchase order) take their own number series, not the sales INV- one
ALTER TABLE tenants ADD COLUMN bill_prefix      varchar(10) NOT NULL DEFAULT 'BILL-';
ALTER TABLE tenants ADD COLUMN next_bill_number bigint      NOT NULL DEFAULT 1;
