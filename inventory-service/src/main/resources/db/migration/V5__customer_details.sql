-- Customers follow the Cin7 Core customer layout: commercial terms on the customer,
-- people (name, job title, phone, email, ...) as separate contacts, 0..n per customer.

ALTER TABLE customers ADD COLUMN status              varchar(20)  NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE customers ADD COLUMN currency            varchar(3);
ALTER TABLE customers ADD COLUMN payment_term        varchar(100);
ALTER TABLE customers ADD COLUMN tax_rule            varchar(100);
ALTER TABLE customers ADD COLUMN tax_number          varchar(100);
ALTER TABLE customers ADD COLUMN price_tier          varchar(100);
ALTER TABLE customers ADD COLUMN discount            numeric(5,2);
ALTER TABLE customers ADD COLUMN credit_limit        numeric(18,2);
ALTER TABLE customers ADD COLUMN account_receivable  varchar(50);
ALTER TABLE customers ADD COLUMN sale_account        varchar(50);
ALTER TABLE customers ADD COLUMN carrier             varchar(100);
ALTER TABLE customers ADD COLUMN sales_representative varchar(255);
ALTER TABLE customers ADD COLUMN location            varchar(255);
ALTER TABLE customers ADD COLUMN tags                text;
ALTER TABLE customers ADD CONSTRAINT customers_status_check CHECK (status IN ('ACTIVE', 'DEPRECATED'));

CREATE TABLE customer_contacts (
    id                bigserial PRIMARY KEY,
    tenant_id         bigint       NOT NULL,
    customer_id       bigint       NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    name              varchar(255),
    job_title         varchar(255),
    phone             varchar(100),
    mobile_phone      varchar(100),
    fax               varchar(100),
    email             varchar(255),
    website           varchar(255),
    comment           text,
    is_default        boolean      NOT NULL DEFAULT false,
    include_in_email  boolean      NOT NULL DEFAULT false,
    marketing_consent varchar(20)  NOT NULL DEFAULT 'UNKNOWN',
    CONSTRAINT customer_contacts_marketing_consent_check CHECK (marketing_consent IN ('UNKNOWN', 'OPT_IN', 'OPT_OUT'))
);

CREATE INDEX idx_customer_contacts_customer    ON customer_contacts (customer_id);
CREATE INDEX idx_customer_contacts_tenant_email ON customer_contacts (tenant_id, lower(email));

-- Email/phone typed on a customer become its default contact
INSERT INTO customer_contacts (tenant_id, customer_id, phone, email, is_default)
SELECT tenant_id, id, phone, email, true
  FROM customers
 WHERE email IS NOT NULL OR phone IS NOT NULL;

DROP INDEX idx_customers_tenant_email;
ALTER TABLE customers DROP COLUMN email;
ALTER TABLE customers DROP COLUMN phone;
