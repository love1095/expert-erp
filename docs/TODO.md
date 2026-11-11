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
- [ ] E2E for the Export / Import menus (2026-11-11, built without starting the app): new `tests/products/export-import.spec.ts` EXP-001…004, changed PRD-005, PRD-006, PRD-007 (Import / Export are now menus); customers CUST import/export cases (menus moved into the shared building block)
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
- [ ] **R9 check in Cin7** (look only): the columns of Cin7's product list on sale and purchase lines, to adjust ours. Not done on 2026-11-09 (the dashboard was checked): seeing it means opening a new sale or clicking into an existing order's lines, which may create a draft or change an order. **Owner to choose**: send a screenshot of Cin7's product list on a sale and a purchase line, or let Claude open an existing draft quote, look only at the product list and press Esc without picking
- [x] **"+" buttons on the sale form, like Cin7** done 2026-11-10 (owner's request, f7a053a): "+" next to Customer, Contact, Terms and Sales rep on `/sale-orders/new` and Edit opens a pop-up (New customer, New contact, Add a payment term, Add a sales representative) and the new entry is chosen in its field. New: a **Contact** on the sale order (name, phone, e-mail; the customer's default contact unless another is chosen), payment terms and sales reps kept in their own lists (`payment_terms`, `sales_reps`, migration V64), a term added by hand gives the due date by its own rule (days after the invoice date / after the end of the invoice month / on the invoice date). Backend QuickAddTest, e2e SO-015; screenshots in `docs/screenshots/sale-form-plus/`
- [x] **Date boxes start on today on creation pages** (owner's rule, 2026-11-10, f7a053a): new sale: Ship by = today, Quote valid until = today + the organisation's quote days (today itself would make the quote run out the same day); new purchase order: Required by = today. Invoice, bill, payment, deposit, credit note, journal and bank dates already started on today. Left empty on purpose: due dates (empty = worked out from the terms), contract price "To", certificate expiry. One shared `todayIso()` in `lib/dates.ts` (the computer's own date; the old `toISOString()` gave yesterday just after midnight in summer time). Editing keeps the document's own dates. e2e SO-016
- [ ] **"+" buttons, rest**: the same "+" on the purchase order form (Supplier, Terms), the customer and supplier forms (Terms, Sales rep), invoices and bills; the Contact on the printed quote / order / invoice templates and in e-mails; a page to rename or remove payment terms and sales reps (Settings); check Cin7's own pop-up fields (look only) to match them
- [ ] **R10 Platform Organisations page**: page and search on the server (7,973 local test organisations = 2.1 MB drawn at once; e2e PLAT-001 times out until fixed)
- [ ] **R1 Search uses its indexes**: drop `coalesce` in search WHERE parts, trigram indexes for the missing columns, EXPLAIN test
- [x] **R2 "+ New" on its own page** (rule D1): done 2026-11-09 for orders, products, customers, suppliers (with a first supplier record page), hand-typed invoices and bills, manual journals (with a journal page); edit on the record page
- [ ] **S6 supplier page, rest**: statement, supplier deposits and contacts/addresses on `/suppliers/{id}` (first version 2026-11-09)
- [ ] **R3 Credit notes and returns**: own page, start from the order, one Return / credit action, lists; check double restock first (flow test F1)
- [ ] **R4 Simple vs Advanced**: keep one kind with "Receive and bill all" / "Ship and invoice all" (after the owner's OK and the Cin7 check)
- [ ] **R5 Product drop-downs**: type-to-search picker on Stock history, Movements Log, Transfer and Invoices
- [ ] **R8 UI improvement, cleaner look** (owner 2026-11-09: later, in phases). Coded directly on dev, no Figma; each page: detail → change on dev → before/after screenshots (desktop + phone) for the owner's OK → one commit
  - [ ] Phase 1: building blocks in `components/ui`, **built 2026-11-10, waiting for the owner's OK** (committed f7a053a): Button (primary / secondary / danger / ghost, busy spinner, 3 sizes, LinkButton), FormField + Input / Select / Textarea / Checkbox (label, hint, error, required star), StatusTabs (counts, red count for warnings, arrow keys, scrolls on a phone), Money, DateText (UK date on every computer; `ClientDate` now uses it), with Section, SubHeading, StickyActions from 2026-11-09. Sample page `/ui-kit` (no sign-in, sample data only); screenshots in `docs/screenshots/r8-phase1/`. The old `BTN_PRIMARY / BTN_SECONDARY / BTN_DANGER` classes now come from Button. The pages themselves change in phases 2 and 3
  - [ ] Phase 2: three model pages: Sale orders list (status tabs with counts, Filters panel, empty columns hidden, clear progress), New sale order (Customer → Lines → Delivery → Notes, "More details", sticky Save), sale order page (one main button, others under "More")
  - [ ] Phase 3: the other pages module by module with the page recheck, together with R2 (`/new` pages) and R7 (pop-ups)
  - [ ] UK date format everywhere (a US-English browser shows 11/9/2026 for 9 November): `DateText` and `lib/dates.ts` built 2026-11-10, the 28 places using `ClientDate` are fixed; the other pages move to it with phase 3
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
- [x] **Part 1 done 2026-11-09**: "Manage dashboard" pop-up (tick per part, ↑ ↓ order, Collapse all, Expand all, Reset layout, admin: "Make this the default for everyone"); each part's own icons (half / full width, fold, hide); saved per user on the server (`dashboard_layouts`, `GET/PUT/DELETE /api/dashboard/layout`, `PUT /api/dashboard/layout/default` admin only), organisation default for users without their own; parts hidden without their permission (sales parts SALE_VIEW, purchases and reorder PURCHASE_VIEW, stock chart PRODUCT_VIEW); backend DashboardLayoutTest, e2e DASH-002
- [ ] Part 2: the server works out only the parts shown, each part loads on its own
- [ ] Part 3: Cin7's parts we lack: Quick links (counts), Bills and orders (owed to suppliers, POs not invoiced / received), top customers, sales by brand / region; drag to reorder (needs a library, owner's OK)
- [ ] **The parts (widgets)**, from what the dashboard has today: the 7 figure tiles (revenue, orders to ship, stock value, products to reorder, units shipped, unpaid invoices, active customers), orders by status, Sales waiting now, Purchases waiting now, revenue per day, units in and out per day, the per-day table, sales by rep, top and worst sellers, recent sale orders, reorder list; later the Stock health panel (S1), overdue invoices, Xero / Shopify failed items
- [ ] **"Manage dashboard" button** on the dashboard: a panel with every part, a tick to show or hide it, and drag (or up / down arrows) to put them in order; "Reset to default"
- [ ] **Saved per user** on the server (follows the person to any computer), not in the browser; a new user gets the organisation's default
- [ ] **Organisation default** set by an admin (e.g. the warehouse sees stock and orders to ship, accounts see money); each user can change their own
- [ ] **Permissions win**: a part a role may not see (cost, stock value, money reports) is not offered and never sent by the server
- [ ] **Speed**: the server only works out the parts that are shown (today one call computes everything); each part loads on its own, with placeholder rows
- [ ] Sizes (half / full width) and a period per part (today / 7 / 30 days) if wanted
- [ ] Tests: hide a part → it is gone after a reload and on another browser; a role without the permission never sees the part; reset brings the default back
- **Checked in Cin7 (look only, 2026-11-09)**, Overview dashboard → General Dashboard:
  - A **period** box at the top for the whole dashboard (e.g. Custom, with the dates) and a **Manage dashboard** button.
  - The Manage dashboard pop-up: *Dashboard actions*: **Collapse all widgets · Expand all widgets · Reset layout**; *Select widgets to display*: a tick per widget: **Quick links, Referral credit program, Key performance indicators, Discover Knowledge Base, Bills and orders, Purchases, Sales, Product overview, Customer overview, Reorder**; a Close button.
  - Each widget's own header icons: **expand width** (half ↔ full), **collapse** (fold to its title), **close** (hide, same as unticking), and a **settings** icon on some.
  - What the widgets show: Quick links (counts of chart of accounts, suppliers, products, customers); KPIs (revenue, net, pending orders, due, overdue, inflow, outflow, with tabs and a bar chart and "View report"); Bills and orders (bills draft / awaiting delivery / due / overdue, POs draft / authorised / not invoiced / not received, with a chart); Purchases and Sales (status counts: draft, draft invoices, awaiting payment, overdue, awaiting delivery / ready to ship, with the matching list); Product overview (top selling, worst selling, brand, region); Customer overview (by sales rep, top customers, by products); Reorder (SKU, product, supplier, available + on order).
  - **To copy**: the tick list, Reset layout, collapse / expand all, per-widget expand width / collapse / close, one period for the whole dashboard. **Better than Cin7**: drag to reorder (Cin7 has no order in the pop-up), saved per user on the server with an organisation default, parts hidden by permission, only the shown parts are worked out.
  - Ours maps onto it: KPI tiles ≈ Key performance indicators; Sales / Purchases waiting now ≈ Sales / Purchases; top and worst sellers ≈ Product overview; sales by rep ≈ Customer overview; reorder ≈ Reorder. Missing on ours: Quick links (counts), Bills and orders (money owed to suppliers), top customers, brand / region views.

**Export / Import menus like Cin7 (owner, 2026-11-10: plan only, not built yet)**: on Cin7's lists Export (and Import) is a drop-down of file types, not one file
- **Checked in Cin7 (look only, 2026-11-10; menus read, nothing ticked, nothing downloaded)**:
  - `/CustomerList` Export and Import: Customers · Customer addresses · Customer contacts · Custom prices. **The ERP already has all four**, both ways.
  - `/Product` Export (17): Inventory list · Inventory list (without descriptions) · Stock on hand · Fixed assets · Discount matrix · Assembly BOM · Production BOM · Units of measure · Stock reorder locations · Product prices by supplier · Reorder parameters · Images and attachments · Markup prices · Simple prices · Custom prices · Bulk SKU change · Bulk costing method change. Import: the same without "(without descriptions)". Print: Product Label · Product Specification. The ERP has one Export (inventory list) and one Import.
  - `/SupplierList` Export: Suppliers · Supplier addresses · Supplier contacts; Import: the same + Product prices by supplier. The ERP has none.
  - `/PurchaseList` Export: Export accounts payable · Export to CSV format. The ERP exports only the ticked rows. `/SaleList`: an Export to CSV and Import CSV file / Extract from file; its menu was not read reliably, to check again.
- [x] **X1 One shared menu** done 2026-11-11: `components/FileMenus.tsx` (`ExportMenu`, `ImportMenu`, `saveFile`) used by customers, products, suppliers, sales and purchases; files made on the server in Cin7's columns and file names, written row by row (JDBC / pages of 500); an import refuses a file of another type; an audit line per export; cost columns left out (not emptied) without "Product - Prices: cost price", so importing the file back keeps the costs
- [x] **X2 Products** done 2026-11-11 (`ProductDataFileService`), columns copied from Cin7's own sample files: Export = Inventory list · Inventory list (without descriptions) · Stock on hand (one row per FIFO batch left: quantity, unit cost, warehouse, the product's bin, day received) · Units of measure (AdditionalUOM) · Assembly BOM · Simple prices (tiers 1–4; 5–10 empty) · Custom prices. Import = the same without "(without descriptions)": stock on hand is previewed then applied, one file may hold several warehouses (Location = warehouse name); units and kits replaced per product in the file (Action "Delete" removes a line); simple prices never create products. Left: a unit has no SKU of its own here (Cin7 makes each unit a product), so AdditionalUnitsOfMeasureSKU is empty; batch / expiry columns empty until S2; Bin = the product's default bin, not stock per bin
- [x] **X3 Suppliers** (Suppliers file) done 2026-11-11 (`SupplierFileService`): export one row per supplier in Cin7's 42 columns; import creates / updates by name, the ContactDefault row gives the contact. Columns with nothing to hold them here (AccountPayable, Carrier, attributes, job title, mobile, fax) are written empty and ignored
- [ ] **X3 rest**: Supplier addresses and Supplier contacts after the supplier page gets its address and contact lists (review S6); Cin7's sample files are in `~/Documents/expertsafety/design-pages/importexport` (not in the project)
- [x] **X4 Sale and purchase lists** done 2026-11-11 (`OrderListExportService`): Export → "Export to CSV format" = every order of the current search and filter (sales: also the one-customer filter), made on the server; purchases also "Export accounts payable" (one row per open bill, credit note or deposit, needs "purchase prices" or financial reports); purchase totals only with "purchase prices". The ticked-rows Export stays in the bulk bar. Our own columns: Cin7's purchase export shows only after ticking rows, which was not done in the live account; to match its columns, the owner can send one Cin7 sale and one purchase "Export to CSV format" file
- [ ] **X5 Later, each with the function it needs**: Reorder parameters and Stock reorder locations (with Stock health S1: lead time, per-warehouse levels) · Product prices by supplier (with S3 supplier price lists) · Images and attachments (a list of file links; import by link) · Bulk SKU change (old SKU → new SKU; must also move the Shopify links and keep history, needs its own tests)
- **Not planned** (no such function here): Fixed assets, Production BOM, Discount matrix, Markup prices, Bulk costing method change (the ERP is FIFO only)
- [ ] **Sample files, at implementation time** (owner's OK 2026-11-10: "yes download it during time implementation"): when X2–X4 are built, Claude clicks each Export in Cin7 to copy the exact columns: Products: Stock on hand, Units of measure, Assembly BOM, Simple prices; Suppliers: Suppliers, Supplier addresses, Supplier contacts; Purchases: Export accounts payable, Export to CSV format (9 files, into Chrome's Downloads). Only the Export menu items, no rows ticked, no Import / More actions / bulk menus; stop and report if a click opens anything other than a download. The files hold real supplier and price data: read the headers and a few rows, never copy them into the project or commit them. If the work starts in a later session, confirm with the owner again before the first download
- **Decided 2026-11-10**:
  - **What is exported: everything of that type, whatever the list shows** (like Cin7). The specification does not say; Cin7's own files on this computer show it: `InventoryList_2026-10-29.csv` has 13,974 active + 1,592 deprecated products and `Customers_2026-10-29.csv` has deprecated customers too, although Cin7's lists open on "Only active". The sale and purchase "Export to CSV format" (X4) is the one that follows the list's search and filter; to confirm with its sample file
  - **Who may export: everyone who can see the list** (owner). No separate Export tick per role for now; cost columns still follow the permission to see costs
  - **Format**: the specification asks for **CSV / Excel** import and export of products, prices and stock "with validation and error report" (Must), and Excel/CSV from the report builder (Should). Plan: CSV first (what Cin7 gives and takes back); Excel (.xlsx) as a second step, it needs a library (owner's OK). The import's error report: lines skipped with the reason, downloadable (customers and products list warnings on screen today)
  - **Also in the specification**: "Full export of all data at any time" (data ownership) → one "Export all data" action for an admin: every file above in one zip (with S5 per-company data export); "Import supplier price files and update cost prices in bulk" (Should) → Product prices by supplier, X5
- Tests: each file exported then imported back changes nothing; a file of the wrong type is refused; another organisation's rows never appear; 14,000 products export within a few seconds; e2e: each menu lists its types and downloads a file with the right name

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
