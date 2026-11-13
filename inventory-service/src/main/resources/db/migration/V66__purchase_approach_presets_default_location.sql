-- New purchase, the rest (like Cin7): the Approach of a purchase, presets of the header, the default location.

-- STOCK_FIRST: the supplier's bill covers only what has come in. INVOICE_FIRST: only what is billed can be received.
-- Orders so far are Stock first (nothing changes for what is already received or billed: the rule is checked on new steps).
ALTER TABLE purchase_orders ADD COLUMN approach varchar(20) NOT NULL DEFAULT 'STOCK_FIRST';

-- The organisation's defaults for a new purchase
ALTER TABLE tenants ADD COLUMN default_purchase_approach varchar(20) NOT NULL DEFAULT 'STOCK_FIRST';
ALTER TABLE tenants ADD COLUMN default_warehouse_id bigint REFERENCES warehouses (id) ON DELETE SET NULL;

-- Cin7's "Save as preset" / "Select a preset": a named copy of a purchase's header (supplier, terms, location...),
-- kept as the form's own JSON so the form can fill itself from it. Names are unique per organisation, ignoring case.
CREATE TABLE purchase_presets (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       varchar(100) NOT NULL,
    data       text NOT NULL,
    created_by varchar(255),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_purchase_presets_name ON purchase_presets (tenant_id, lower(name));
