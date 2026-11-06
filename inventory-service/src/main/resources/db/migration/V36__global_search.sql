-- Phase I2: search everything from the top bar ("contains", any case), with trigram indexes like products' (V10).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_customers_name_trgm       ON customers       USING gin (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_suppliers_name_trgm       ON suppliers       USING gin (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_sale_orders_number_trgm   ON sale_orders     USING gin (lower(order_number) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_sale_orders_customer_trgm ON sale_orders     USING gin (lower(customer_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_number_trgm ON purchase_orders USING gin (lower(order_number) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_supplier_trgm ON purchase_orders USING gin (lower(supplier_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_invoices_number_trgm      ON invoices        USING gin (lower(invoice_number) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_invoices_party_trgm       ON invoices        USING gin (lower(party_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_products_barcode_trgm     ON products        USING gin (lower(barcode) gin_trgm_ops);
