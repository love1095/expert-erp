-- Phase H3: VAT return. Each tax rate says which VAT boxes it goes to (docs/business-rules.md §3, like Xero's tax
-- types), and the notional rate a reverse-charge / EC-acquisition line reports (nothing is charged on the line).
ALTER TABLE tax_rates ADD COLUMN vat_type varchar(20) NOT NULL DEFAULT 'STANDARD'
    CHECK (vat_type IN ('STANDARD', 'REDUCED', 'ZERO', 'EXEMPT', 'NO_VAT', 'REVERSE_CHARGE', 'EC_ACQUISITION', 'FULL_VAT'));
ALTER TABLE tax_rates ADD COLUMN report_rate numeric(6,3) NOT NULL DEFAULT 0;

UPDATE tax_rates SET vat_type = CASE
        WHEN name ILIKE '%reverse charge%' THEN 'REVERSE_CHARGE'
        WHEN name ILIKE 'EC Acquisitions%' THEN 'EC_ACQUISITION'
        WHEN name ILIKE 'Full VAT%' OR rate >= 100 THEN 'FULL_VAT'
        WHEN name ILIKE '%exempt%' THEN 'EXEMPT'
        WHEN name ILIKE 'No VAT%' THEN 'NO_VAT'
        WHEN rate = 0 THEN 'ZERO'
        WHEN rate < 10 THEN 'REDUCED'
        ELSE 'STANDARD' END;
-- Reverse charge: the supplier charges no VAT, the return shows 20 % in box 1 and box 4 (like Cin7 shows it, at 0 %)
UPDATE tax_rates SET report_rate = CASE WHEN rate > 0 THEN rate ELSE 20 END, rate = 0 WHERE vat_type = 'REVERSE_CHARGE';
UPDATE tax_rates SET report_rate = CASE WHEN name ILIKE '%zero%' THEN 0 ELSE 20 END, rate = 0 WHERE vat_type = 'EC_ACQUISITION';
UPDATE tax_rates SET report_rate = rate WHERE vat_type NOT IN ('REVERSE_CHARGE', 'EC_ACQUISITION');

-- The rates Cin7 uses that the UK defaults did not have, for organisations that already have rates
INSERT INTO tax_rates (tenant_id, name, rate, usage, active, vat_type, report_rate)
SELECT t.tenant_id, x.name, x.rate, 'PURCHASE', true, x.vat_type, x.report_rate
FROM (SELECT DISTINCT tenant_id FROM tax_rates) t
CROSS JOIN (VALUES ('EC Acquisitions (20%)', 0, 'EC_ACQUISITION', 20),
                   ('EC Acquisitions (Zero Rated)', 0, 'EC_ACQUISITION', 0),
                   ('Full VAT', 100, 'FULL_VAT', 100)) AS x(name, rate, vat_type, report_rate)
WHERE NOT EXISTS (SELECT 1 FROM tax_rates r WHERE r.tenant_id = t.tenant_id AND r.usage = 'PURCHASE' AND lower(r.name) = lower(x.name));
