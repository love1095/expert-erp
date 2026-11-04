-- Phase G3: customer statements and overdue reminders (docs/roadmap.md, spec §5).
-- Each printed reminder is recorded on the overdue invoices it lists, so the next one escalates:
-- 1st reminder -> 2nd reminder -> Final notice.
ALTER TABLE invoices ADD COLUMN reminder_count   integer   NOT NULL DEFAULT 0 CHECK (reminder_count >= 0);
ALTER TABLE invoices ADD COLUMN last_reminder_at timestamp;
CREATE INDEX IF NOT EXISTS idx_payments_invoice ON payments (invoice_id);
