# Expert ERP — Project Progress

## Architecture

| Layer | Tech |
|---|---|
| Backend | Spring Boot 3.5, Hibernate 6.6, Spring Security (JWT), Spring Data JPA |
| Database | PostgreSQL 16 (Docker) |
| Frontend | Next.js 16 (App Router, React 19), React Query v5, Axios, Tailwind CSS |
| Infra | Docker Compose, multi-stage Dockerfile, Makefile (`make up`); secrets in a git-ignored `.env` (copy `.env.example`) |
| Schema | Flyway migrations (`inventory-service/src/main/resources/db/migration`), Hibernate `ddl-auto=validate` |
| Tests | Backend: JUnit 5 + Testcontainers (throwaway Postgres 16). End-to-end: Playwright (`e2e-tests/`) |
| CI | GitHub Actions (`.github/workflows/ci.yml`): backend tests + end-to-end tests on every push / PR to `main` |

Multi-tenancy: **DISCRIMINATOR** strategy — shared database, `tenant_id` column in every entity, filtered automatically by Hibernate via `TenantContext` (ThreadLocal).

---

## Phase 1 — MVP ✅

> Goal: answer "What is my stock right now?"

- [x] **Product management** — create/edit/delete, SKU, name, unit, description, cost price, min stock, category
- [x] **Stock movement** — heart of the system; stock is *computed* (Σ IN − Σ OUT), never stored directly
- [x] **Warehouse** — basic single/multi warehouse model
- [x] **Stock overview** — current stock per product per warehouse, computed from movements

---

## Phase 2 — Usable Inventory ✅

- [x] **Stock Transfer** — warehouse A → B creates two movements (TRANSFER_OUT + TRANSFER_IN)
- [x] **Movement types** — `PURCHASE_IN`, `SALE_OUT`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `TRANSFER_IN`, `TRANSFER_OUT`, `SALE_RETURN_IN` (added in Phase 6A)
- [x] **Reference tracking** — `reference_type` (PURCHASE_ORDER, SALE_ORDER, SALE_RETURN, TRANSFER, ADJUSTMENT, MANUAL) + `reference_id` on every movement
- [x] **Stock History (timeline)** — all movements per product, sorted by time, paginated
- [x] **Validation** — `InsufficientStockException` (HTTP 422) before any OUT movement; prevents negative stock
- [x] **Purchase Orders** — create order → receive → auto-creates PURCHASE_IN movements per line
- [x] **Sale Orders** — create order → fulfill → auto-creates SALE_OUT movements per line (lifecycle extended in Phase 6A)
- [x] **Stock summary** — per product per warehouse, with `lowStock` flag
- [x] **Low stock alert** — `minStock` threshold, highlighted in UI (red/amber)
- [x] **Supplier management** — CRUD + search + pagination

---

## Phase 3 — Real System ✅

- [x] **Audit Log** — `entity_type`, `entity_id`, `action` (CREATE/UPDATE/DELETE), `username`, `changes` (JSON diff), `created_at`
- [x] **Concurrency control** — `@Transactional` on all service methods + `PESSIMISTIC_WRITE` lock on `ProductRepository` during stock calculation
- [x] **Permissions & Roles** — `SUPER_ADMIN`, `ADMIN`, `MANAGER`, `STAFF`; DELETE endpoints require ADMIN, user management requires ADMIN
- [x] **Event-driven foundation** — `StockChangedEvent` published by `StockMovementService` (Spring `ApplicationEvent`); ready for async listeners
- [x] **CSV export** — `GET /api/stock-movements/export/csv` with product/warehouse filters

---

## Invoicing ✅

- [x] `Invoice` (SALES / PURCHASE), `InvoiceItem`, `Payment` (CASH / BANK / STRIPE), per-tenant `InvoiceSequence` numbering
- [x] Invoice lifecycle — DRAFT → ISSUED → PAID (payments recorded against issued invoices), cancel unless paid
- [x] Optional link to a sale order or purchase order (`saleOrderId` / `purchaseOrderId`)
- [x] Frontend `/invoices` page

---

## Phase 4 — Flexibility 🔲

- [ ] **Custom Fields** — JSONB column in PostgreSQL per entity (not yet built)
- [ ] **Dynamic Forms** — render fields from config in React (not yet built)

---

## Phase 5 — SaaS / Multi-tenant ✅

### Tenant System
- [x] `Tenant` entity — id, name, slug (unique), active flag
- [x] `TenantContext` — ThreadLocal holder, set by `JwtAuthFilter` on every request
- [x] `TenantResolver` — Hibernate `CurrentTenantIdentifierResolver`, routes all queries through discriminator
- [x] All entities carry `tenant_id`; data is fully isolated per tenant
- [x] New org signup creates a tenant automatically (`/api/auth/register` with `organizationName`)
- [x] `SUPER_ADMIN` role can list/toggle tenants (`/api/tenants`)

### Subscription System
- [x] `Plan` entity — `FREE`, `PRO`, `ENTERPRISE` tiers with limits and monthly price
- [x] `Subscription` entity — tenant ↔ plan, status (ACTIVE/CANCELLED/EXPIRED), dates
- [x] **Plan limits enforced server-side** (HTTP 402 when exceeded):
  - Products: FREE=50, PRO=500, ENTERPRISE=∞
  - Users:    FREE=3,  PRO=25,  ENTERPRISE=∞
  - Warehouses: FREE=2, PRO=10, ENTERPRISE=∞
- [x] `GET /api/subscription` — current plan + live usage counts
- [x] `GET /api/plans` — all available plans
- [x] `POST /api/subscription/upgrade` — switch plan (ADMIN only)
- [x] New org registration auto-assigns FREE plan
- [x] Plans seeded by `DataSeeder` on startup
- [x] Frontend `/settings/subscription` page — usage bars, plan comparison cards
- [x] Sidebar shows current plan tier badge

---

## Phase 6A — Core Order Model (gap fixes) ✅

> Goal: make the sale-order lifecycle reliable before connecting external systems (Shopify).

### Foundation
- [x] **Flyway** — `V1__baseline.sql` = schema as it was under `ddl-auto=update`; existing databases are baselined at V1 automatically (`baseline-on-migrate`)
- [x] Hibernate switched to `ddl-auto=validate` — **every schema or enum change now needs a migration** (enum columns have CHECK constraints)
- [x] **Testcontainers** test suite (`./mvnw test`) — fresh Postgres per run; never touches the dev DB
- [x] `DataSeeder` no longer crashes on an empty database (no warehouse yet)

