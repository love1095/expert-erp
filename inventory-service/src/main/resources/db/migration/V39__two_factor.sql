-- Phase I5: two-step sign-in with an authenticator app (TOTP, RFC 6238), like Cin7 Core's two-factor authentication.
ALTER TABLE users ADD COLUMN totp_secret        varchar(200);   -- encrypted (SecretCipher); set while setting up too
ALTER TABLE users ADD COLUMN totp_enabled       boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN totp_last_step     bigint;         -- a code is accepted once
ALTER TABLE users ADD COLUMN totp_failed        int     NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN totp_locked_until  timestamp;
ALTER TABLE users ADD COLUMN totp_recovery      text;           -- SHA-256 of unused one-time recovery codes, comma-separated

-- Cin7: "Two-factor authentication required" per organisation
ALTER TABLE tenants ADD COLUMN two_factor_required boolean NOT NULL DEFAULT false;
