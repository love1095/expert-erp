# TODO — things waiting on someone or something

Open items gathered from `PROGRESS.md` (where each one is explained under its step). Tick here and there when done.

## Needs the owner (an account, a login or a decision)
- [ ] **Xero developer app** at developer.xero.com (phase L is built against a fake Xero): client ID + client secret, redirect URI `https://<server>/finance/xero/callback`; then set APP_XERO_MODE=live, APP_XERO_CLIENT_ID, APP_XERO_CLIENT_SECRET, APP_XERO_REDIRECT_URI
- [ ] **Xero webhook key** (from the Xero developer app, Webhooks: delivery URL `https://<server>/api/webhooks/xero`, events Invoices) → APP_XERO_WEBHOOK_KEY; without it payments from Xero are still polled every 5 minutes
- [ ] **Xero Demo Company (UK)** in the owner's Xero login: to test the Xero link before cutover (never the real Expert Safety Xero before cutover)
- [ ] **Purchase charges account**: which Xero account bill carriage / duty go to (407 Import Duty is a placeholder in the local data)
- [ ] **Cin7 read-only check**: PriceTier1 = Retail and PriceTier2 = Contract (assumed from the data)
- [ ] **Customer contacts import**: CustomerContacts_11_4_2026.csv is built but not run — load it?
- [ ] **E-mail**: the SMTP account or mail provider (e.g. Microsoft 365, Google Workspace, SendGrid) and the sender address (e.g. accounts@expertsafety.co.uk) → Settings → E-mail, then APP_MAIL_MODE=live
- [ ] **Landed cost** (phase P5, built, off): should freight and duty on supplier bills be added to the stock cost (by value or by quantity) instead of an expense? Inventory → Adjustments → Landed cost; the accountant should agree as it changes margins and stock value
- [ ] **Stock adjustment approval value** (phase N5): above what value an adjustment needs a manager (Inventory → Adjustments → Change; empty today = none)
- [ ] **Couriers** and courier accounts in use today (phase O is built against a fake courier): e.g. Royal Mail Click & Drop, DPD, Parcelforce, Evri, or one shipping hub (ShipStation, Sendcloud…); with the accounts and API keys the live clients are built, then APP_COURIER_MODE=live
- [ ] **Financial year end**: now a setting (Accounting → Ledger settings, 31 March by default) — set Expert Safety to 30 November (1 December start seen in Cin7) once the owner confirms

