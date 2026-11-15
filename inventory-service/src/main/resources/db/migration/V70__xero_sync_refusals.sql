-- How often Xero refused an item outright (a validation error). The next try is a new request with a new
-- Idempotency-Key: Xero rejects a key used again with another body, e.g. after the account mapping was corrected.
ALTER TABLE xero_sync_items ADD COLUMN refusals INTEGER NOT NULL DEFAULT 0;
