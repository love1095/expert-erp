-- Phase K2: Xero's financial year end and lock dates (Settings → Financial settings)
ALTER TABLE accounting_settings ADD COLUMN year_end_day   int;           -- empty = 31 March, Xero's UK default
ALTER TABLE accounting_settings ADD COLUMN year_end_month int;
-- "Lock date for all users except advisers" (advisers: admins until accountant access, K5) and "for all users"
ALTER TABLE accounting_settings ADD COLUMN lock_date      date;
ALTER TABLE accounting_settings ADD COLUMN hard_lock_date date;
