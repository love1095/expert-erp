-- Phase L3: the CSV export and the Xero API share one record of what Xero has (a document sent one way is never sent
-- the other way); one way is on per organisation
ALTER TABLE accounting_exported ALTER COLUMN batch_id DROP NOT NULL;
ALTER TABLE accounting_exported ADD COLUMN via varchar(10) NOT NULL DEFAULT 'CSV';          -- CSV or API
ALTER TABLE accounting_settings ADD COLUMN xero_link varchar(10) NOT NULL DEFAULT 'CSV';    -- CSV files or the API

ALTER TABLE xero_sync_items ADD COLUMN doc_date date;
ALTER TABLE xero_sync_items ADD COLUMN amount numeric(18,2);
ALTER TABLE xero_sync_items ALTER COLUMN depends_on TYPE varchar(255);                     -- several keys, comma-separated