### Order lifecycle (gap: all-or-nothing fulfilment)
- [x] Order status `DRAFT → CONFIRMED → COMPLETED` / `CANCELLED`, tracked separately from fulfilment status `UNFULFILLED / PARTIALLY_FULFILLED / FULFILLED`
- [x] Explicit **confirm** step — only CONFIRMED orders can ship
- [x] **Partial fulfilment** — `Fulfilment` + `FulfilmentLine` entities; per-line `fulfilledQuantity`; SALE_OUT movements only for shipped quantities
- [x] **Idempotency** — optional `idempotencyKey` (unique per tenant) on fulfilments and returns; retries are applied once
- [x] **Concurrency** — order row locked (`PESSIMISTIC_WRITE`) for confirm / fulfil / cancel / return
- [x] Insufficient-stock protection kept (HTTP 422); stock checked per product across all lines of a shipment

### Cancellations & returns (gap: only DRAFT could be cancelled)
- [x] Cancel before shipping → `CANCELLED`; cancel after partial shipment → remaining quantities cancelled, order `COMPLETED`, shipped quantities kept
- [x] **Returns** — `SaleReturn` + `SaleReturnLine`; per-line `restock` flag: restocked lines create `SALE_RETURN_IN`, damaged ones are recorded only; cannot return more than shipped
- [x] Refunds stay financial (invoices) — not linked to stock

### Audit actor (gap: audit required a logged-in user)
- [x] `audit_log.actor_type` (`USER` / `SYSTEM` / `INTEGRATION`) + `actor_id`; `AuditActorContext.runAs(...)` for jobs and webhooks
- [x] Audit log UI shows actor badge; new actions CONFIRM, RETURN, CANCEL_REMAINING

### Frontend
- [x] Sale orders: "Manage" panel — confirm, ship (per-line quantities), cancel / cancel remaining, record return (restock toggle, target warehouse), shipment & return history
- [x] Order status + fulfilment progress badges

### Fixed along the way
- [x] Stock queries now share one `StockMovementRepository.IN_TYPES` constant (was copied in 5 queries); a test enforces it matches `MovementType.isIn()`
- [x] `IllegalArgumentException` → HTTP 400 (was 500)

---

## Automated Testing (Option C) ✅

> Goal: a regression safety net before Shopify. Option B (an in-app testing module) comes later, near the end of the project.

### Testable UI
- [x] All form labels linked to their inputs (`htmlFor` / `id`) — tests find fields by label; also better accessibility
- [x] `data-testid` on sale-order rows/panels and purchase-order rows; accessible names on per-line ship/return inputs

### `e2e-tests/` (Playwright)
- [x] **Every test registers its own tenant** (ENTERPRISE by default, `plan` option for limit tests) — tests are isolated, parallel, and never touch real tenants
- [x] Fixtures: logged-in `page`, tenant `api` client, `warehouses`; page helpers in `fixtures/pages/` (e.g. `SaleOrdersPage`)
- [x] Test case IDs + tags in titles (`SO-004 … @P0 @sale-orders`); `npm run catalog` exports every case to JSON
- [x] Reports: HTML (`npm run report`), interactive UI mode (`npm run test:ui`), JSON (`test-results/results.json`)
- [x] **30 test cases** at the time: AUTH (5), PRD (2), STK (3), PO (1), SO (7), SEC (5), MT multi-tenant (4), PLAN (2), AUD (1)

### CI
- [x] GitHub Actions: backend JUnit + Testcontainers job; end-to-end job (Docker Compose backend, built frontend, Playwright) with the HTML report uploaded as an artifact

### Bugs found by the tests and fixed
- [x] SKU / warehouse / category / supplier names were unique across **all** tenants (leftover global constraints) → `V3__per_tenant_uniqueness.sql`
- [x] Wrong-password error never shown (401 interceptor reloaded the login page) → login call excluded from the redirect
- [x] Unhandled errors surfaced as HTTP 403 (`/error` was secured) → `/error` permitted; DB constraint violations → HTTP 409
- [x] Sign-up trusted a client-supplied `role` (SUPER_ADMIN was blocked only by a DB check) → founders are always ADMIN; only a SUPER_ADMIN can grant SUPER_ADMIN; unknown role → 400; DB now accepts SUPER_ADMIN
- [x] Invoice list pagination never worked (wrong prop name) — caught by the CI type-check

---

## Phase 8 — Customers ✅

> Goal: customers as real records in the Cin7 Core layout, so existing Cin7 data can be imported and Shopify orders can attach to a customer.

### Backend
- [x] `Customer` (`V4__customers.sql`, `V5__customer_details.sql`), tenant-isolated, fields as in the Cin7 export: status (Active / Deprecated), currency, payment term, tax rule, tax number, price tier, discount, credit limit, accounting codes, carrier, sales rep, location, tags, address, comments
- [x] **Contacts** (`customer_contacts`, 0..n per customer): name, job title, phone, mobile, fax, email, website, comment, include-in-email, marketing consent; exactly one default contact, listed first
- [x] `source` (`MANUAL` / `SHOPIFY`) + `external_id`, unique per tenant + source — an integration finds the customer it imported before
- [x] Sale orders link to a customer (`customer_id`, required); `customer_name` stays as the name **at order time**
- [x] Migration turned each distinct customer name on existing orders into a customer, per tenant, and linked the orders
- [x] Create order with `customerId`, or with `customerName` only: reuses the oldest customer with that name (ignoring case), else creates one
- [x] `/customers` CRUD; search matches customer name and contact name / email / phone; order counts; customers with orders cannot be deleted (422 — set Deprecated instead)
- [x] **Cin7 CSV import** (`POST /customers/import`, ADMIN/MANAGER): rows grouped by name (Cin7 writes one row per contact); existing customers updated, others created; re-import is safe; columns missing from the file keep existing values; cleans spreadsheet `'` on phones and `2,500.00` amounts; per-row warnings instead of failing the whole file
- [x] `/sale-orders?customerId=` filter; customer create / update / delete / import are audited (`CUSTOMER`)

### Frontend
- [x] Customers page (MASTER DATA): Cin7 fields, contacts editor (default contact radio), search, Deprecated status, expandable recent orders, **Import CSV** with a result summary and warnings
- [x] Sale order form: customer field suggests active customers; a new name is flagged and created on save

