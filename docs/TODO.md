# TODO — things waiting on someone or something

Open items gathered from `PROGRESS.md` (where each one is explained under its step). Tick here and there when done.

## Needs the owner (an account, a login or a decision)
- [ ] **Xero developer app** at developer.xero.com: client ID + client secret for the Xero API link
- [ ] **Xero Demo Company (UK)** in the owner's Xero login: to test the Xero link before cutover (never the real Expert Safety Xero before cutover)
- [ ] **Purchase charges account**: which Xero account bill carriage / duty go to (407 Import Duty is a placeholder in the local data)
- [ ] **Cin7 read-only check**: PriceTier1 = Retail and PriceTier2 = Contract (assumed from the data)
- [ ] **Customer contacts import**: CustomerContacts_11_4_2026.csv is built but not run — load it?
- [ ] **Couriers** and courier accounts in use today (phase I / warehouse)
- [ ] **Financial year start** as an organisation setting (1 December assumed, seen in Cin7)

## Decide at cutover (phase J) — runbook: [`cutover.md`](cutover.md)
- [ ] **Production server**: where it runs, domain, HTTPS; cron `infra/backup.sh` + an off-site copy of `backups/`; `infra/monitor.sh` (or an uptime service) on `/api/health`
- [ ] **Rehearsal** of the whole move on a copy, Go-live check all green, before the real weekend
- [ ] Fill Cin7's sale / purchase task templates with Expert Safety's open orders, unpaid invoices and bills (from Cin7's lists / reports); untick *Export imported tasks to Xero* for what Cin7 has already sent
- [ ] Switch Shopify from Cin7 to the ERP the same day (never both on)
- [ ] **Xero export start date**: the day the ERP takes over from Cin7, after the opening stock import (local import of 30/10/2026, £99,818.37, would double Xero's stock if exported)
- [ ] Sale order / purchase order / invoice number series = Cin7's next numbers
- [ ] Connect the real Expert Safety Xero; switch off Cin7's Xero autosync the same day
- [ ] Check the sales profit figures against Cin7's for one month once orders are migrated

## To run next time the app is up
- [x] E2E REPORT-001 (passed 2026-11-06 after rebuilding the backend; the running container was an older build)
- [x] E2E TEMPLATE-001, TWOFA-001, CUTOVER-001 (passed 2026-11-06 after rebuilding the backend; TWOFA-001 fixed: it signed in again from a fresh browser)
- [x] The full e2e suite for I3, I4, I5 and J (99 passed with 2 workers, 2026-11-06)
- [ ] The full backend test suite for I3, I4, I5 and J (built without starting the app)
- [ ] Expert Safety: make its Cin7 templates here (Invoice - Account Client / Cash Sale, Delivery Note, Pick List…) with its bank details and terms

## Test later with live systems
- [ ] **Shopify (live store)**: a price change updates only that product (GLGH-WG-310RP-08 → expect 1 updated); stock push to the real dev store (would set stock for 13,959 linked SKUs); Shopify payouts (money paid into the bank, less fees) not matched to orders yet; Shopify-paid invoices tested with a fake order only
- [ ] **Xero, first real import** (Demo Company first): credit notes as negative lines in the invoice file, *Full VAT* (C79 import VAT) lines, penny differences where Xero recomputes discounted lines

## Needs the owner's OK
- [ ] Add a QR code library (e.g. the npm package `qrcode`) so two-step sign-in setup shows a QR code; meanwhile the setup key and phone link work

## Phase K — ERP accounting (after cutover)
- [ ] Expert Safety: import its chart from Xero (Chart of accounts → Export) and set the conversion date + conversion balances from Xero's trial balance on the day it switches (until then the ledger also holds the local opening stock import, posted Dr 631 / Cr 310 by Cin7's mapping of adjustments to COGS)
- [ ] Ledger speed on large organisations: it is brought up to date by recomputing every source before each read (fine on today's data, 0.04 s); post on each change instead if it gets slow
- [ ] Have an accountant review the ledger design (accounts posted per document) before building
- [ ] Run K alongside Xero for a few months at Expert Safety; move off Xero at a year end once the figures match
- [ ] HMRC Making Tax Digital: register as a software developer, pass the sandbox tests
- [ ] Choose an Open Banking provider for live bank feeds (cost per connection), or statement import only

## Build later (deliberately left for now)
- [ ] Sale / purchase task import, not like Cin7 yet: Auto Pick / Auto Pick + Pack are not separate steps (needs pick lists, warehouse phase); credit lines at another price than the invoice's, negative credit note charges, part refunds, Cin7's Restock lines; currencies other than GBP; Cin7's 100-task limit not copied (not needed); undo of an import (today: restore the backup taken before)
- [ ] **Xero API link**: contacts, invoices, credit notes, bills, payments both ways, journals, retries — built against a fake Xero, live-tested with the Demo Company
- [ ] Payments on account (customer credit / supplier deposits) in the Xero files
- [ ] E-mailing documents, statements and reminders (with the e-mail integration, spec §7)
- [ ] Reports like Cin7's: drag-and-drop pivot, more fields, saved layouts; Sales Overview, Customer Orders by Product, Stock Value Inventory Aging (phase I)
- [ ] Roles (I1 done): Settings permissions inside roles (now admin only), several roles per user, Cin7's other ticks when those modules exist
- [ ] Expert Safety's real users: set their roles at cutover (in Cin7 all 5 are on FULL)
- [ ] API key rate limits (calls per minute per Application Key)
- [ ] Supplier credit notes for a price (no goods back) don't lower FIFO cost yet; landed cost only if wanted
- [ ] A return without a credit note is on no document for *All COGS*
- [ ] Cin7's deprecated tax rules (5 % income, Exempt, Reverse Charge) still active here; cash accounting VAT scheme not built
- [ ] Shipping not stopped for orders confirmed before a credit hold
- [ ] Hand-typed purchase invoices still take INV- numbers
- [ ] Shopify orders imported before the VAT split keep their old lines
- [ ] Product: Combined view (families), alternative products, product images, extra units of measure, channels, Merge products
- [ ] Accounting tab on sale orders; supplier tax-rule field on the supplier form
- [ ] Reorder by sales velocity / lead time / minimum order value; multi-currency; supplier price-list import
- [ ] Custom fields and dynamic forms
