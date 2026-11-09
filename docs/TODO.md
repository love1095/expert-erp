# TODO — things waiting on someone or something

Open items gathered from `PROGRESS.md` (where each one is explained under its step). Tick here and there when done.

## Needs the owner (an account, a login or a decision)
- [ ] **Expert Safety's website address**: to compare what the site offers with what the ERP covers (not found online on 2026-11-09)
- [ ] **Review before anything new** ([`review-and-test-plan.md`](review-and-test-plan.md), 2026-11-08): decide S1–S12 (menu structure: keep / merge / move / rename) to agree a target menu, then flow tests F1–F10 and the page-by-page recheck
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

## Review fixes to do (found in the review, details in [`review-findings.md`](review-findings.md))
Done module by module with the page recheck (Part 4 step 5 of the review plan), so each page is touched once.
- [x] **R7 Confirmation pop-ups** steps 1–2 done 2026-11-09: own pop-up, 23 browser boxes replaced, ~70 actions that move stock or money, delete or send now ask, ticked rows always ask
- [ ] **R7 step 3, saves**: owner to choose a) pop-up on every save, or b) (recommended) no pop-up, "Saved" message, unsaved-changes warning, pop-up only when editing money or stock already booked
- [ ] **R7 reasons**: move the `prompt()` reason boxes (void payment, credit hold, remove credit, dismiss refund) into the pop-up
- [x] **R9 Product selection list like Cin7** done 2026-11-09 on SO/PO lines and contract prices (opens on click, scroll loads more, keyboard, PO shows on hand/on order/cost, supplier's products first)
- [ ] **R9 check in Cin7** (look only, needs the Chrome extension): the columns of Cin7's product list on sale and purchase lines, adjust ours to match
- [ ] **R10 Platform Organisations page**: page and search on the server (7,973 local test organisations = 2.1 MB drawn at once; e2e PLAT-001 times out until fixed)
- [ ] **R1 Search uses its indexes**: drop `coalesce` in search WHERE parts, trigram indexes for the missing columns, EXPLAIN test
- [x] **R2 "+ New" on its own page** (rule D1): done 2026-11-09 for orders, products, customers, suppliers (with a first supplier record page), hand-typed invoices and bills, manual journals (with a journal page); edit on the record page
- [ ] **S6 supplier page, rest**: statement, supplier deposits and contacts/addresses on `/suppliers/{id}` (first version 2026-11-09)
- [ ] **R3 Credit notes and returns**: own page, start from the order, one Return / credit action, lists; check double restock first (flow test F1)
- [ ] **R4 Simple vs Advanced**: keep one kind with "Receive and bill all" / "Ship and invoice all" (after the owner's OK and the Cin7 check)
- [ ] **R5 Product drop-downs**: type-to-search picker on Stock history, Movements Log, Transfer and Invoices
- [ ] **R8 UI improvement, cleaner look** (owner 2026-11-09: later, in phases). Coded directly on dev, no Figma; each page: detail → change on dev → before/after screenshots (desktop + phone) for the owner's OK → one commit
  - [ ] Phase 1: building blocks in `components/ui` (Section, SubHeading, StickyActions done 2026-11-09 with the sale and purchase order forms; still Button, Field, StatusTabs, Money, DateText) (Button, Field, Section, StatusTabs, sticky Save bar, Money, UK DateText), approved by the owner
  - [ ] Phase 2: three model pages: Sale orders list (status tabs with counts, Filters panel, empty columns hidden, clear progress), New sale order (Customer → Lines → Delivery → Notes, "More details", sticky Save), sale order page (one main button, others under "More")
  - [ ] Phase 3: the other pages module by module with the page recheck, together with R2 (`/new` pages) and R7 (pop-ups)
  - [ ] UK date format everywhere (a US-English browser shows 11/9/2026 for 9 November)
  - [ ] Footers (owner 2026-11-09): no website-style footer on every page. Instead: help / what's new / version / support at the bottom of the sidebar and in the user menu; list pages: footer bar with paging plus totals of the filtered rows on money lists and "3 selected · £1,240.00"; forms: the sticky Save bar; record pages: "Created by … · Last changed by … · View history"; printed documents: company number, VAT number, registered address, bank details, "Page 1 of 2" (check with the template review); login and register: "© Expert ERP · Privacy · Terms"
  - [x] Animations done 2026-11-09 (placeholder rows, Saved message, button spinner, menus and pop-ups fade, smooth sidebar, progress icons, dashboard count-up and bars, hover, keyboard focus; off with reduced motion)
  - [ ] Animations still to do with the R8 list pages: status-tab counts, highlight the row just changed, removed row fades out
- [x] R6 Window scrolled the app off the top (fixed daee4ae, 2026-11-09)

## Next phases after the review (owner, 2026-11-09: business review, to plan as phases)
Already in the ERP (the base to build on): Cin7's minimum-based Reorder list, top and worst sellers and the low-stock count on the dashboard, the Stock Value Inventory Aging report (by FIFO layers, 0–30 … 180+ days), nightly Xero and Shopify jobs.

**S1 Stock health (owner's idea), run every night**
- [ ] First: store the **supplier lead time** (per supplier, and per product–supplier when different), pack size and minimum order quantity
- [ ] First: **import Cin7's sales history** (needed: without it every product looks slow; part of the cutover data)
- [ ] Per product and warehouse: units sold in the last 30 and 90 days (shipments minus returns, kit components counted), average daily sales, **typical order size** (8 in 10 order lines are this size or smaller) and the largest recent order, **days of cover** = available ÷ daily sales
- [ ] **Low stock by sales, not only near 0**: available + on order below the reorder point (daily sales × lead time + safety stock, safety stock at least one typical large order); also flagged when available is less than one typical order even above the minimum
- [ ] **Suggested quantity**: covers the lead time plus the review period, rounded up to the pack size and minimum order; shown next to Cin7's minimum-based suggestion on the Reorder page
- [ ] **Slow and dead stock**: days since the last sale per product, months of cover; dead = stock but no sale in X days (setting, e.g. 180), slow = more than X months of cover; each with its stock value (money tied up)
- [ ] **ABC classes** (A = products making about 80 % of sales value, watched most closely)
- [ ] Where it shows: a dashboard panel, a report with CSV export, the Reorder page, and a **weekly e-mail digest** (once live e-mail is on)

**Manage dashboard, like Cin7 (owner, 2026-11-09)**: each user decides which parts the dashboard shows
- [ ] **The parts (widgets)**, from what the dashboard has today: the 7 figure tiles (revenue, orders to ship, stock value, products to reorder, units shipped, unpaid invoices, active customers), orders by status, Sales waiting now, Purchases waiting now, revenue per day, units in and out per day, the per-day table, sales by rep, top and worst sellers, recent sale orders, reorder list; later the Stock health panel (S1), overdue invoices, Xero / Shopify failed items
- [ ] **"Manage dashboard" button** on the dashboard: a panel with every part, a tick to show or hide it, and drag (or up / down arrows) to put them in order; "Reset to default"
- [ ] **Saved per user** on the server (follows the person to any computer), not in the browser; a new user gets the organisation's default
- [ ] **Organisation default** set by an admin (e.g. the warehouse sees stock and orders to ship, accounts see money); each user can change their own
- [ ] **Permissions win**: a part a role may not see (cost, stock value, money reports) is not offered and never sent by the server
- [ ] **Speed**: the server only works out the parts that are shown (today one call computes everything); each part loads on its own, with placeholder rows
- [ ] Sizes (half / full width) and a period per part (today / 7 / 30 days) if wanted
- [ ] Tests: hide a part → it is gone after a reload and on another browser; a role without the permission never sees the part; reset brings the default back
- To check first in Cin7 (look only): which parts its dashboard offers and how its "Manage dashboard" screen works, to copy it

**S2 Safety-supplier (PPE) functions**
- [ ] **Batch / serial / expiry tracking**: batch and expiry on receipt (first-aid kits, filters, spill kits), serial numbers (harnesses, lanyards); sell earliest expiry first; recall trace: which customers got a batch; expiry warning list
- [ ] **Embroidery / logo jobs**: an order line "to embroider" becomes a job: garments to the embroiderer, logo charge, extra lead time, stock coming back
- [ ] **Call-off and recurring orders** for contract customers (same kit every month)

**S3 Spec gaps (Should)**
- [ ] Drop-ship, and purchase orders made automatically from backorders
- [ ] Supplier price-list import; multi-currency purchasing
- [ ] Quotes pipeline with follow-up reminders; CRM activity log (calls, notes) on customers
- [ ] Notifications and automation rules (e.g. alert when the Shopify sync fails, approval for large orders)
- [ ] Stock transfers with an "in transit" status
- [ ] Custom fields
- Later (Could): B2B ordering portal, card payment links on invoices, Amazon / eBay sync, trade counter sales

**S4 Robustness before go-live**
- [ ] **Nightly reconciliation checks** with an alert when they drift: stock value vs the ledger's inventory account, FIFO layers vs stock on hand, ERP stock vs Shopify stock, ERP balances vs Xero
- [ ] **Monitoring and alerts**: error tracking, and alerts when the Xero, Shopify, e-mail or courier queues hold failed items (more than an uptime check)
- [ ] **Load test** at the spec's volumes (2,500+ products, 10,000+ orders a year, 5 years of history), including the ledger recalculation (Phase K item) and the big-data findings R1, R5, R10
- [ ] **Security review**: login rate limits and lock-out, session timeout, password rules, dependency scanning, a basic OWASP check, secrets out of the server image
- [ ] **GDPR**: export or delete a customer's personal data; data retention rules
- [ ] **Restore drill**: restore a backup onto a fresh server and time it
- [ ] **User acceptance testing**: each of the 5 staff runs their real daily jobs on the rehearsal copy and signs off

**S5 Product track (selling the ERP to other companies)**
- [ ] Self-service onboarding (set-up wizard, then imports), Stripe billing, modules switched on per plan
- [ ] Per-company data export, deletion and restore
- [ ] Support tools: "log in as" for support with audit logging; a status page
- [ ] More than one server: scheduled jobs locked so two servers don't run the same job
- [ ] QuickBooks connector; importers for systems other than Cin7
- [ ] Other countries' tax and currency rules (if sold outside the UK)
- [ ] Legal documents: terms of service, data processing agreement

**Suggested order**: finish the review (fixes above, flow tests) → S4 robustness → go-live (with the Cin7 sales history import) → S1 stock health and S2 PPE functions → S3 spec gaps → S5 product track. Each becomes a phase in `next-phases.md` (same format as L–Q) when its turn comes.

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
- [ ] Reports like Cin7's: drag-and-drop pivot, more fields, saved layouts; Sales Overview, Customer Orders by Product (Stock Value Inventory Aging is built, Reports)
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
- [ ] Reorder by sales velocity / lead time / minimum order value; multi-currency; supplier price-list import (now planned below: Stock health S1, Spec gaps S3)
- [ ] Custom fields and dynamic forms (planned below: Spec gaps S3)
