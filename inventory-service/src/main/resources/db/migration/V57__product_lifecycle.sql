-- Phase P6: product lifecycle — Active, Discontinued (sold while stock lasts, never reordered), Archived (DEPRECATED, Cin7's name)
ALTER TABLE products DROP CONSTRAINT products_status_check;
ALTER TABLE products ADD CONSTRAINT products_status_check CHECK (status IN ('ACTIVE', 'DISCONTINUED', 'DEPRECATED'));
