-- Review R1: "contains" searches use their trigram indexes. The searches no longer wrap columns in coalesce() (an index
-- on lower(col) does not match lower(coalesce(col, ''))); here are the indexes for the searched columns that had none
-- (one unindexed column in an "A OR B OR C" search makes Postgres read the whole table).
CREATE INDEX IF NOT EXISTS idx_sale_orders_customer_ref_trgm ON sale_orders USING gin (lower(customer_reference) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_sale_orders_external_ref_trgm ON sale_orders USING gin (lower(external_reference) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_supplier_ref_trgm ON purchase_orders USING gin (lower(supplier_reference) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_products_supplier_code_trgm ON products USING gin (lower(supplier_product_code) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_suppliers_contact_trgm ON suppliers USING gin (lower(contact_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_suppliers_email_trgm ON suppliers USING gin (lower(email) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customer_contacts_name_trgm ON customer_contacts USING gin (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customer_contacts_email_trgm ON customer_contacts USING gin (lower(email) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customer_contacts_phone_trgm ON customer_contacts USING gin (phone gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customer_contacts_mobile_trgm ON customer_contacts USING gin (mobile_phone gin_trgm_ops);
-- Review R10: the platform's organisation search
CREATE INDEX IF NOT EXISTS idx_tenants_name_trgm ON tenants USING gin (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_tenants_slug_trgm ON tenants USING gin (lower(slug) gin_trgm_ops);