### Data
- [x] Local dev DB: `Customers_2026-10-29.csv` imported into the Expert Safety tenant — 885 rows → 332 customers, 876 contacts (counts checked against the file). The CSV itself is not in the repo

### Tests
- [x] Backend: `CustomerServiceTest` (7), `CustomerImportServiceTest` (4, made-up Cin7-shaped fixture: multi-line comment, `'` phones, thousands separators, Deprecated, re-import, case-insensitive match, wrong file)
- [x] E2E: CUS-001…CUS-008 (38 cases total)

---

## Phase 6 — Dashboard ✅

> Goal: one screen showing the state of stock, orders and sales.

### Backend
- [x] `GET /dashboard?days=7|30|90` (1–365) — one call, native SQL with an explicit tenant filter on every query
- [x] KPIs: sales value and order count for the period (cancelled orders and cancelled quantities excluded), orders to ship, drafts to confirm, stock value at cost, low-stock products (below their minimum across all warehouses), units shipped, unpaid sales invoices (issued minus payments), active customers, orders by status
- [x] Per-day series with every day present: orders, sales value, units in / out (warehouse transfers excluded — they don't change total stock)
- [x] Top 5 products shipped, 10 lowest-stock products (furthest below minimum first), 5 most recent orders

### Frontend
- [x] Dashboard home page: period switch (7 / 30 / 90 days), KPI tiles linking to their pages, sales-per-day bars, units in/out bars on one shared scale, top products, low stock, recent orders
- [x] Charts are plain HTML (no chart library): hover tooltip per day, legend, "Show as table" view; colours from a palette checked for colour-blind separation and contrast

### Tests
- [x] Backend: `DashboardServiceTest` (2) — exact figures for known data, another tenant's data never counted, empty tenant
- [x] E2E: DSH-001…DSH-004 (42 cases total)

---

## Phase 6.1 — Dashboard to the functional spec (Cin7 parity, part 1) ✅

> Goal: the dashboard widgets the owner's spec marks **Must** (§8) that need no schema change, modelled on the live Cin7 Core dashboard.
> Next: 6.2 sales rep on orders + sales by rep, VAT on order lines (revenue ex-VAT), bills due/overdue; 6.3 part receipts on POs + supplier bills, revenue vs expense by month. Plan: [`docs/roadmap.md`](docs/roadmap.md).

### Backend
- [x] `GET /dashboard?from=&to=` (both days included, at most 366 days); `?days=` still works. Every period query now has an upper bound too
- [x] Status counts, whatever the period: **sales** — draft orders, to ship, draft invoices, awaiting payment, overdue invoices; **purchases** — awaiting delivery (draft POs), draft bills, bills to pay, overdue bills. Awaiting payment = issued with a balance left; overdue = also past its due date
- [x] **Reorder list** `GET /dashboard/reorder?search=&page=&size=`: Cin7's rule — active stock products with a minimum or a reorder quantity whose stock + quantity on draft (not yet received) POs is **at or below** the minimum, lowest first (Cin7 uses available = on hand − allocated; on hand until allocation exists); supplier = the product's default supplier; suggested quantity = the product's reorder quantity, else what brings it back to its minimum. Search matches SKU, name or supplier. KPI "Products to reorder" replaces "Low stock products"
- [x] **Top and worst sellers** by value (10 each) among products ordered in the period, cancelled orders and quantities left out; replaces "top products shipped"
- [x] Unpaid sales invoices now count only invoices with a balance left (an overpaid invoice no longer lowers the total)

### Frontend
- [x] Period picker with Cin7's presets: today, yesterday, last 7 / 30 / 90 days, this / last month, this / last quarter (calendar quarters), this / last financial year (starts 1 December, as in Cin7), custom range. "This …" periods cover the whole period, days to come included (as Cin7)
- [x] "Sales — waiting now" and "Purchases — waiting now" panels linking to their lists; Top / Worst sellers switch; Reorder panel with search and paging (its own query)
- [x] The figures stay on screen while another period loads, so the table view and the sellers switch keep their state

### Tests
- [x] Backend: `DashboardServiceTest` (5) — + reorder list (on order, received/cancelled POs, deprecated and service products, search, paging), status counts, date range bounds (80 backend tests total)
- [x] E2E: DSH-001…DSH-005 (60 cases total)

### Data
- Checked on the real local data (13,974 Cin7 products): every dashboard call answers in under 0.06 s. The reorder list shows ~14k products until stock on hand is imported from Cin7

---

## Phase C — Cin7 stock on hand (opening balances) ✅

> Goal: real stock in the ERP, the same as Cin7, so Shopify stops showing "Sold out" and the reorder list is right. Plan: [`docs/roadmap.md`](docs/roadmap.md), rules: [`docs/business-rules.md`](docs/business-rules.md).

### Backend
- [x] `POST /stock/import` (ADMIN / MANAGER): Cin7 **AvailabilityReport** CSV (Inventory → Availability → Export), one location per file, into a chosen warehouse. Each SKU's stock is set to the file's OnHand with an `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` movement (reference `STOCK_IMPORT`, the file name), so history stays complete; the same file again changes nothing; products not in the file are left alone
- [x] **Preview** (`apply=false`): the same checks and figures — rows, products that change, unknown SKUs, units and value before / after, largest changes — then rolled back
- [x] `V11`: `stock_movements.unit_cost` — the cost of stock coming in, for FIFO later. Imports cost at Cin7's StockValue / OnHand (£0 included, so the opening value equals Cin7's); the product cost price only when a row has no value. Received purchase orders now record their line cost too
- [x] One audit entry per import (`STOCK_IMPORT`); products locked while the differences are posted
- [x] `DataSeeder` no longer invents demo stock or **random minimum stock** — it was filling every product without a minimum (14,729 of Cin7's products) with a random one on each start
- [x] Reorder rule aligned with Cin7 (see Phase 6.1): at or below the minimum, products with only a reorder quantity included

### Frontend
- [x] Stock Overview → **Import stock**: warehouse, file, Preview (summary, changes with unit cost, warnings), then **Import N changes**

### Data (local dev database, real Cin7 files — never committed)
- [x] Removed the 1,193 seeded fake "Opening stock" movements (146,329 units); re-imported `InventoryList_2026-10-29.csv` → minimums equal Cin7's for every product
- [x] Imported `AvailabilityReport_2026-10-30.csv` (location "Expert Warehouse") into **ExpertSafety Warehouse**: 2,121 products, **19,178 units, £99,818.37** (Cin7: 19,178 units, £99,818.31 — 6p from unit costs kept to 4 decimals); every SKU matched, quantities equal Cin7 for all 6,756 rows
- [x] Leftovers of local test orders set to 0 the same way (CLEARANCE 40, EXBLUEROLL −1 and +100 in the second warehouse, NSX1859S −1)
- [x] Reorder list: **429** vs Cin7's 446 — the same rule on the same stock gives exactly 429; the 17 others need Cin7's allocations and open POs (227 / 90 rows), which come with allocation (phase D) and moving open orders (phase J)

### Tests
- [x] Backend: `StockImportServiceTest` (4) — quantities and costs, preview, re-import, refused files, other tenant's same SKU, PO receipt cost; `DashboardServiceTest` reorder rule updated (84 backend tests total)
- [x] E2E: STK-004 (61 cases total)

---

## Phase D — Sales (spec §2) 🔄

> Goal: one sales flow like Cin7 — quote → order → ship → invoice → payment, with VAT, reps, reservations and backorders. Plan and Cin7 findings: [`docs/phase-d-sales.md`](docs/phase-d-sales.md).

### D1 Order header, VAT and totals ✅
- [x] `V12`: per-organisation **tax rates** seeded for the UK with Cin7's names (`20% (VAT on Income)`, `Zero Rated Income`, `No VAT`, 5 %, exempt; purchase side incl. reverse charge); organisations made later get them on first use
- [x] Each line gets **discount %** (the customer's by default) and its **VAT**: product's sale tax rule → customer's tax rule → organisation default; the rule's name and rate are **copied onto the line**, so later rate changes never alter an order. A different rate on a line needs **MANAGER or above** (403 otherwise) and a **reason** when it is less VAT; changes are in the audit log
- [x] **Additional charges** (carriage…): no product, no stock, own VAT (`sale_order_charges`)
- [x] Order header: **customer reference** (their PO number), **sales rep**, **terms**, **carrier** (copied from the customer, editable), ship by, shipping notes; Shopify orders get the organisation's web sales rep (*Web Sales*) and their Shopify number as reference; their lines carry no VAT until phase F settles Shopify VAT
- [x] **Order numbers** from a per-organisation sequence (`SO-` + next number), settable in **Settings → Organisation** to continue Cin7's series at cutover; numbers in use are skipped; existing orders keep `SO-<id>`
- [x] Totals before tax / VAT / total per order; net, VAT and **margin %** per line (over the product's cost price until FIFO costing)
- [x] `POST /sale-orders/preview`: the order as it would be created, nothing saved — the form shows defaults, VAT per line and totals as it is filled in
- [x] Dashboard: **revenue ex-VAT** (after discounts, charges included) and **sales by rep** (orders, ex-VAT, VAT, total)
- [x] Frontend: new order form (reference, rep, terms, ship by, carrier, notes; discount and VAT per line — VAT locked for STAFF; charges; live totals); list shows number, reference, rep, total inc VAT; order panel shows the header, price / discount / VAT / net / margin per line, charges and totals; Settings → Organisation (numbering, Shopify rep, default VAT, VAT rates)
- [x] Checked on real data: AE Yates Ltd + HVJC-F01-Y-M at £25.65 gives rep Dwaine Silverblade, 30 Day EOM, Expert Van, 20 % VAT and **36.10 % margin — the same as Cin7's SO-66003**
- [x] Tests: `SaleOrderVatTest` (6) — VAT rule order, discounts, charges, totals, rates changing later, override permissions and reasons, numbering, preview, dashboard revenue and reps (90 backend tests); E2E SO-008…SO-010 (64 cases)

### D2 Reservation, available stock and backorders ✅
- [x] **Reserved** stock is computed from order lines, never stored (`AllocationService`): the quantity still to ship on CONFIRMED orders and on Shopify orders from the moment they are imported (drafts kept for review included). Shipping or cancelling ends a reservation by itself
- [x] **Available = on hand − reserved**, per product and warehouse; below 0 when more is promised than there is
- [x] **First confirmed, first served**: per product and warehouse, stock covers lines in order of confirmation; each line of a reserving order shows its **allocated** and **backordered** quantity, and the order is **Backordered** when any line is short (one query for a whole page of orders)
- [x] Confirming without enough stock is allowed (the shortfall is a backorder)
- [x] **Shipping is strict like Cin7**: a line ships at most what is allocated to it, so stock promised to orders confirmed earlier is never taken; "Ship items" pre-fills the allocated quantities, more is refused with the reason (422); products are locked before the allocation is worked out, so shipments running at the same time can't take the same units
- [x] Shown on: order list (Backordered badge), order panel (allocated column, "N × SKU short"), Stock Overview (on hand / reserved / available), Products (available under total), dashboard (**Backordered** count)
- [x] Reorder list and "Products to reorder" use **available** + on order, as Cin7 does
- [x] Order form: a **product search box** (SKU or name, active products, with available stock and price, fills in the shop price) instead of a dropdown of all ~14,000 products
- [x] Checked on real data: every call under 0.05 s; reorder list still 429 (no open orders locally); leftover Shopify test order SO-265 correctly backordered (its product has no stock)
- [x] Tests: `AllocationTest` (5) — first confirmed first served, confirmed-later-wins-by-confirmation, release on ship / cancel, drafts vs Shopify imports, warehouses kept apart, reorder on available, shipping limited to allocated; `SaleOrderLifecycleTest` updated to the strict rule (96 backend tests); E2E SO-011 (incl. refused shipment), SO-003, SO-008 and CUS-002/003 use the search box (65 cases)

### D3 Quotes ✅
- [x] A **quote** is the first stage of the same sale document (same number, as in Cin7): status `QUOTE` (`V13`), reserves no stock, is not a sale, cannot be confirmed or shipped
- [x] **Valid until** date (default: today + the organisation's quote validity, 30 days, in Settings → Organisation); an **expired** quote can't be converted until it gets a new date
- [x] **Convert to order** (→ DRAFT, same number and lines) and **Reject quote** (→ `REJECTED`); all audited
- [x] **Edit** quotes and draft orders (`PUT /sale-orders/{id}`): header, lines and charges replaced, VAT and defaults worked out again; not for Shopify orders (rebuilt from Shopify) or anything confirmed
- [x] Sales are dated by **`ordered_at`** (when the sale became an order), so a quote counts in revenue from the day it is accepted; existing orders backfilled with their creation date
- [x] Frontend: "Create quote" next to "Create sale order", valid-until date; panel shows valid until / Expired, Convert to order, Reject quote, Edit (the same form, prefilled); list filter by stage (quotes, drafts, confirmed, completed, cancelled, rejected) and an Expired badge; dashboard **Quotes** count; quote validity in Settings
- [x] Tests: `QuoteTest` (4) — no reservation / not a sale until converted, revise keeps the number, expired and renewed, rejected, draft edits, Shopify drafts not editable, list filter (100 backend tests); E2E SO-012, SO-013 (67 cases)

### D4a Invoices from orders ✅
- [x] Cin7 checked first: an invoice is made from the order (SO-66003's INV-118553 = the 11 units of fulfilment #1, £507.00 of £622.62, "115.62 to invoice"), carries the order's VAT, a due date from the terms and payments — and Cin7 is synced with **Xero** ("View in Xero")
- [x] `POST /sale-orders/{id}/invoices` (`V14`): invoice the given quantities of a confirmed / completed order, or everything not invoiced yet; the order's charges not invoiced yet go on it too; prices, discounts and **VAT copied from the order lines**; draft or issued straight away
- [x] Invoices store net / VAT / total, customer, the order line or charge each item bills; GBP
- [x] **Due date from the terms**, as in Cin7: "30 Day EOM" = 30 days after the end of the invoice month, "60 Day" = 60 days after the invoice date, "Cash" = the invoice date (`PaymentTerms`; the terms used by Expert Safety's customers: 30 Day EOM ×744, Cash ×87, 60 Day EOM ×50, 60 Day ×4)
- [x] Orders track **invoiced quantity** per line and invoiced charges; asking for more than is left is refused; **cancelling an invoice puts its quantities and charges back** to "to invoice"; the order shows **to invoice / invoiced** (VAT included) and its invoices
- [x] **Invoice on shipment** (Settings → Organisation, off by default): every shipment creates and issues an invoice for what it shipped and is not invoiced yet, linked to the shipment
- [x] **Invoice numbers** from a per-organisation sequence with a settable prefix and next number (to continue Cin7's INV- series at cutover); numbers in use skipped; replaces the old INV-0001 counter
- [x] Frontend: order panel **Create invoice** (quantity per line pre-filled with what is left, include charges, *Create & issue* / *Create draft invoice*) and an Invoices section; Invoices page shows before tax / VAT / total and VAT per item; invoice settings
- [x] Tests: `InvoiceFromOrderTest` (3) — part invoices, VAT, due date, over-invoicing refused, cancel gives quantities back, only confirmed orders, invoicing on shipment with numbering; `PaymentTermsTest` (104 backend tests); E2E INV-001, INV-002 (69 cases)

### D4b Credit notes & restock, refunds ✅
- [x] As in Cin7 (CR-00231): `POST /invoices/{id}/credit-notes` (`V15`) credits part of an issued / paid sales invoice — quantities per item and/or extra amounts such as a **shipping refund** — with a reason
- [x] **Restock** per line, back into the order's warehouse (or a chosen one); only goods that were shipped can be restocked; never more than invoiced and not credited yet
- [x] **Settled** by paying off what is still due on the invoice (default), a **refund** (cash / bank / card, with its reference) or **kept as customer credit**, which lowers what the customer owes until refunded
- [x] Credit notes have their own number series (`CR-`, settable next number) and cannot be cancelled; an invoice with credit notes cannot be cancelled either
- [x] Frontend: Invoices panel **Credit note & restock** form (quantities, restock, shipping refund, settlement)
- [x] Tests: `CreditNoteTest` (5) (109 backend tests); E2E INV-003 (70 cases)

### Next
- [ ] D5 PDFs

---

## Phase E — Purchasing (spec §3) 🔄

> Plan, Cin7 findings and decisions: `docs/phase-e-purchasing.md`. Also done before it: the Cin7-style redesign (app frame, list toolbar, record pages for sale orders / invoices / POs / customers).

### E1 The purchase order like Cin7 ✅
- [x] Cin7 checked first: PO-19069 (Orn Clothing, partially received), PO-19064 (Portwest, billed £407.40 vs ordered £393.00 because of £14.40 shipping on the bill), reorder screen
- [x] `V16`: PO linked to the **supplier record** (name kept), **PO number series** per organisation (`PO-`, settable next number; existing orders keep PO-{id}), supplier reference, terms (from the supplier), **required by**, **ordered** date
- [x] Stages DRAFT → **ORDERED** (Cin7's *Authorise*, no approval step) → RECEIVED; drafts can be edited (`PUT`), ordered ones cancelled
- [x] Lines: **supplier SKU** and **cost** from the product's own supplier (Cin7 supplier price, else cost price; another supplier gets neither), discount %, **purchase VAT** by precedence: line override (manager+, audited, shared with sales in `TaxService.choose`) > product purchase tax rule > supplier tax rule > organisation default `20% (VAT on Expenses)`
- [x] **Additional costs** (freight, duty…) with their own VAT — an expense, not stock cost (decision 4); Cin7's totals grid (order lines / additional costs / total)
- [x] `POST /purchase-orders/preview` for the form; dashboard "awaiting delivery" and "on order" count draft + ordered POs; search also on PO number and supplier reference
- [x] Frontend: new PO form (supplier, deliver to, required by, terms, supplier reference, lines with supplier SKU / cost / disc / VAT, additional costs, live totals; *Create & mark ordered* / *Save as draft*), PO page (Mark as ordered, Edit, Receive, Cancel; lines, costs, totals grid), Settings → Organisation: PO prefix / next number / default purchase tax rule
- [x] Real data: Orn Clothing + WWJC-3200-BK-L fills supplier SKU 3200.BK.L, £9.21, 20 % VAT on Expenses, 30 Day EOM — 5 units = £46.05 as on Cin7's PO-19069
- [x] Tests: `PurchaseOrderTest` (3) (116 backend tests); E2E PO-002 (73 cases)

### E2 Goods receiving ✅
- [x] `V17`: **receipts** against a PO (several per order, Cin7's "Stock receiving"), each with its date, warehouse, notes, who; lines with the quantity and any **over-delivery**; retried requests applied once (idempotency key)
- [x] Per PO line: ordered, **received**, **outstanding**, **closed short**; receiving status *Not / Partially / Received*; the PO becomes RECEIVED once nothing is outstanding
- [x] **More than ordered is accepted and flagged** (decision 2) on the receipt, the line (▲) and the PO (*Over-delivered*)
- [x] **Close short**: nothing more is coming, the rest is closed and the PO is RECEIVED; a PO with stock in can't be cancelled (close short instead)
- [x] Stock comes in at the line's cost **after discount**; **on order** (reorder list) = outstanding on draft / ordered POs only
- [x] Frontend: PO page *Receive stock* (quantity per line prefilled with what is outstanding, "more than outstanding" warning, date, warehouse, notes), Receipts card in the trail, Received / Outstanding columns, Close short; list shows the receiving status
- [x] Tests: `PurchaseReceivingTest` (4) — PO-19069's 18 ordered / 13 received case, over-delivery, close short, retried receipt (120 backend tests); E2E PO-002 extended (73 cases)

### E3 Supplier bills and three-way match ✅
- [x] `V18`: a **bill from the PO** (`POST /purchase-orders/{id}/bills`, an invoice of type PURCHASE linked to the supplier): the **supplier's own invoice number** (entered once per supplier, cancelled bills aside), invoice date, **due date from the order's terms**, lines received and not billed yet (or those given) at the PO price unless the bill says otherwise, VAT and discount from the PO line
- [x] The supplier's **extra charges** on the bill (e.g. shipping) with their own VAT — an **expense** (decision 4), not a price difference
- [x] **Three-way match** per bill line: price vs the PO, billed vs received, billed vs ordered — shown on the bill ("⚠ Price £66.00 differs from the PO (£65.50)", "Billed 2, received 0") and counted on the PO's Bills card; billing more than is left is refused
- [x] PO: **billed** quantity per line, *Not / Partially / Fully billed*, *Unpaid / Partially paid / Paid* from its bills; cancelling a bill gives its quantities back; a PO with bills can't be cancelled
- [x] Payments on bills as on invoices; bills count in the dashboard's bills to pay / overdue
- [x] Frontend: PO page *Enter bill* (supplier's number, dates, quantity and price per line with "PO price" / "more than received" hints, charges), Bills card in the trail, Billed column; bill page with a Match column and "✓ Matches the PO and receipts"; list shows the billing status
- [x] Tests: `SupplierBillTest` (4) — Cin7's PO-19064 bill 006387333: 5 × 65.50 + £12 shipping = **£407.40**, due 30/11/2026 (30 Day EOM), no mismatch; price and quantity flags; cancel gives back; drafts not billable (124 backend tests); E2E PO-002 extended (73 cases)
- [ ] Stock cost following the bill price (decision 3) needs the FIFO layers — done in E5 as a cost adjustment
- [ ] Purchase invoices typed by hand on the Invoices page still take a number from the INV- series (bills from POs don't)

### E4 Reorder → purchase orders in bulk ✅
- [x] **Reorder** page (Purchase → Reorder) like Cin7's: suppliers with products to reorder and how many (plus *No supplier*), the products of all or one supplier with available, on order, minimum, **Cin7's suggested quantity** (reorder quantity, else up to the minimum — decision 5), supplier SKU and cost (supplier price, else cost price)
- [x] Tick products (kept across pages and suppliers), change quantities, choose the warehouse → **one draft PO per default supplier** (`POST /reorder/purchase-orders`), each line with the supplier's code, price and VAT as on any new PO; products without a supplier are refused by name; links to the new POs
- [x] `GET /reorder` (supplier filter, search, paging) and `GET /reorder/suppliers` reuse the dashboard's reorder query (one rule, one count: 429 on the real data)
- [x] Tests: `ReorderTest` (3) — per-supplier list and suggestions, two suppliers → two draft POs now on order, no-supplier refused (127 backend tests); E2E RO-001 (74 cases)

### Next
- [ ] E5 FIFO cost layers (incl. bill price → stock cost)

---

## Phase 7 — Shopify integration 🔄

> Goal: Shopify orders flow into the ERP; fulfilments, cancellations, returns and stock flow back.

### 7.1 Connection ✅
- [x] **Settings → Integrations** page (one card per integration, with status) → **Shopify** page: store address, client ID, client secret; **Save & test connection**, **Test connection**, change credentials, disconnect
- [x] Uses a Shopify **Dev Dashboard app** with the **client credentials grant** (legacy admin-created custom apps can't be made since 2026-01-01). Access tokens last 24 h; cached and renewed 10 minutes before expiry (`ShopifyConnectionService.accessToken()`)
- [x] `shopify_connections` (`V6`): one store per tenant; a shop domain can belong to only one tenant (also the lookup for webhooks later)
- [x] Client secret and access token encrypted with AES-256-GCM using **`APP_ENCRYPTION_KEY`** (separate from the JWT key; `.env`, `.env.example`, docker-compose, CI). The secret is never returned by the API; an empty secret on update keeps the saved one
- [x] Only `*.myshopify.com` addresses accepted (also accepts the handle or the admin URL); readable errors for wrong client ID / secret, store in another organization, unknown store
- [x] Reports granted scopes and any missing ones; ADMIN only; connect / update / test / disconnect audited (`SHOPIFY`)
- [x] Verified against the real dev store **expertsafety-dev.myshopify.com**: Connected, all required scopes granted, Admin API 2026-10
- [x] Tests: `SecretCipherTest` (3), `ShopifyConnectionServiceTest` (7, local fake Shopify — no network); E2E SHP-001…SHP-003 (45 cases total)

### 7.2 Order import ✅
- [x] **Webhooks** `orders/create`, `orders/updated`, `app/uninstalled` → `POST /api/webhooks/shopify` (public). HMAC-SHA256 signature checked with the tenant's client secret; tenant found from `X-Shopify-Shop-Domain` by `ShopifyWebhookTenantFilter` (before the request's DB session opens)
- [x] Every delivery stored first (`shopify_webhook_events`, `V7`); repeats of a processed event are skipped; failures answer 500 so Shopify retries; events listed on the Shopify page
- [x] **Catch-up import** (GraphQL, last 7 / 30 / 60 days) — same import path, safe to repeat
- [x] Shopify order → sale order: `source=SHOPIFY`, Shopify id + number (`#1001`), unique per tenant (no duplicates even when Shopify sends `updated` before `create`); paid → CONFIRMED, unpaid → DRAFT (confirmed once Shopify reports it paid); line prices after discounts
- [x] **Needs review**: unknown / missing SKU or non-GBP currency → DRAFT with the reason on the order; "Confirm as it is" clears it (audited); once an order leaves DRAFT, Shopify updates no longer change it (7.3 / 7.4)
- [x] Customers matched by Shopify id, then by contact email, else created (source Shopify, contact with email / phone)
- [x] Imports and webhook processing audited as actor `INTEGRATION / shopify`
- [x] Settings on the Shopify page: warehouse that ships Shopify orders, public address, **Register webhooks** (replaces our older subscriptions, keeps others), **Import orders**, recent deliveries; Shopify errors shown with Shopify's reason (HTTP 502), not "Internal Server Error"
- [x] Development: `make tunnel` = Cloudflare quick tunnel → nginx gateway (`infra/webhook-gateway.conf`) that only passes `POST /api/webhooks/shopify`; the rest of the API, incl. login, is never public. Docker profile `shopify-dev`, not started by `make up`
- [x] Shopify app setup needed: **protected customer data** (Name, Email, Phone, Address; reason Store management) — required for order webhooks, no review for dev stores
- [x] Verified with a real checkout on expertsafety-dev (test gateway): order #1001 arrived via webhook within a second, imported as DRAFT / needs review (sample product not in the ERP), customer created from Shopify
- [x] Tests: `ShopifyWebhookTest` (8), `ShopifyConnectionServiceTest` +4 (import, webhook registration, refused topic, public address); E2E SHP-004…SHP-008 (50 cases total)

### 7.3 Fulfilment back to Shopify ✅
- [x] Shipping a Shopify order in the ERP creates a **Shopify fulfillment** (`fulfillmentCreate`, `notifyCustomer: true`): Shopify marks the items fulfilled and emails the customer
- [x] Optional **carrier + tracking number** on the ship form, stored on the shipment and passed to Shopify
- [x] Imported lines keep Shopify's line item id (`V8`); each shipped quantity is allocated over the order's open fulfillment-order lines — partial shipments become separate fulfillments; items already fulfilled in Shopify are skipped with a note
- [x] Sent **after the ERP transaction commits** (`FulfilmentCreatedEvent` + after-commit listener): the shipment and stock movement stand even if Shopify is down
- [x] Outcome on each shipment: Sending… / ✓ Sent to Shopify / ✕ Not sent (reason) + **Retry** (`POST /sale-orders/{id}/fulfilments/{fid}/shopify-sync`); never sent twice; audited as `SHOPIFY_FULFILLMENT` by `INTEGRATION / shopify`
- [x] Phone and address taken from the order's shipping address when the customer record has none
- [x] Verified with a real order: #1003 shipped in the ERP with tracking → Shopify fulfillment created within a second
- [x] Tests: `ShopifyFulfilmentSyncTest` (4, fake Shopify with fulfillment orders), webhook test for shipping address; E2E SHP-009 (51 cases total)
- [x] Fixed along the way: browser autofill put an email into the Carrier field (autofill off); the Shopify page now names the warehouse actually used when none is chosen (the oldest), not the alphabetically first

### Product catalogue in Cin7 format ✅
- [x] Products follow Cin7 Core's **InventoryList** CSV (`V10`): searched / filtered / synced fields are columns (status ACTIVE / DEPRECATED, type STOCK / SERVICE, brand, barcode, trade price, reorder quantity, default supplier, supplier product code, product family + up to 3 variant options); the rest of Cin7's data is one JSONB `details` column, so a new field needs no migration
- [x] **Import** (Cin7 InventoryList CSV, ~15,000 rows) with a per-row error report, option to deprecate active products missing from the file; **Export** in the same format (re-importable)
- [x] Products page: search on SKU / name (`pg_trgm` index), status filter (deprecated hidden by default), full edit form; dropdowns use a light `/products/options` list
- [x] Real data: the user's Cin7 file (15,566 rows, 13,974 active = Cin7's count) imported into the local dev database only — never committed
- [x] Tests: `ProductImportServiceTest` (7); E2E PRD-003…PRD-007

### Product sync ERP → Shopify ✅ (price-change check still to do)
- [x] **Sync products to Shopify** on the Shopify page (`V9`: `sell_price`, `shopify_product_links`): a product family = one Shopify product with variants (options by name), single products = one product; new products created as **DRAFT**; £0.00 when no price; deprecated products removed (ERP-made) or archived (made in Shopify)
- [x] Runs in the background with progress in 3 steps (sending, removing, stock), 5 requests in parallel; unchanged products are skipped
- [x] Real run on expertsafety-dev: 2,650 Shopify products / 13,959 SKUs created, 1,193 old drafts removed, 0 failed; storefront shows variants (e.g. Wonder Grip glove sizes)
- [ ] To check: a price change updates only that product (edit GLGH-WG-310RP-08 → sync → expect 1 updated) — local price already set to £4.50
- [x] UI moves: Shopify webhook deliveries → Audit Log tab **Shopify webhooks** (admins); **Import recent orders** → Sale Orders page
- [x] Tests: `ShopifyProductSyncTest` (8, fake Shopify); E2E SHP-010, SHP-011, AUD-002 (59 cases, 77 backend tests at the time)

### Next
- [ ] 7.4 cancellations / returns · 7.5 reliability (retry failed events, reconciliation, background processing) · 7.6 stock sync
- [x] ~~Stock on hand from Cin7~~ ✅ — see Phase C (still to do: push stock to Shopify, 7.6)

---

## Cross-cutting (done throughout)

- [x] **JWT authentication** — signing key from `APP_JWT_SECRET` (base64, ≥ 32 bytes; local value in git-ignored `.env`, see `.env.example`). Tokens survive restarts; the backend refuses to start without a valid key. Expiry 24 h
- [x] **401 → redirect to login** — axios interceptor + `AuthContext` validates token on mount via `/auth/me`
- [x] **402 → plan-limit-exceeded event** — dispatched globally, components can listen
- [x] **Server-side pagination** — all list endpoints return `Page<T>`; frontend uses `Pagination` component
- [x] **Category management** — products can be categorised; filter by category on products + stock overview
- [x] **Docker multi-stage build** — compiles Java inside Docker; `make up` = `docker compose up --build`
- [x] **DataSeeder** — seeds plans, default tenant, admin user, opening stock on first run

---

## API surface (backend base path `/api`)

| Method | Path | Description |
|---|---|---|
| POST | `/auth/login` | Login, returns JWT |
| POST | `/auth/register` | With `organizationName`: new org + its ADMIN (requested role ignored). Without: ADMIN adds a user to their tenant (`role` ADMIN/MANAGER/STAFF; SUPER_ADMIN only by a SUPER_ADMIN) |
| GET  | `/auth/me` | Validate token, return current user |
| GET  | `/auth/users` | List users in tenant (ADMIN) |
| GET/POST | `/products` | List / create products |
| GET/PUT/DELETE | `/products/{id}` | Get / update / delete product |
| GET  | `/products/options` | Light product list for dropdowns (`status`) |
| POST | `/products/import` | Import a Cin7 InventoryList CSV (`deprecateMissing`) |
| GET  | `/products/export` | Export products as a Cin7 InventoryList CSV |
| POST/GET | `/integrations/shopify/products/sync` | Start / follow the product sync to Shopify (ADMIN) |
| GET/POST | `/warehouses` | List / create warehouses |
| GET/PUT/DELETE | `/warehouses/{id}` | Get / update / delete warehouse |
| GET/POST | `/categories` | List / create categories |
| GET/POST | `/suppliers` | List / create suppliers |
| GET/PUT/DELETE | `/suppliers/{id}` | Get / update / delete supplier |
| GET/POST | `/customers` | List (search, paginated, with order counts) / create customers |
| GET/PUT/DELETE | `/customers/{id}` | Get / update / delete customer (delete refused if it has orders) |
| POST | `/customers/import` | Import a Cin7 Core customer CSV (multipart `file`; ADMIN/MANAGER) |
| GET/POST | `/stock-movements` | List / record movement |
| POST | `/stock-movements/transfer` | Transfer between warehouses |
| GET  | `/stock-movements/export/csv` | CSV export |
| GET  | `/stock/overview` | Stock levels (paginated, filterable by category) |
| GET/POST | `/purchase-orders` | List / create POs |
| POST | `/purchase-orders/{id}/receive` | Receive PO → IN movements |
| POST | `/purchase-orders/{id}/cancel` | Cancel PO |
| GET/POST | `/sale-orders` | List (optional `customerId` filter) / create SOs (`customerId`, or `customerName` to reuse/create a customer) |
| POST | `/sale-orders/{id}/confirm` | DRAFT → CONFIRMED |
| POST | `/sale-orders/{id}/fulfill` | Ship given line quantities (no body = all remaining) → SALE_OUT movements |
| POST | `/sale-orders/{id}/cancel` | Cancel SO, or cancel remaining quantities if partly shipped |
| POST | `/sale-orders/{id}/returns` | Record customer return → SALE_RETURN_IN for restocked lines |
| GET/POST | `/invoices` | List / create invoices |
| GET | `/invoices/{id}` | Get invoice |
| POST | `/invoices/{id}/issue` | DRAFT → ISSUED |
| POST | `/invoices/{id}/pay` | Record payment |
| POST | `/invoices/{id}/cancel` | Cancel invoice |
| GET/PUT/DELETE | `/integrations/shopify` | Shopify connection: view (no secrets) / save + test / disconnect (ADMIN) |
| POST | `/integrations/shopify/test` | Fetch a fresh token and read the shop |
| PUT  | `/integrations/shopify/settings` | Warehouse for Shopify orders, public address for webhooks |
| POST | `/integrations/shopify/webhooks` | Register order webhooks at the public address |
| POST | `/integrations/shopify/import?days=` | Catch-up import of recent orders |
| GET  | `/integrations/shopify/events` | Latest webhook deliveries |
| POST | `/webhooks/shopify` | Shopify webhooks (public; HMAC-signed) |
| POST | `/sale-orders/{id}/fulfilments/{fid}/shopify-sync` | Send a shipment of a Shopify order to Shopify again |
| GET  | `/dashboard` | Dashboard figures for `from`–`to`, or the last `days` days (default 30) |
| POST | `/sale-orders/{id}/invoices` | Invoice (part of) an order: quantities per line, or everything not invoiced yet |
| PUT  | `/sale-orders/{id}` | Change a quote or draft order (not Shopify ones) |
| POST | `/sale-orders/{id}/convert` · `/reject` | Quote → draft order · quote rejected |
| POST | `/sale-orders/preview` | The order as it would be created (defaults, VAT, totals); nothing saved |
| GET/PUT | `/settings/organisation` | Order numbering, Shopify sales rep, default sale tax rule (PUT: ADMIN) |
| GET  | `/tax-rates` | The organisation's VAT rates (`usage` SALE / PURCHASE) |
| GET  | `/dashboard/reorder` | Products whose available stock + on order is at or below their minimum (`search`, `page`, `size`) |
| POST | `/stock/import` | Cin7 stock file → stock in one warehouse set to the file's OnHand (`warehouseId`, `apply=false` previews) |
| GET  | `/audit-log` | Audit log (filterable) |
| GET  | `/tenants` | List tenants (SUPER_ADMIN) |
| PUT  | `/tenants/{id}/toggle` | Enable/disable tenant (SUPER_ADMIN) |
| GET  | `/subscription` | Current subscription + usage |
| GET  | `/plans` | All available plans |
| POST | `/subscription/upgrade` | Switch plan (ADMIN) |

---

## What to build next

Agreed order (each phase is done when its feature works, its tests are written, all existing tests pass, then this file is updated):

1. ~~**Persistent JWT / secret key**~~ ✅ done — see Cross-cutting
2. ~~**Phase 8 — Customers**~~ ✅ done — see Phase 8 (Cin7 layout + CSV import)
3. ~~**Phase 6 — Dashboard**~~ ✅ done — see Phase 6; brought to the functional spec in steps: ~~6.1 period, status counts, reorder, top/worst sellers~~ ✅ → 6.2 sales rep, VAT, bills due/overdue → 6.3 PO part receipts + supplier bills, revenue vs expense by month. Full plan: [`docs/roadmap.md`](docs/roadmap.md)
4. **Phase 7 — Shopify integration** 🔄 (~~7.1 connection~~ ✅ → ~~7.2 order import~~ ✅ → ~~7.3 fulfilment~~ ✅ → 7.4 cancellations/returns → 7.5 reliability → 7.6 stock sync). Shopify access tokens get their own encryption key, handled like `APP_JWT_SECRET`
5. Then: Stripe billing, notifications, custom fields (former Phase 4), advanced inventory, purchasing, reporting; production hardening throughout
