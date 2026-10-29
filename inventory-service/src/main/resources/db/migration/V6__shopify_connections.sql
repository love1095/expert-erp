-- One Shopify store per tenant (Phase 7.1).
-- shop_domain is unique across ALL tenants: webhooks arrive without a logged-in user, and the
-- shop domain in the webhook header is how the backend finds the tenant they belong to.
-- client_secret and access_token are encrypted with APP_ENCRYPTION_KEY (see SecretCipher).

CREATE TABLE shopify_connections (
    id                       bigserial PRIMARY KEY,
    tenant_id                bigint       NOT NULL,
    shop_domain              varchar(255) NOT NULL,
    client_id                varchar(255) NOT NULL,
    client_secret_encrypted  text         NOT NULL,
    access_token_encrypted   text,
    token_expires_at         timestamp(6),
    scopes                   text,
    shop_name                varchar(255),
    shop_currency            varchar(3),
    status                   varchar(20)  NOT NULL DEFAULT 'NOT_TESTED',
    last_error               text,
    last_checked_at          timestamp(6),
    created_at               timestamp(6) NOT NULL DEFAULT now(),
    updated_at               timestamp(6) NOT NULL DEFAULT now(),
    CONSTRAINT uk_shopify_connections_tenant UNIQUE (tenant_id),
    CONSTRAINT uk_shopify_connections_shop   UNIQUE (shop_domain),
    CONSTRAINT shopify_connections_status_check CHECK (status IN ('NOT_TESTED', 'CONNECTED', 'ERROR'))
);
