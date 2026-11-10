-- "+" buttons on the sale form, like Cin7: a payment term or a sales representative can be added to the list before it
-- is used on a customer or an order, and the order keeps the contact it was made for.

-- Payment terms added by hand. method: ON_INVOICE (due on the invoice date), DAYS (days after the invoice date),
-- DAYS_EOM (days after the end of the invoice month). Terms only known by name (from Cin7) are still read from the name.
CREATE TABLE payment_terms (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       varchar(100) NOT NULL,
    method     varchar(20) NOT NULL,
    days       integer NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_payment_terms_name ON payment_terms (tenant_id, lower(name));

CREATE TABLE sales_reps (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       varchar(100) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_sales_reps_name ON sales_reps (tenant_id, lower(name));

-- The customer's contact for this order (Cin7: Contact, Phone, Email on the sale), copied so later changes keep it
ALTER TABLE sale_orders
    ADD COLUMN contact_name  varchar(255),
    ADD COLUMN contact_phone varchar(100),
    ADD COLUMN contact_email varchar(255);
