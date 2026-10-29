-- Customers become their own table instead of a free-text name on each sale order.
-- source/external_id identify customers created by an integration (e.g. a Shopify customer id),
-- so a second import of the same customer finds the existing row.

CREATE TABLE customers (
    id          bigserial PRIMARY KEY,
    tenant_id   bigint       NOT NULL,
    name        varchar(255) NOT NULL,
    email       varchar(255),
    phone       varchar(100),
    address     text,
    notes       text,
    source      varchar(20)  NOT NULL DEFAULT 'MANUAL',
    external_id varchar(100),
    created_at  timestamp(6) NOT NULL DEFAULT now(),
    updated_at  timestamp(6) NOT NULL DEFAULT now(),
    CONSTRAINT customers_source_check CHECK (source IN ('MANUAL', 'SHOPIFY')),
    CONSTRAINT uk_customers_tenant_source_external UNIQUE (tenant_id, source, external_id)
);

CREATE INDEX idx_customers_tenant_name  ON customers (tenant_id, lower(name));
CREATE INDEX idx_customers_tenant_email ON customers (tenant_id, lower(email));

-- One customer per distinct name already used on a tenant's orders
INSERT INTO customers (tenant_id, name, created_at, updated_at)
SELECT tenant_id, customer_name, min(created_at), min(created_at)
  FROM sale_orders
 GROUP BY tenant_id, customer_name;

ALTER TABLE sale_orders ADD COLUMN customer_id bigint;

UPDATE sale_orders so
   SET customer_id = c.id
  FROM customers c
 WHERE c.tenant_id = so.tenant_id AND c.name = so.customer_name;

ALTER TABLE sale_orders ALTER COLUMN customer_id SET NOT NULL;
ALTER TABLE sale_orders ADD CONSTRAINT fk_sale_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers (id);
CREATE INDEX idx_sale_orders_customer ON sale_orders (customer_id);

-- sale_orders.customer_name stays: it is the name as it was when the order was placed.
