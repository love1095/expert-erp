-- Payments on documents the ERP never sent (the Xero organisation's own invoices and bills) are no longer listed as
-- "Not matched": remove the ones listed so far.
DELETE FROM xero_pulled_payments WHERE status = 'UNMATCHED' AND doc_key IS NULL;