## Decide at cutover (phase J) — runbook: [`cutover.md`](cutover.md)
- [ ] **Production server**: run it on UK time (`TZ=Europe/London` for the backend container: "today" for payments, statements and lock dates is the server's date, UTC in development); where it runs, domain, HTTPS; cron `infra/backup.sh` + an off-site copy of `backups/`; `infra/monitor.sh` (or an uptime service) on `/api/health`
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
- [x] The full backend test suite (231 tests, green on 2026-11-07 after PermissionTest was updated for the Adviser tick and the Accountant role)
- [ ] Expert Safety: make its Cin7 templates here (Invoice - Account Client / Cash Sale, Delivery Note, Pick List…) with its bank details and terms

## Test later with live systems
- [ ] **Shopify (live store)**: a price change updates only that product (GLGH-WG-310RP-08 → expect 1 updated); stock push to the real dev store (would set stock for 13,959 linked SKUs); Shopify payouts (money paid into the bank, less fees) not matched to orders yet; Shopify-paid invoices tested with a fake order only
- [ ] **Xero API link, live test on the Demo Company** (phase L, built against a fake): connect, send each document type, pull a payment back
- [ ] **Shopify product images (phase P4)**: run the product sync on the dev store with a product that has images; check they appear once, main image first
- [ ] **Couriers, live**: once built for the owner's couriers — book a real parcel, print its label on the warehouse printer, cancel one, follow tracking
- [ ] **Xero, first real import** (Demo Company first): credit notes as negative lines in the invoice file, *Full VAT* (C79 import VAT) lines, penny differences where Xero recomputes discounted lines

## Needs the owner's OK
- [ ] **Mail library** for live e-mail (phase M): `spring-boot-starter-mail` (Jakarta Mail / Angus Mail) so `SmtpMailSender` can send by SMTP; until then e-mails are kept in the outbox (fake mode)
- [ ] **Camera barcode scanning** on the phone page (phase N4): a library such as `@zxing/browser` (npm) to read barcodes with the phone camera; meanwhile a handheld scanner (acting as a keyboard) or typing the SKU works
- [ ] Add a QR code library (e.g. the npm package `qrcode`) so two-step sign-in setup shows a QR code; meanwhile the setup key and phone link work

## Phase K — ERP accounting (after cutover)
- [ ] Expert Safety: import its chart from Xero (Chart of accounts → Export) and set the conversion date + conversion balances from Xero's trial balance on the day it switches (until then the ledger also holds the local opening stock import, posted Dr 631 / Cr 310 by Cin7's mapping of adjustments to COGS)
- [ ] Ledger speed on large organisations: it is brought up to date by recomputing every source before each read (fine on today's data, 0.04 s); post on each change instead if it gets slow
- [ ] Have an accountant review the ledger postings (accounts posted per document) before relying on them: give them a login with the Accountant role and the year-end pack
- [ ] Run K alongside Xero for a few months at Expert Safety; move off Xero at a year end once the figures match
- [ ] HMRC Making Tax Digital (built, K4): register on HMRC's Developer Hub, create an application subscribed to the VAT (MTD) API, set APP_HMRC_CLIENT_ID / _CLIENT_SECRET / _REDIRECT_URI (https://<server>/accounting/vat/callback) / _VENDOR_PUBLIC_IP; test with a sandbox test user; then apply for production credentials (HMRC checks the fraud prevention headers) and set APP_HMRC_BASE_URL=https://api.service.hmrc.gov.uk
- [ ] Expert Safety: record the VAT returns already filed from Xero (Accounting → VAT returns → Mark as filed) so the periods are locked here too
- [ ] Choose an Open Banking provider for live bank feeds (cost per connection); meanwhile statements are imported (CSV / OFX, K3)
- [ ] Bank rules, cash coding, minor adjustments on a match, bank reconciliation summary report, QIF (Xero has them; K3 does not yet)

## Build later (deliberately left for now)
- [ ] Sale / purchase task import, not like Cin7 yet: credit lines at another price than the invoice's, negative credit note charges, part refunds, Cin7's Restock lines; currencies other than GBP; Cin7's 100-task limit not copied (not needed); undo of an import (today: restore the backup taken before)
- [ ] Payments on account (customer credit / supplier deposits) in the Xero files and the Xero API link (Xero overpayments / prepayments)
- [ ] Xero API link: a document changed after it was sent (Xero has no edit once paid): today it is listed by the CSV export's "changed after export" check, to correct in Xero by hand
- [ ] Reports like Cin7's: drag-and-drop pivot, more fields, saved layouts; Sales Overview, Customer Orders by Product, Stock Value Inventory Aging (phase I)
- [ ] Roles: Cin7's other ticks when those modules exist (Settings tick and several roles per user done in Q5, 2026-11-07)
- [ ] Expert Safety's real users: set their roles at cutover (in Cin7 all 5 are on FULL)
- [ ] API key rate limits (calls per minute per Application Key)
- [x] Supplier credit notes for a price (no goods back) lower the FIFO cost (Q3, 2026-11-07); landed cost built in P5, off
- [x] A return without a credit note is on no document for *All COGS* (Q4: its cost comes off its order, 2026-11-07)
- [ ] Cin7's deprecated tax rules (5 % income, Exempt, Reverse Charge) still active here; cash accounting VAT scheme not built
- [x] Shipping not stopped for orders confirmed before a credit hold (Q1, 2026-11-07)
- [x] Hand-typed purchase invoices still take INV- numbers (Q2: BILL- series, 2026-11-07)
- [ ] Shopify orders imported before the VAT split keep their old lines
- [ ] Product: Combined view (families), alternative products, channels, Merge products (images and units of measure: phase P)
- [ ] Accounting tab on sale orders; supplier tax-rule field on the supplier form
- [ ] Reorder by sales velocity / lead time / minimum order value; multi-currency; supplier price-list import
- [ ] Custom fields and dynamic forms
