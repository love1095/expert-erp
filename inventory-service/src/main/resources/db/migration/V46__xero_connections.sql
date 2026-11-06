-- Phase L1: the Xero API link — one connection per organisation (tokens encrypted like the Shopify tokens)
CREATE TABLE xero_connections (
    tenant_id                bigint        PRIMARY KEY,
    xero_tenant_id           varchar(64),                  -- the chosen Xero organisation (xero-tenant-id header)
    xero_tenant_name         varchar(255),
    organisations            text,                         -- JSON: the organisations the user gave access to, to choose from
    access_token_encrypted   text,
    refresh_token_encrypted  text,
    token_expires_at         timestamp(6),
    oauth_state              varchar(64),
    status                   varchar(20)   NOT NULL DEFAULT 'NOT_CONNECTED',   -- NOT_CONNECTED, CHOOSE_ORGANISATION, CONNECTED, EXPIRED
    last_error               text,
    connected_at             timestamp(6),
    connected_by             varchar(100)
);
