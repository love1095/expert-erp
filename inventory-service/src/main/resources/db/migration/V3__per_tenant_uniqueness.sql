-- Leftovers from before multi-tenancy: these made SKUs and names unique across ALL tenants.
-- The per-tenant constraints (tenant_id, sku) / (tenant_id, name) already exist and stay.
ALTER TABLE products   DROP CONSTRAINT IF EXISTS ukfhmd06dsmj6k0n90swsh8ie9g; -- UNIQUE (sku)
ALTER TABLE warehouses DROP CONSTRAINT IF EXISTS uk2qm0l82n5ivhyqwmgejxxefm1; -- UNIQUE (name)
ALTER TABLE categories DROP CONSTRAINT IF EXISTS ukt8o6pivur7nn124jehx7cygw5; -- UNIQUE (name)
ALTER TABLE suppliers  DROP CONSTRAINT IF EXISTS ukeegixpn11chp14nb25tl3ucv0; -- UNIQUE (name)

-- The role check predates SUPER_ADMIN, so that role could never be stored.
-- Who may be given SUPER_ADMIN is enforced in AuthController.register.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF'));
