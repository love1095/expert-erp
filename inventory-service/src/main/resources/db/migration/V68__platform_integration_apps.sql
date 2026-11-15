-- The platform's own apps at other systems (one row per system, e.g. the Expert ERP app at Xero): set by the super admin
-- on Platform -> Integration apps and used by every organisation's connection. Belongs to no organisation.
CREATE TABLE platform_integration_apps (
    app                     VARCHAR(40) PRIMARY KEY,
    client_id               TEXT,
    client_secret_encrypted TEXT,
    redirect_uri            TEXT,
    webhook_key_encrypted   TEXT,
    updated_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_by              VARCHAR(255)
);
