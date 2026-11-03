-- Phase 7.5 (F): the Shopify link must not lose anything. Webhooks are answered as soon as they are stored and
-- processed in the background; a failed event is tried again later, waiting longer each time, until it gives up.
ALTER TABLE shopify_webhook_events ADD COLUMN attempts        int          NOT NULL DEFAULT 0;
-- When a failed event is tried again; null = not waiting (processed, ignored, or given up)
ALTER TABLE shopify_webhook_events ADD COLUMN next_attempt_at timestamp(6);
-- Taken by a worker until then, so two workers (or a worker and a person pressing Retry) never run it together
ALTER TABLE shopify_webhook_events ADD COLUMN locked_until    timestamp(6);
CREATE INDEX idx_shopify_webhook_events_due ON shopify_webhook_events (next_attempt_at) WHERE status = 'FAILED';
CREATE INDEX idx_shopify_webhook_events_received ON shopify_webhook_events (received_at) WHERE status = 'RECEIVED';

-- The daily check against Shopify: orders a webhook missed, webhook subscriptions Shopify removed
ALTER TABLE shopify_connections ADD COLUMN last_reconciled_at      timestamp(6);
ALTER TABLE shopify_connections ADD COLUMN last_reconcile_summary  text;
ALTER TABLE shopify_connections ADD COLUMN last_reconcile_error    text;
