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

## Phase D — Sales (spec §2) ✅

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

### List actions like Cin7 ✅
- [x] Sale order and purchase order lists: tick rows (or all on the page), then **Undo** (Cin7's warning "This will change the task status back to draft…"; a confirmed order / ordered PO goes back to draft, refused once shipped / received or invoiced / billed), **Fill and authorise** (draft → confirmed / ordered), **Export** (CSV of the ticked rows), **Void** (cancel); **Print** since D5; Email greyed out until e-mail sending
- [x] Each row is done on its own: the result says how many were done and why the others were not
- [x] Progress icons: a step that will not happen is **Not available** (no quote step on an order made directly; steps after a cancel / rejection), not "not yet"
- [x] `POST /sale-orders/{id}/undo`, `POST /purchase-orders/{id}/undo`; tests: `QuoteTest`, `PurchaseOrderTest` (130 backend tests); E2E SO-014, PO-003 (76 cases)

### UI like Cin7: compact lists, product lines, stock availability ✅
- [x] **U1 compact lists** (every list page): one line per row, long text cut with "…" and shown in full on hover (`Clip`), numbers right-aligned, tighter rows (`.list-table`, `ListHead`); no more "Open ›" links on sales / purchases / invoices (the number is the link); customer contact and terms on one line
- [x] **U2 product lines** on sale order, PO and invoice pages: "SKU: name" on one line, right-aligned numbers, a **Total** row (quantity and value) and **Export** (CSV), as in Cin7; cancelled / returned columns only when used
- [x] **U3 stock availability** (Stock Overview, Cin7's Product availability): category, product, warehouse, min, **stock value** (on hand × cost price), on hand, **allocated**, available, **on order** (ordered POs not yet received) and **next delivery** (earliest required-by); filters **Only active** and **Show only non-zero quantities** (`GET /stock/overview?activeOnly=&nonZero=`)
- [x] Tests: `PurchaseReceivingTest` +1 (131 backend tests); E2E STK-005, tests now use test ids instead of column positions (77 cases)

### D5 Documents (PDF) ✅
- [x] **PDFs** for **quote**, **sales order**, **invoice**, **credit note** and **purchase order** (`GET /sale-orders/{id}/pdf`, `/invoices/{id}/pdf`, `/purchase-orders/{id}/pdf`), built from the same responses as the screens so the figures match: company block with logo, customer / supplier with address, contact and VAT number, dates and references, lines (SKU, supplier code on POs, quantity, price, discount, VAT rate, net) and charges / additional costs, **VAT summary per rate**, totals (paid and balance due on part-paid invoices), notes; **how to pay** with the bank details on invoices still to pay; footer with company no., VAT no. and the footer text; *Page x of y*; DRAFT / CANCELLED / PAID / EXPIRED stamped; a supplier's bill is not printed (their document)
- [x] One XHTML template (`templates/documents/document.html`) for all, rendered by OpenHTMLtoPDF with **Liberation Sans** embedded (Lato was tried and dropped: its "ti" ligature broke copy / search in the PDF text); headings repeat on every page
- [x] **Company details** (Settings → Organisation, `GET/PUT /settings/company`, `V20`): name on documents, address, phone, e-mail, website, VAT number, company number, bank details, footer text, **logo** (PNG / JPEG ≤ 512 KB, checked by content; `POST/DELETE/GET /settings/company/logo`); admin only, audited
- [x] **Print** on the sale order (*Print quote* on quotes), invoice and PO pages (opens the PDF in a new tab); list **Print** on ticked sales / purchases now works — one PDF with all of them in list order (`GET /documents/{kind}/pdf?ids=`)
- [x] Tests: `DocumentTest` (6) — invoice with company, customer, VAT and how to pay (4 × £10 + £5 carriage = £54.00), credit note, quote and draft order, PO with supplier codes and deliver-to, two orders in one PDF, logo checks (148 backend tests); E2E DOC-001, SO-014 now expects Print enabled (80 cases)
- [ ] Later: e-mailing documents (with the e-mail integration, spec §7); a delivery address on sale orders (Cin7 has billing / shipping addresses; Cin7's customer export has none, so documents print the customer's address); editable templates (phase I)

---

## Phase E — Purchasing (spec §3) ✅

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
- [x] Stock cost following the bill price (decision 3) — done in E5 as a cost adjustment
- [ ] Purchase invoices typed by hand on the Invoices page still take a number from the INV- series (bills from POs don't)

### E4 Reorder → purchase orders in bulk ✅
- [x] **Reorder** page (Purchase → Reorder) like Cin7's: suppliers with products to reorder and how many (plus *No supplier*), the products of all or one supplier with available, on order, minimum, **Cin7's suggested quantity** (reorder quantity, else up to the minimum — decision 5), supplier SKU and cost (supplier price, else cost price)
- [x] Tick products (kept across pages and suppliers), change quantities, choose the warehouse → **one draft PO per default supplier** (`POST /reorder/purchase-orders`), each line with the supplier's code, price and VAT as on any new PO; products without a supplier are refused by name; links to the new POs
- [x] `GET /reorder` (supplier filter, search, paging) and `GET /reorder/suppliers` reuse the dashboard's reorder query (one rule, one count: 429 on the real data)
- [x] Tests: `ReorderTest` (3) — per-supplier list and suggestions, two suppliers → two draft POs now on order, no-supplier refused (127 backend tests); E2E RO-001 (74 cases)

### Purchase list: General and Accounting tabs, like Cin7 ✅
- [x] **General** (one row per PO): progress icons *ordered → received → billed → paid* (same strip as the sales list, shared `StageIcons`), **Documents** column with the supplier bill numbers; the list loads the page's bills in one query
- [x] **Accounting** (one row per supplier bill, Cin7's purchase ledger view): supplier's invoice number, PO, status, from, date, due date, **overdue by**, total, paid, **due**; filters all / awaiting payment / **overdue** / paid / draft / cancelled (`GET /invoices?type=PURCHASE&overdue=true`); "doesn't match the PO" shown on mismatched bills
- [x] Tests: `SupplierBillTest` +1 (overdue filter, bills on the PO list; 128 backend tests); E2E PO-002 checks the icons and the Accounting tab (74 cases)

### Product page and More actions, like Cin7 ✅
- [x] **Product page** `/products/{id}`: sections on the left as in Cin7 — **General** (incl. costing method FIFO, tax rules, tags, family / variant, description, notes, dimensions), **Prices** (cost, shop, trade, tiers 3–4 with margin), **Stock** (per warehouse: on hand, reserved, available; **on order** with the open POs), **Suppliers**, **Reorder levels** (with "on the reorder list"), **Movements**, **Activity log**; Edit (back to the page once saved), More actions → Delete
- [x] Products list: tick boxes; clicking a row / SKU opens the product; **More actions** (Cin7): Edit (one ticked), Delete (ticked), **Delete deprecated products**; the per-row Edit / Delete buttons are gone
- [x] Backend: `GET /products/{id}/stock`; product create / update / delete are **audited** (only the fields that changed) and `GET /audit-logs?entityId=` filters one record; deleting a product with stock movements, orders or invoices is refused ("mark it deprecated"); `POST /products/delete-deprecated` deletes only deprecated products without history and says how many were kept
- [x] Tests: `ProductPageTest` (3) (134 backend tests); E2E PRD-003 via More actions, PRD-008 product page (78 cases)
- [ ] Later (Cin7 has them, not needed yet): **Combined view** (one row per product family — 1,399 families locally, but Cin7's are mostly single-variant), alternative products, custom prices, images, extra units, channels, Merge products

### E5 FIFO cost layers ✅
- [x] `V19`: **cost layers** (product, warehouse, received at, quantity, left, unit cost now / when it came in), **consumptions** (what each stock-out took, at what cost) and **cost adjustments**; movements carry their order line (`source_line_id`, existing ones matched on order number + product)
- [x] Every stock-in opens a layer: receipt = PO line cost after discount; adjustment / stock import = the cost given (new **Unit cost** field on *Record movement*), else the product's cost price; restocked return = what the line's shipments cost per unit. Every stock-out takes from the **oldest layers first**; a **transfer** moves the layers to the other warehouse at their cost and date
- [x] **Bill price ≠ PO price** (decision 3): the issued bill's lines are matched to the PO line's received units (oldest first; units billed ahead of the goods are priced when they arrive) and the difference is spread by **cost adjustments** — the part still in stock changes the stock value, the part already shipped changes those shipments' COGS (passed on through transfers); cancelling the bill adds the opposite adjustments; drafts change nothing until issued. Bill charges stay an expense (decision 4: no landed cost)
- [x] **COGS** per sale order line and order (shipments + later corrections − restocked returns); line **margin %** now over the FIFO cost of what shipped (cost price for the rest)
- [x] **Stock value** from the layers: stock overview (per product / warehouse), product page, dashboard KPI
- [x] **Rebuild**: layers are built by replaying the movements and bills in order — at start-up for organisations with movements but no layers (real data: 2,132 movements → 2,129 layers in ~2 s, stock value **£99,851.54** vs £99,367 on cost price; 2 old test sales that went below zero costed at the cost price) and on request (`POST /costing/rebuild`, admin)
- [x] Frontend: product page Stock → **Cost layers (FIFO)** (received, warehouse, from, came in, left, unit cost with "was £x" after a bill correction, value); sale order totals **Cost of goods shipped** (and per line on the margin); bill totals **Stock cost adjustment**
- [x] Tests: `FifoCostingTest` (8) — 10 at £5 + 10 at £6, ship 15 → **COGS £80**, £30 left; bill at £5.50 after 4 shipped → +£2 COGS, +£3 stock, cancel takes it back; bill ahead of the goods; draft bill; transfer then bill; restocked return; adjustment costs; rebuild gives the same figures (142 backend tests); E2E FIFO-001 (79 cases)
- [ ] Later: supplier credit notes on bills don't correct the cost yet; landed cost (charges into stock cost) only if wanted (decision 4)

---

## Phase 7 — Shopify integration ✅

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

### 7.5 Reliability ✅ (phase F)
- [x] Webhooks are **answered as soon as they are stored** (200) and run in the background (`ShopifyEventProcessor`, pool of 2–4); a failure no longer answers 500, so Shopify never drops a subscription that keeps failing — the ERP retries instead
- [x] **Retries** (`V22`: attempts, next try, lock): 1 min, 5 min, 15 min, 1 h, 4 h, 12 h, 24 h, then given up (8 runs); a worker runs due ones every 30 s and picks up events stored but never run (backend stopped). Each run first takes the event, so two workers or a worker and a person never run it together
- [x] Audit Log → **Shopify webhooks**: tries, next try / given up, **Failed only**, **Retry** (`POST /integrations/shopify/events/{id}/retry`)
- [x] **Daily check** at 03:15 for every connected organisation, or **Check now** (`POST /integrations/shopify/reconcile`): webhook subscriptions Shopify removed are made again; orders created or changed since the last check (1 h overlap, at most 60 days) are imported — safe to repeat
- [x] **Health** card on the Shopify page (`GET /integrations/shopify/health`): last webhook, last 24 h handled / failed, retrying, failed for good, last check and its result; warnings when the connection, webhooks or the daily check need attention
- [x] Tests: `ShopifyWebhookTest` +3 (retry schedule and give-up, success on retry and Retry by hand, stranded event), `ShopifyConnectionServiceTest` +2 (daily check, look-back window); E2E SHP-008 now: answered 200, retried, imported by Retry once a warehouse exists

### 7.4 Prices with VAT, cancellations and refunds ✅ (phase F)
- [x] **Shopify prices include VAT** (business-rules.md §5): each imported line gets the ERP's VAT rule (product → customer → default) and a net price whose net + VAT is what Shopify charged (`VatIncluded`); **shipping** comes in as a charge (it was dropped before). The ERP total is checked against Shopify's: up to 5p of VAT rounding (about 1 amount in 6 cannot be split exactly with VAT per line) becomes a visible *Rounding to Shopify's total* charge at 0 %; a bigger difference puts the order up for review
- [x] **Cancelled in Shopify before anything shipped** → the ERP order is cancelled and its stock freed (also after it was confirmed). After shipping: left as it is (later)
- [x] **Refunds wait for review** (`V23` `shopify_refunds`): every refund in an order's data (webhook or the daily check; also on confirmed / shipped orders) is kept on the order — amount, note, items with Shopify's restock type, amount beyond the items. Nothing is credited by itself
- [x] On the order: **Apply in the ERP** — invoiced items get a **credit note** on their invoice (ticked items **back in stock**, ticked by default when Shopify says *sent back*), items not invoiced and not shipped are **cancelled** on the order, shipping / other money is credited as a charge; the credit note is set against what is still due, else recorded as paid back through **Shopify** (new payment method). **Dismiss** needs a reason. Who / when / what is kept; audited
- [x] Shopify page: refunds waiting for review, each linked to its order; Health warns while any wait
- [x] Tests: `ShopifyRefundTest` (6) — £34.92 order with shipping = £34.92 in the ERP (2p rounding), every amount 1p–£30 splits exactly or 1p under, £50 vs £25 needs review, cancel before / after shipping, refund → credit note with restock (seen twice, kept once), refund before shipping → quantity cancelled, goodwill dismissed; E2E SHP-012
- [ ] Orders imported before this keep their old lines (prices with VAT, no VAT rate): only new imports are split

### 7.6 Stock to Shopify ✅ (phase F)
- [x] Shopify shows each product's **available stock over all warehouses** (on hand − reserved, whole units, never below 0; business-rules.md §5), set as Shopify's **available** quantity — so Shopify does not take its own unshipped orders off a second time (the product sync used to set *on hand* of one warehouse)
- [x] `ShopifyStockSync` (`V24`: what Shopify was last given per product, the store's location): compares and sends **only what changed**, in batches of 100 (a refused batch is sent item by item so the bad one names itself). Nothing has to call it when stock moves: a worker runs it **every 20 s**, so receipts, shipments, adjustments, transfers, returns, imports and orders confirmed / cancelled all reach Shopify without hooks that could be missed; **everything again every night** (03:45) and with **Send all stock now** (`POST /integrations/shopify/stock/sync`); the product sync sends stock the same way
- [x] Health card: last stock run and result, products still to send, warnings when Shopify refused some; product page Stock tab: what Shopify shows and when it was sent (`GET /products/{id}/shopify-stock`)
- [x] `APP_SHOPIFY_SCHEDULER_ENABLED` (docker-compose, `.env.example`): false on a development machine connected to a real store, so retries, the daily check and stock are only sent when asked on the page
- [x] Tests: `ShopifyProductSyncTest` +1 (10 + 4 in two warehouses = 14; nothing moved → nothing sent; 3 reserved → only that product, 11; more reserved than on hand → 0), product sync now sets *available*; E2E SHP-008 checks the stock line
- [ ] Not yet run against the real dev store (it would set stock for its 13,959 linked SKUs)
- [x] ~~Stock on hand from Cin7~~ ✅ — see Phase C (still to do: push stock to Shopify, 7.6)

---

## Phase G — Customers and pricing (spec §5) ✅ (G1 ✅ G2 ✅ G3 ✅ G4 ✅)

### G1 Price tiers and contract prices ✅
- [x] **Price tiers** per organisation (`V25` `price_tiers`), numbered like Cin7's PriceTier1..10 and named in Settings → Organisation → **Price tiers** (ADMIN): tiers 1–4 use each product's own price for the tier (Cin7's PriceTier1–4 columns: sell price, trade price, tier 3, tier 4; a product without one gets tier 1), or a **percentage off tier 1**; tiers 5–10 must be a percentage. Seeded *1 Retail* and *2 Contract* (the name the real Cin7 customers use: 191 Contract, 141 Retail) or *Trade*; renaming a tier renames it on its customers; a tier in use cannot be removed
- [x] **Contract prices** (`customer_prices`): one customer, one SKU, a net price from a date, until a date or open; periods for one SKU never overlap; on the customer page with the tier price beside it, **End today**, delete (ADMIN); MANAGER and above change them; audited on the customer
- [x] **An order line without a typed price** is priced by the server: contract price valid on the day → the customer's tier → Retail; no customer discount on top of a contract price; each line keeps **where its price came from** (`price_source`: Contract price / Contract tier / Retail / Typed), shown on the order and as the price placeholder on the order form (which no longer copies the shop price in). A product with no price stays without one, as before
- [x] Customer form: the price tier is a list of the organisation's tiers (a Cin7 name that is not a tier stays visible)
- [x] **Price tier on the sale order**, like Cin7's header (`V26`): the customer's tier by default, changeable per order on the form; lines without a typed price follow it at once (the customer's contract prices still win); kept on the order and shown on its page
- [x] Tests: `PricingTest` (5) — order tier chosen / changed / unknown; tier price, Retail fallback, typed price kept; contract price wins only while valid, no discount; overlapping periods refused; percentage tier, rename moves customers, tier in use kept; E2E PRICE-001
- [ ] To confirm in Cin7 (read-only): that PriceTier1 = Retail and PriceTier2 = Contract (assumed from the data: tier 1 is the shop price)

### G2 Credit control ✅
- [x] A customer is **on credit hold** (`CreditService`) when put on hold by hand (with a reason), when what they owe (issued invoices less payments and unused credit notes) plus their orders not invoiced yet (confirmed or shipped) is **over the credit limit** (no limit = none), or when an **invoice is past its due date**
- [x] **Warning at order entry**: the order form (preview), a new order and the order page show the reasons, e.g. "£102.00 owed and on order with this order is £2.00 over the £100.00 credit limit"
- [x] **Confirming** a held customer's order is refused; a **manager or admin** confirms anyway with a reason (`V27` `credit_override`, shown on the order, audited `CREDIT_OVERRIDE`); bulk *Fill and authorise* stops at held customers; Shopify orders (paid at checkout) are not checked
- [x] Customer page **Credit** card: limit, owed, on order not invoiced, available, overdue (oldest, days), status; **Put on hold** / **Release** (MANAGER and above, `PUT /customers/{id}/credit/hold`, audited); customers list shows **On hold**
- [x] Tests: `CreditTest` (3) — £54 owed + £12 on order against £100, a £36 order warns and needs a manager (STAFF refused); an invoice 5 days overdue holds a customer with no limit; hold by hand needs a reason, blocks confirming, release; E2E CREDIT-001
- [ ] Later: shipping is not stopped for orders confirmed before the hold (overdue reminders: done in G3)

### G3 Statements and overdue reminders ✅
- [x] Cin7 checked (read-only, 2026-11-04): two statement templates, *Sale Statement Activity* and *Sale Statement Outstanding*; no aged-debtors report and no overdue reminders (Xero sends them)
- [x] **Sales ledger per customer** (`StatementService`): issued invoices and credit notes, money received, refunds; a credit note applied to an invoice counts once (as the credit note); drafts and cancelled left out
- [x] **Activity statement**: a period (default the last three months) with opening balance, each entry and a running balance, closing balance. **Outstanding statement**: what is open on a date (unpaid invoices, unused credit, days overdue). Both with the **ageing** (Current / 1–30 / 31–60 / 61–90 / over 90 days), which adds up to the closing balance
- [x] On the customer page (**Statement** card: Activity / Outstanding, dates, Print statement) and as PDF (`statement.html`, same letterhead as the other documents); ticked customers in one PDF from the Overdue list
- [x] **Overdue reminders** that get firmer: *Payment reminder* → *Second payment reminder* → *Final notice*. Preview (nothing recorded) or print and record (MANAGER and above, `V28` `reminder_count` / `last_reminder_at` per invoice, audited `OVERDUE_REMINDER`). Print only until e-mail sending exists
- [x] **Sale → Overdue** list: customers with overdue invoices, overdue amount, oldest, days, balance, last reminder, next reminder, on hold
- [x] Fixed (G2): a credit note bigger than what was still due on its invoice had its applied part counted twice in what the customer owes; credit control now uses the statement's balance
- [x] Sales invoices typed by hand can name their customer (`customerId`), so they show on the statement
- [x] Tests: `StatementTest` (3) — activity and outstanding agree, opening / running / closing balance, ageing, open items on a past date; partly applied credit note counted once; reminders list only overdue invoices, preview records nothing, 1st → 2nd → final, audited; E2E STATEMENT-001
- [ ] Local data: Expert Safety has no invoices yet (only customers, products and stock came from Cin7), so statements were checked on test data; e-mailing statements and reminders comes with e-mail sending

### G4 Billing and delivery addresses ✅
- [x] Cin7 checked (read-only, 2026-11-04): the customer **Addresses** tab is a list (Line 1, Line 2, City / Suburb, State / Province, Zip / Postcode, Country, Type Billing / Business / Shipping, Default for type); the sale order header copies a billing and a shipping address, with Ship to company, Ship to contact and Shipping notes. The customer export CSV has no addresses
- [x] **Address book per customer** (`V29` `customer_addresses`): any number, one default per type (the first ticked, else the first); edited on the customer form, shown on the customer page; saving a customer without addresses (CSV import) keeps them. The old one-text address became the default billing address (its first line as line 1)
- [x] **Addresses on the sale order**: billing and shipping copied from the customer's defaults (shipping falls back to billing), changeable per order (pick a saved address or type a one-off), plus Ship to company and Ship to contact; kept when the customer's addresses change later; orders made before V29 got the customer's address
- [x] **Shopify orders** deliver to Shopify's shipping address (with the name as Ship to contact); new Shopify customers get it as their default Shipping address
- [x] **Printed**: the order's billing address on quotes, orders and invoices, and a **Deliver to** block (company, Attn: contact, shipping address)
- [x] Tests: `AddressTest` (2) — one default per type, blank rows dropped, address book kept when not sent; order copies the defaults, one-off delivery address, kept when the customer moves, printed on order and invoice; Shopify and document tests moved to the address book; E2E ADDRESS-001
- [x] **Customers → Import CSV knows Cin7's three customer files** by their columns: the customer export, *CustomerAddresses* (each customer in the file gets the file's address book) and *CustomerContacts* (each customer in the file gets the file's contacts); names not in the ERP are reported, not created; *CustomPrices* (one SKU, one customer, one price, no dates in Cin7) become **contract prices from today with no end**: the same price again is left alone, a different one ends the old price yesterday (or replaces today's). Tests: `CustomerImportServiceTest` (+3)
- [x] **Import ▾ / Export ▾ menus** on Customers, like Cin7: Customers, Addresses, Contacts, Custom prices. An import checks the file is the type chosen (e.g. the addresses file under Customers is refused: "choose Import → Addresses"), nothing imported; each export is in Cin7's own columns (`GET /customers/export?type=`) and imports back the same. Tests: `CustomerImportServiceTest` (round trip of all four), E2E ADDRESS-002
- [x] Real data (2026-11-04): CustomerAddresses_2026-11-04.csv loaded, 781 addresses for 307 customers, no warnings (customers with no default of a type got their first one as default); CustomPrices_2026-11-04.csv loaded, 561 contract prices for 45 customers, no warnings (e.g. AE Yates' CUST-AEYEVO3BLACK now prices at £11.40, *Contract price*)


---

## Phase H — Finance and accounting (spec §6) ✅ (H1 ✅ H2 ✅ H3 ✅ H4 ✅ H5 ✅ CSV; the Xero API link is next)

### H1 Sales ledger ✅
- [x] Cin7 checked (read-only, 2026-11-04): an invoice's **Payments** are Account (e.g. *650: Current Account - RBS*), Reference, Date paid, Amount, with *Use customer credit*, *Allocate credit note* and a **Customer credit** section; the chart of accounts is **loaded from Xero** (accounting package = Xero): bank 650 / 652 / 653 / 654, 641 Cash, 643 Card Payments, 644 Paypal Payments, 802 Customer Credits
- [x] **Payment accounts** per organisation (`V30` `payment_accounts`: code, name, kind Bank / Card / Cash / PayPal / Shopify / Other, active, one default) in Settings → Organisation (ADMIN); one used by payments is switched off, not removed. Without accounts, a payment records only how it was paid
- [x] **Recording a payment**: amount, **date paid** (not in the future), **payment account**, **reference**; part payments as before. **Overpaying** a sales invoice: what is due goes on the invoice, the rest becomes **customer credit** (`customer_credits`); a bill or a refund cannot be more than is due
- [x] **Customer credit** card on the customer page: credit held, used, left; **+ Payment on account** (money before the invoice); a manager removes unused credit with a reason. On the invoice: **Use customer credit** (oldest first) and **Allocate credit note** (a credit note of the same customer kept as credit)
- [x] **Void a payment** entered by mistake: manager or admin, with a reason; out of every total, kept in `voided_payments` and shown on the invoice (*1 voided*), audited `PAYMENT_VOID`; the invoice (and a credit note it used) opens again. Shopify payments are refused (refund in Shopify)
- [x] The **statement and the credit balance** count customer credit once (received money is the credit; using it is not counted again)
- [x] **Sale → Aged debtors**: every customer owing or in credit on a day (as at), Current / 1–30 / 31–60 / 61–90 / over 90 days, credit, total, credit limit, on hold; totals; **Export CSV**
- [x] **Shopify orders' invoices are paid when issued** (the order is only confirmed once Shopify says it is paid): a Shopify payment with the order number as reference, on the Shopify payment account if there is one
- [x] Tests: `SalesLedgerTest` (6) — accounts (one default, a used one switched off, no duplicate codes); day / account / reference kept, overpayment → credit → used on the next invoice, payment on account, no future date; credit note allocated to another invoice, not to another customer's; void (STAFF refused, reason needed, invoice open again, audited); aged debtors; Shopify invoice paid on issue; E2E LEDGER-001
- [ ] **To test later with the live Shopify store**: Shopify payouts (money Shopify pays into the bank, less fees) are not matched to orders yet; the Shopify-paid invoice above is tested with a fake order only
- [x] Local data: Expert Safety's payment accounts entered as in Cin7 (650 Current Account - RBS default, 652, 653, 654, 641 Cash, 643 Card Payments, 644 Paypal Payments)


### H2 Purchase ledger ✅
- [x] Cin7 checked (read-only, 2026-11-04, PO-19064): the purchase page has **Payments** ("add a payment or refund to the invoice or credit note") and **Credit notes** ("create a new credit note and unstock items"); a bill's payments are Account, Reference, Date paid, Amount with **Use supplier deposit** (615 Supplier Deposits). No aged creditors report in Cin7 (Xero has it)
- [x] **Supplier credit notes** on a bill (*Supplier credit note* on the bill's page): the supplier's number (else the bill's + `-CR1`), date, quantities per bill line (at most what is not credited yet), extra amounts (e.g. a rebate), reason; settled by paying off the bill (default), a **refund from the supplier** (into a payment account, with a reference), or kept as credit
- [x] **Send back to the supplier**: the goods leave the order's warehouse (`V31` `PURCHASE_RETURN_OUT`, reference `PURCHASE_RETURN`) **at the cost they came in at**: that PO line's FIFO layers first, then FIFO; not more than received less already sent back
- [x] **Supplier deposits** (`supplier_credits`): *+ Deposit* on the purchase order (amount, date, payment account, reference), or the rest of an **overpaid bill**; **Use supplier deposit** and **Allocate credit note** on a bill; a manager removes an unused deposit with a reason. Payments to suppliers use the H1 payment accounts, dates, references and voids
- [x] **Purchase → Aged creditors**: what is owed to each supplier on a day, by days past the bills' due dates, credit notes and deposits, totals, **Export CSV**
- [x] A purchase order typed with a supplier name now uses that supplier (any case) or creates it, so its bills are on a supplier's ledger (orders made before stay as they are)
- [x] Tests: `PurchaseLedgerTest` (4) — a vest sent back costs this order's £65.50 not FIFO's £60.00, stock value £311.00, credit note pays off the bill, not more than billed; deposit + overpayment used on the next bills; kept credit note allocated, refund by the supplier; aged creditors; E2E PLEDGER-001
- [ ] Later: a supplier credit note for a price (no goods back) does not lower the stock's FIFO cost yet (as before E5's open item)


### H3 VAT return ✅
- [x] Cin7 checked (read-only, 2026-11-04, Settings → Tax rules, 17): active 20% (VAT on Income), 20% / 5% (VAT on Expenses), EC Acquisitions (20%) and (Zero Rated), 100% VAT, Full VAT, No VAT, Zero Rated Expenses / Income; deprecated 5% (VAT on Income), Exempt, Reverse Charge Expenses (20%) (at 0 %), VAT on Imports, Zero Rated EC Goods / Services
- [x] **Each tax rate has a VAT type** (`V32`: Standard, Reduced, Zero, Exempt, No VAT = outside the scope, Reverse charge, EC acquisition, Full VAT) and a **report rate**; existing rates typed by name; *EC Acquisitions (20%)*, *(Zero Rated)* and *Full VAT* added for organisations that lacked them
- [x] **Reverse charge** adds nothing to the bill (0 % on the line, as Cin7 shows it) and reports notional 20 % in box 1 and box 4; **EC acquisitions** notional in box 2 and box 4, net in box 9; **Full VAT** (import VAT, C79): the whole line is VAT, no net (bills, bill charges, PO costs, supplier credit notes)
- [x] **Finance → VAT return** (standard scheme: invoices, bills and credit notes by their date, issued or paid, drafts / cancelled left out): HMRC's **nine boxes** (1–5 in pence, 6–9 in whole pounds), *To pay HMRC* / *HMRC owes you*, net and VAT **by tax rate**, the **documents** behind it (links), quarter picker by VAT stagger (Mar/Jun/Sep/Dec, Jan/Apr/Jul/Oct, Feb/May/Aug/Nov) or custom dates, **Export CSV**. Filing stays in Xero (Making Tax Digital)
- [x] Menu: **Finance** (VAT return, Aged debtors, Aged creditors)
- [x] Tests: `VatReturnTest` (2) — rates know their types, reverse charge charges nothing; sale + zero-rated sale + credit note + bill with import VAT and reverse charge → boxes 1 £38.00, 4 £84.30, 5 £46.30 (refund), 6 £140, 7 £296; out-of-period and cancelled left out; E2E VAT-001
- [ ] Later: Cin7 has 5% (VAT on Income), Exempt and Reverse Charge deprecated; they are still active here (switch them off in Cin7's way if wanted). Cash accounting scheme not built (Expert Safety uses the standard scheme)

### H4 Sales profit and stock value ✅
- [x] Cin7 checked (read-only, 2026-11-06, Reports): **Sales Profit Summary** (rows Customer + Sales representative; Invoice, COGS, Profit, Gross margin; Sale = invoice − credit note ex tax; GM = (Sale − COGS)·100 / Sale, empty if either is 0; Markup; services and non-inventory left out), **Profit by Sales Representatives**, **Sales by Product Summary**, each a pivot with saved layouts; a **Show COGS** switch: *Only COGS within the date range* (default) or *All COGS*; **Inventory Movement Summary** (Location, SKU, Product, Unit: Quantity on hand, In, Out, Unit cost, Stock on hand for a period). Owner: "do the same as Cin7; if it is not best practice, note it and change later"
- [x] **Finance → Sales Profit Summary** (per customer or per order), **Profit by Sales Representatives** (per customer, per order, totals per rep), **Sales by Product Summary** (quantity, category filter): invoices less credit notes dated in the period (issued / paid), stock products only; Invoice (with VAT), Sale, COGS, Profit, Gross margin, Markup, Grand Total, search, Cin7's period modes, **Export CSV**
- [x] **Show COGS** like Cin7: *within the dates* = FIFO cost of goods shipped in the period less goods returned to stock, with bill corrections made in the period (a shipped order not invoiced yet shows cost and no sale, as in Cin7); *All COGS* = each invoice's cost (its order line's shipments per unit, the cost price if not shipped yet) and each credit note's goods taken back into stock (`V33`: a credit note keeps its return; the 44 local credit notes were linked by the backfill)
- [x] **Finance → Inventory Movement Summary**: per location and product, opening quantity and FIFO value, In, Out (quantity and value), quantity on hand, unit cost, stock on hand; location and category filters, search, CSV. Values from the cost layers on their dates, so any period works and its end equals the FIFO stock value (local data: £99,851.54, the dashboard's figure; one period's closing = the next one's opening)
- [x] Bill corrections rebuilt at start-up (or by *rebuild*) now keep the time of the bill / receipt, not the rebuild's (reports read their dates); local costs rebuilt once
- [x] Reports with cost and profit: **managers and admins** (menu items hidden for staff, API 403)
- [x] Tests: `SalesProfitTest` (3) — 5 helmets at £6 + 5 at £8, Abbey 4 × £10 shipped + invoiced, AE Yates 3 × £12 shipped not invoiced, 1 credited back: within dates Abbey sale £30 / COGS £18 / GM 40 % / markup 66.67 %, AE Yates COGS £22 and empty margin; All COGS one row; by rep and product; movement In £76 / Out £46 / closing £30 = FIFO stock value, next period opens at £30; bill at £7 → COGS +£1 and correction dated at the bill after a rebuild; E2E PROFIT-001
- [ ] Later (not like Cin7 yet): drag-and-drop pivot, more fields (paid, due, ship by, customer reference, location, tags, brand), saved layouts — phase I reports. Per-user report permissions like Cin7 (now by role) — phase I
- [ ] Later: a return without a credit note (goods back, no money) lowers COGS within the dates but is on no document for *All COGS*; Sales Overview, Customer Orders by Product and Stock Value Inventory Aging not built
- [ ] Local data has few sales (Cin7's sales history is not imported); check the profit figures against Cin7's for a month once orders are migrated (phase J)

### H5 Accounting export: Xero CSV ✅
- [x] Cin7 checked (read-only, 2026-11-06, Integrations → Xero, Chart of accounts, Account mapping): the chart of accounts and tax rules come **from Xero** (so our tax rule names are Xero's tax types); Cin7 exports contacts, items, authorised invoices, credit notes (not allocated), payments / refunds, prepayments, and **manual journals for COGS and stock adjustments**; one Xero document per Cin7 one (no consolidation), autosync every 2 hours (its status showed *Sync failed*). Account mapping: Inventory control 631, COGS 310, Tax liability 820, Customer credit 802, In-transit 633, Inventory discrepancy 310, Default revenue 200, Supplier deposits 615
- [x] **Finance → Xero export** (managers and admins): **account mapping** like Cin7's (`V34` `accounting_settings`, admins edit) and an **export start date**: nothing dated before it is exported (Xero already has what Cin7 sent: set it to the cutover day, after the opening stock import)
- [x] **Ready to export**: counts and the list of invoices, credit notes, bills, supplier credit notes, journals and payments not exported yet; **Export for Xero** makes a zip of Xero's import files and records each item as exported (`accounting_exported`, one row per document / journal / payment, never twice):
  - `SalesInvoices.csv` (Xero's template: contact, number, order number as reference, dates dd/mm/yyyy, item code, quantity, unit amount, discount %, revenue account, tax type = our tax rule name, tax amount); credit notes are the documents with negative lines
  - `Bills.csv`: stock lines on the inventory account (perpetual stock, like Cin7), charges on the purchase charges account; supplier credit notes negative
  - `ManualJournals.csv`, one journal per stock transaction and day like Cin7: shipment Dr COGS / Cr inventory at FIFO cost, restocked return the other way, stock adjustments against the discrepancy account, a bill's price difference on goods already sold Dr COGS / Cr inventory; each journal balances
  - `Payments.csv`: payments and refunds with account code and reference, to match in Xero (Xero has no payment import); allocations (credit notes, customer / supplier credit) left out
- [x] **Exports** history: download a batch again, **Undo** a batch Xero did not import (its items become ready again); **Changed after export**: documents cancelled or payments voided since, to put right in Xero by hand
- [x] Local data: Expert Safety's Cin7 mapping entered (631, 310, 310, 200, 802, 615; purchase charges 407 Import Duty as a placeholder); a test export from 01/10/2026 was made and undone, the start date left empty
- [x] Tests: `AccountingExportTest` (2) — nothing before setup; stock counted in, Portwest bill with carriage, sale + payment + credit note → files hold the invoice on 200, the credit note at −£10 / −£2 VAT, bill lines on 631 and 407, three balanced journals (£30 to 495, COGS £24, return £6), the £20 payment with its reference; nothing twice, re-download, undo and export again, a voided payment flagged; E2E XERO-001
- [ ] **To check on the first real import into Xero**: credit notes as negative lines in the invoice file, *Full VAT* (C79 import VAT) lines, penny differences where Xero recomputes discounted lines
- [ ] **Decide at cutover (phase J)**: the export start date. The local opening stock import of 30/10/2026 (£99,818.37) is a stock adjustment: exported, it would double the stock Xero already has from Cin7
- [ ] **Purchase charges account**: confirm which Xero account bill carriage / duty go to (407 Import Duty is a guess)
- [ ] Later: the **Xero API link** (contacts, invoices, bills, payments both ways, journals, retries) — needs a Xero app (client id / secret) from the owner; payments on account (customer credit / supplier deposits) not in the files yet; stock received but not billed is in the ERP's stock value but not on Xero's 631 until the bill comes (as in Cin7)


## Phase I — Reports, permissions, search, templates (spec §8) ✅ (I1 ✅ I2 ✅ I3 ✅ I4 ✅ I5 ✅)

### I1 Users and roles ✅
- [x] Cin7 checked (read-only, 2026-11-06, Settings → Users & Roles): users have one **role** (Expert Safety: an Owner and 5 users all on role *FULL*); a role is 256 ticked permissions in groups (Audit, CRM, Dashboard, Financials, Integration, Inventory, Purchase, Reports, Sale, Settings …) with *Full access* / *Read-only access* buttons; cost-related ticks: *Product - Prices*, *Product availability - View stock value*, *Purchase - View prices and totals*, *Sale - View profit margin*, *Sale - Change prices and discounts*, *Sale - Ignore credit limit*, report groups (Financial, Inventory, Purchase, Sale)
- [x] **Roles per organisation** (`V35` `access_roles`, names unique in any case): 23 permissions in Cin7's words for our modules (Sale, Purchase, Inventory, Reports, Dashboard, Audit); every organisation starts with **Manager** (all ticks) and **Staff** (no cost price, stock value, profit margin, credit limit / hold, contract prices, imports, financial reports, audit log); **admins have everything** (Cin7's owner); a user with no role keeps the defaults of their old Manager / Staff role, so nothing changed for existing users
- [x] **Admin → Users**: an *Access* column to give each user a role; **Roles** below like Cin7's: add / rename / remove (not while given to someone), ticks grouped by module, *Full access* / *Read-only access* buttons; all audited
- [x] **Enforced on the server**: one rules table (method + path → permissions, first match wins) checks every request (403 naming the missing permission); imports, credit hold, reminders, contract prices, financial reports and the Xero export use the permission instead of the old Manager role; the credit limit override is *Sale - Ignore credit limit*; Application Keys use their role's defaults; permissions are read on every request, so a role change applies at once
- [x] **Cost hidden** without the tick (sent as empty, not zero): product cost price (*Product - Prices: cost price*; such a user cannot change it either), stock value / cost layers / movement costs (*View stock value*), margins and cost of goods on sale orders (*Sale - View profit margin*), purchase order prices and totals (*Purchase - View prices and totals*); menus show only what the role allows
- [x] Platform → Organisations was slow with many organisations (5,801 locally from test runs: 3.5 s a load, and every plan change or switch re-read them all): counts now grouped once and a change reads back one organisation (0.56 s / 0.11 s)
- [x] Tests: `PermissionTest` (2) — the rules table; Staff gets no cost (JSON null) and a Manager does; a "Sales lead" role with margins but no cost given to the manager changes what they see at once; duplicate / unknown / in-use roles refused; an admin keeps everything; E2E ROLE-001 (staff: no cost price, VAT return 403; a "Buyer" role made in the UI → cost and report visible, sale orders 403)
- [ ] Later (Cin7 has them): Settings permissions in roles (now admin only), several roles per user, the remaining Cin7 ticks (CRM, manufacturing, integrations…) when those modules exist; bills / invoices amounts are not hidden by *Purchase - View prices and totals* (needed to pay them)

### I2 Global search ✅
- [x] Cin7 checked (read-only, 2026-11-06): Cin7 Core has **no global search box** (its top bar has *Ask me anything*, Favourites and a **+** quick-create menu; search is inside each list). The spec asks for one, so this goes beyond Cin7
- [x] **Search box in the top bar** (press **/** to jump to it; arrows, Enter, Esc): sale orders (number, customer, customer reference, Shopify number), invoices and credit notes, bills and supplier credit notes (number, party), purchase orders (number, supplier, supplier reference), customers, suppliers (name, contact, e-mail), products (SKU, name, barcode, supplier code); "contains", any case; up to 6 of each kind, the exact number first; only the kinds the user's role can open (I1); a supplier opens the Suppliers list searched (no supplier page yet)
- [x] Trigram indexes (`V36`) like products'; on the local data a search takes about 0.1 s
- [x] Tests: `SearchTest` (1) — each kind by number, name, reference, e-mail, barcode; `%` is not a wildcard; a sales-only role finds no products / bills / suppliers; another organisation finds nothing; E2E SEARCH-001 (not run yet: the app server was stopped for low memory)
- [ ] Later (Cin7 has it): the **+** quick-create menu and Favourites in the top bar; a supplier record page

### I3 Core reports ✅
- [x] Cin7 checked (read-only, 2026-11-06, Reports): Purchase reports are Pending Purchase Orders, Purchase Order Details / Summary, Purchase Order vs Invoice, Stock Received Not Invoiced, Purchase Invoice Details / Summary, Purchase Invoices & Credit Notes, Stock Received vs Invoice, Supplier Financial Summary, Product Prices by Supplier, Inventory Suppliers (Sale and Inventory lists seen in H4)
- [x] **Finance → Reports**, like Cin7's Reports page: every report by group (Sale, Purchase, Inventory, Financial), only those the role allows
- [x] Six more of Cin7's reports on one report page (period where it has one, search, Grand Total, links to the records, **Export CSV**): **Sales Overview** (each order of the period: customer, rep, status, shipment, ordered, invoiced, credited, paid, due, COGS, profit, gross margin), **Sale Invoices & Credit Notes** (amount, tax, total, paid, due), **Sales Backorder Details** (orders short of stock: backordered, available, on order), **Pending Purchase Orders** (to receive, to invoice, order total), **Stock Received Not Invoiced** (received but not billed, at cost: the stock that is in the ERP's value but not yet in Xero), **Stock Value Inventory Aging** (FIFO value by age of the layers: 0–30, 31–60, 61–90, 91–180, over 180 days, average age)
- [x] Cin7's report groups as permissions (`V37`): *Sale reports* and *Purchase reports* for Manager and Staff, *Inventory reports* for Manager; COGS / profit / cost columns only with *Sale - View profit margin* / *Purchase - View prices and totals*; the aging report needs *View stock value*
- [x] Tests: `CoreReportTest` (1) — 3 counted in at £6, 10 bought at £5 received not billed, 2 sold at £10 invoiced (£24) with £10 paid, 15 confirmed with 11 in stock → overview £24 / £10 / £14, COGS £12, profit £8 (40 %); invoices total £24; 4 backordered with 11 available; PO to invoice 10, £50 not invoiced; aging £56 all in 0–30 days; Staff: no profit columns, no aging report. E2E REPORT-001 written, **not run yet** (app not started: Java heavy); full backend suite not run for I3 either (only `CoreReportTest`, `PermissionTest`)
- [ ] Later: the other Cin7 reports (Purchase Order Details / vs Invoice, Purchase Invoice reports, Supplier Financial Summary, Product Prices by Supplier, Customer Payment Summary, Sales Demand Forecast…); Cin7's pivot layouts (from H4)

### I4 Document templates ✅
- [x] Cin7 checked (read-only, 2026-11-06, Settings → Document and email templates): templates grouped Purchase / Sale / Inventory / POS / Production / CRM / Financial, **several named templates per document type** (Expert Safety's Sale: *Sale Quote - Expert Safety*, *Sales Order - Expert Safety*, *Pick List*, *Receipt*, *Delivery Note- Expert Safety*, *Box Label*, *Invoice - Account Client*, *Invoice - Cash Sale*, *Invoice Expert*, *Credit Note*, statements…; Purchase: *Purchase Order - Expert Safety*, *Purchase Invoice*, *Receiving Note*, *Purchase Return*, *To Receive List*, *Product Label*); each is a **Word mail-merge file** (download, edit in Word, upload; Reset) with an e-mail subject, e-mail template and recipients (users, contacts, mailing list, BCC, customer, sales rep)
- [x] **Settings → Document templates** (admins edit, everyone prints): named templates for quotes, sales orders, invoices, credit notes and purchase orders (`V38`); one **default** per type (the first one is; removing it makes another the default); per template: printed title, accent colour, logo on / off, **prices on / off** (off = a delivery note or pick list: no prices, totals, VAT, payment box or charges), SKU / discount columns, VAT summary, text above the lines, payment text and footer instead of the company's, e-mail subject (for when e-mailing comes); names unique per type in any case
- [x] **Printing** uses the type's default template, or the one picked next to **Print** on the order / invoice / purchase order page when the type has more than one (`?template=`, refused for a template of another type); without templates the standard layout prints as before
- [x] **Not like Cin7 (noted):** no Word mail-merge upload: turning Word files into PDF needs LibreOffice on the server (heavy and fragile), and a template written by an organisation would run on the shared server; templates are layout settings on the ERP's own layout instead
- [x] Tests: `DocumentTest` (+1, 7 in all) — a "Delivery Note" default prints without prices or carriage and with its own title and text; "Invoice - Cash Sale" prints its payment text and footer instead of the bank details; a template of another type, a duplicate name and a bad colour are refused; removing the default makes the other one default. E2E TEMPLATE-001 passed (2026-11-06)
- [ ] Later: picking a template for ticked rows in the lists (the API takes `template`); templates for statements / reminders, receiving notes, pick lists per warehouse, box and product labels; e-mail subject and body used when e-mailing comes (spec §7)

### I5 Two-step sign-in ✅
- [x] Cin7 checked (read-only, 2026-11-06): Settings → General → Security settings has *Password change frequency* (Never) and *Session timeout* (12 hours); User profile has **Reset two-factor authentication**, **Add a passkey**, a per-organisation **Two-factor authentication required** column (N/A for Expert Safety) and a **login history**
- [x] **Authenticator-app codes** (TOTP, RFC 6238: 6 digits, 30 s, one step of clock drift) per user (`V39`): **My account** (account menu) → *Turn on two-step sign-in*: a setup key to type into the app (or open on the phone), confirmed with a first code; **8 one-time recovery codes** shown once (only their SHA-256 kept), renewable with a code; turn off with a code. The secret is stored encrypted (`SecretCipher`)
- [x] **Sign-in**: a right password gives only a 5-minute **challenge** (signed, never accepted as a session); the session comes after the code or a recovery code; a code works once; **5 wrong codes lock the step for 15 minutes**; all audited (on, off, recovery code used, locked, reset)
- [x] **Admin → Users**: *Two-step sign-in required* for the whole organisation (Cin7's column): anyone without it sets it up at their next sign-in (key, first code, recovery codes) and cannot turn it off; **Reset two-step sign-in** for someone who lost their phone (Cin7's reset)
- [x] Tests: `TotpTest` (2: RFC 6238 test vectors, drift, format), `TwoFactorTest` (2) — the password alone gives no session; wrong / reused codes refused; recovery code once; lock after 5; admin reset; required → set up at sign-in, secret kept encrypted, cannot be turned off; E2E TWOFA-001 passed (2026-11-06)
- [ ] Later: a **QR code** on the setup screen (needs a QR library: to add with the owner's OK; the key and the phone link work meanwhile); passkeys, login history page, session timeout and password change frequency settings (Cin7 has them; sessions last 24 h here); sessions open when the requirement is switched on last until they expire

## Phase J — Cutover from Cin7 (functions) ✅

The functions for moving an organisation from Cin7 Core; the move itself (Expert Safety's data) is done at cutover, following [`docs/cutover.md`](docs/cutover.md). Built without starting the app (2026-11-06).

- [x] Cin7 checked (read-only, 2026-11-06): Sale / Purchase → **Import or extract from file → Import CSV file** (*Sale/purchase task CSV import*): 3 steps (download the template, copy the data in, import), one file with a **RecordType** per row (Invoice, InvoiceLines, InvoiceAdditionalCharges, Payment, CreditLine, CreditNoteAdditionalCharge, Refund), options *Pick, pack and ship processing mode* (No picking…), *Invoice status* (Draft…), *Set order backordered on low stock*, *Export imported tasks to Xero*, and a **Logs** tab
- [x] Cin7's rules read from its help article *Import sales tasks via CSV* (2026-11-06) and followed: rows grouped by **CustomerName / SupplierName + InvoiceNumber**; the order is always authorised; **invoice status Draft / Authorised**; **Pick, pack and ship mode** No picking / Auto Pick / Auto Pick + Pack / Auto Pick + Pack + Ship; **Set order backordered on low stock level** (off: short of stock is an error; on: imported backordered, credit note and refund skipped); **Export imported tasks to Xero** (unticked: the documents and payments are recorded as already exported, in a batch of their own, `markNotToExport`); purchases: **Generate stock received from invoice lines**
- [x] **Settings → Import sales & purchases** (`V40`, `TaskImportService`): Cin7's template headings, tabs *Sale tasks / Purchase tasks / Logs*. Record types **Invoice, InvoiceLines, InvoiceAdditionalCharges, Payment, CreditLines, CreditAdditionalCharges, Refund**, purchases also **Received** (by date, partial) and **Unstock** (credited goods sent back to the supplier). As Cin7: a **customer / supplier not found by name is created** from the Invoice row (terms, tax rule, currency) and the customer's **addresses added or updated** (same line 1) and **contact added**; deprecated ones refused; payments need Authorised; credit notes and refunds need Authorised (sales: and shipped); a refund needs credit lines; **line Total must equal price × quantity less discount**; invoice / payment / credit note dates not in the future; only new tasks (a number already used is refused)
- [x] **Added for cutover (not in Cin7):** invoice status **No invoice / bill yet** (open orders keep Cin7's SO / PO number, or the optional `OrderNumber` column) and **already shipped / received in the old system** (lines done without moving stock: the opening stock already counts them); new customers / suppliers listed in the preview (to catch typos)
- [x] **Safe**: Preview runs everything then rolls back; **all or nothing** (any error, nothing written; every error listed); each import in the Logs tab and the audit log
- [x] **Settings → Go-live check** (`CutoverCheckService`, `GET /cutover/check`): per warehouse units and FIFO stock value, customers owe / number owing, owed to suppliers, open sale orders and value left to ship, open POs and value left to receive, next SO / invoice / PO numbers; where each figure is in Cin7; type Cin7's figure → green when equal, red with the difference; warnings (no Xero start date, users without two-step sign-in when required)
- [x] **Running it**: `GET /api/health` (public, app + database; 503 when the database is down); `infra/backup.sh` (daily `pg_dump`, checked with `pg_restore -l`, 30 days kept; `make backup`), `infra/restore.sh` (into a spare database to prove a backup, or `--replace`; `make restore-check`), `infra/monitor.sh` (cron uptime alert by e-mail once down / once back); runbook with the weekend plan and the **rollback plan**: [`docs/cutover.md`](docs/cutover.md)
- [x] **Not like Cin7 (noted in TODO):** Auto Pick and Auto Pick + Pack leave the order to ship (no separate pick / pack steps yet); no 100-task limit per file; the whole file or nothing; a credit line must be at the invoice price (a price credit is a credit note charge); negative credit note charges and part refunds are entered by hand; Cin7's Restock lines are not taken; *already shipped* lines carry no cost of goods here (Cin7 posted it to Xero before the cutover)
- [x] Tests: `TaskImportTest` (5) — an open order keeps Cin7's number, reference and date, preview writes nothing, the same file is refused the second time; an unpaid invoice already shipped keeps its number and dates, discount and charge, £10 paid into 650, no stock moved, recorded as already in Xero; Cin7's rules: a new customer created with its shipping address and contact, Auto Pick + Pack + Ship ships, credit note CR with its own number refunded into 650 on its date, short of stock an error unless backordered is ticked; errors (future date, unknown SKU, wrong Total, credit note without Authorised + shipped, unknown RecordType) stop the whole file; open PO, a bill with Received rows, CreditLines + Unstock sending 2 back (stock 10 + 4 − 2), new supplier created; go-live check figures; Cin7 date formats. `PermissionTest` green. E2E CUTOVER-001 passed (2026-11-06)
- [ ] At cutover (owner): run [`docs/cutover.md`](docs/cutover.md) — rehearsal, then the weekend; production server, HTTPS, off-site backup copy

## Phase K — ERP accounting, like Xero ✅ (K1 ✅ K2 ✅ K3 ✅ K4 ✅ K5 ✅)

The ERP's own books, so it can be sold all-in-one; the Xero export / link stays for organisations that keep Xero. Reference: Xero (Expert Safety's chart of accounts read from Cin7's copy of it, read-only, 2026-11-06: Xero's UK standard chart plus 650–654 banks, 615 Supplier Deposits, 631 Inventory Control, 802 Customer Credits).

### K1 Chart of accounts, general ledger, manual journals, trial balance ✅
- [x] **Accounting → Chart of accounts** (`V41`, `ChartOfAccountsService`): Xero's account types and classes, tabs All / Assets / Liabilities / Equity / Expenses / Revenue / Archive; add, edit, archive, delete (an account with transactions keeps its code and can only be archived, as Xero); a new organisation gets **Xero's standard UK chart** plus the accounts the ERP posts to (631, 615, 802, 495) and its payment accounts as **bank accounts**; **import of Xero's chart export** (preview, added / updated by code)
- [x] **General ledger** (`LedgerService`): every document posts a **balanced journal** with the Xero export's account mapping (H5), so ledger and export agree — invoices / credit notes (receivable, revenue, VAT), bills / supplier credits (inventory for stock lines, purchase charges for the rest, VAT, payable), payments (the payment account's bank against receivable / payable; customer credit and supplier deposits used), customer credit received and supplier deposits paid, stock at FIFO cost (COGS on shipment / return, adjustments, bill price differences: the export's own journals), manual journals, conversion balances; pennies to Rounding
- [x] **Never edited**: before the ledger is read it is brought up to date — for each source what it should post is compared with what it posted, and a difference posts a **reversal plus the new journal** (an edited, cancelled or voided document, a voided payment, a deleted credit); nothing changed, nothing posted
- [x] **Accounting → Manual journals**: narration, date, lines (account, description, debit or credit), debits must equal credits; Draft / Posted / Voided; a posted journal is voided (reversed), not edited
- [x] **Accounting → Ledger settings**: Xero's **conversion date** (nothing dated before it is posted) and **conversion balances** (dated the day before; the difference to 840 Historical Adjustment, as Xero); the account each posting goes to (H5's mapping plus receivable, payable, VAT, retained earnings, suspense, rounding, historical)
- [x] **Reports**: **Trial balance** (as at a day, by class, debits = credits), **Account transactions** (opening, lines with source links, running balance, closing), **Journal report** (every journal with its lines, reversals marked)
- [x] Permission **Accounting** (managers by default); chart, settings and conversion balances: admins
- [x] Checked on Expert Safety's local data: the trial balance balances (£98,040.87), 0.04 s
- [x] Tests: `LedgerTest` (4) — stock count, bill, bill paid, sale shipped (COGS 2 × £6 FIFO), invoice overpaid (£6 customer credit): trial balance balanced and each account right (610 and 800 nil, 631 £98, 310 £12, 200 −£20, 802 −£6, VAT, bank), the payment account a bank account, a second sync posts nothing, account transactions; manual journal unbalanced / unknown account refused, a draft posts nothing, posted shows, cannot be changed, void reverses; a conversion date reverses what is before it, conversion balances with the difference in 840; Xero chart import (added, updated, unchanged, unknown type). `AccountingExportTest`, `PermissionTest`, `TaskImportTest` green. E2E LEDGER-001 passed

### K2 Profit and loss, balance sheet, financial year end, lock dates ✅
- [x] **Accounting → Profit and loss** (`FinancialReportService`): Xero's layout — Trading Income, Cost of Sales, **Gross Profit**, Other Income, Operating Expenses, **Net Profit**; any period; **compare** with up to 11 earlier months, quarters or years (a whole month stays a whole month); each account links to its transactions
- [x] **Accounting → Balance sheet**: Bank, Current Assets, Fixed Assets, Non-current Assets, Total Assets; Current and Non-current Liabilities; **Net Assets**; Equity with **Current Year Earnings** (profit since the start of the financial year) and **Retained Earnings** (the years before plus the retained earnings account) — as Xero, **no closing journal**: the year's profit rolls into retained earnings by itself at the year end; comparison columns; a warning if equity ≠ net assets
- [x] **Ledger settings → Financial year end** (`V42`, `LedgerPeriodService`; 31 March when not set, Xero's UK default; a month-end year end stays on the month end in leap years) and Xero's **two lock dates**: *for all users except advisers* and *for all users* (advisers = admins until accountant access, K5); the second must be on or before the first, neither in the future
- [x] **Locked periods**: nothing dated on or before the lock is entered, changed or voided — payments and their voids, bills, invoice cancellations, supplier credit notes, customer credit, supplier deposits, manual journals (save, post, void), conversion balances, sale / purchase task imports; and if a document behind the lock changes anyway, the ledger posts the correction **on the day after the all-users lock**, so filed figures never move
- [x] Checked on Expert Safety's local data: balance sheet balances (net assets = equity = £98,040.87)
- [x] Tests: `FinancialReportTest` (3) — P&L with a month comparison, last year's P&L; balance sheet with current year earnings £300, retained earnings £700, owner's funds, comparison a year back, rollover after the year end; year start for a 30 November and a 28 February year end (leap year); lock dates: rules, a manager stopped and an admin not, all users stopped, a change behind the lock posted on 1 March with February unchanged. `LedgerTest`, `SalesLedgerTest`, `PurchaseLedgerTest`, `TaskImportTest`, `AccountingExportTest` green. E2E LEDGER-002 passed
- [ ] Later: P&L / balance sheet export (PDF, Excel), tracking categories, budgets, cash-basis reports

### K3 Bank accounts, statements and reconciliation ✅
- [x] **Accounting → Bank accounts** (`V43`, `BankService`): Xero's bank cards — **statement balance** (the statement's own running balance and date), **balance in the ERP**, **Reconcile N items**; every Bank-type account of the chart (the payment accounts are bank accounts)
- [x] **Import a statement**: CSV (Xero's template, or a bank's own layout: Date; Amount, or Paid in / Paid out, Money in / out, Debit / Credit; Payee / Description / Narrative; Reference; Balance; £, commas, (brackets) and DR for money out) and **OFX** (DTPOSTED, TRNAMT, NAME, MEMO, CHECKNUM / REFNUM); preview, then import; lines imported before are **skipped as duplicates**
- [x] **Reconcile** (per statement line, Xero's tabs): **Match** — up to three suggestions with the same amount within 60 days, nearest date and shared payee / reference words first, OK to reconcile; **Find & match** — any of the account's ledger items not reconciled (payments, customer credit, supplier deposits, manual journals, bank transactions), several adding up to the line; **Create** — a spend / receive money transaction (who, what, account, VAT rate) made and reconciled; **Transfer** — to or from another bank account; delete a line not reconciled
- [x] **Reconciled statement lines** with what each matched, **Unreconcile**; a reconciled payment, customer credit, supplier deposit, manual journal or bank transaction **cannot be voided** until its line is unreconciled (as Xero)
- [x] **Spend money / Receive money / Transfer money** (Xero's bank transactions): bank account, date, contact, reference, lines with account, description, VAT rate and amount including VAT (net and VAT worked out); posted to the ledger (costs / income and VAT against the bank; transfers bank to bank); void (reversed; not while reconciled; not in a locked period)
- [x] The ledger is now brought up to date before **every** read (a payment voided a moment after a sync shows at once); customer credit and deposit references carried to the ledger
- [x] Tests: `BankTest` (3) — CSV with Paid in / Paid out and balance: preview writes nothing, import, duplicates skipped and refused; the £120 customer credit suggested and matched, a wrong total refused; bank charges created on 404; transfer to the deposit account; ledger and statement balances; a reconciled credit cannot be voided until unreconciled; spend money £60 with 20 % VAT (£50 + £10), a bank account refused as a line, void reverses; OFX read; bank amount and date formats. `LedgerTest`, `FinancialReportTest`, `SalesLedgerTest`, `PurchaseLedgerTest` green. E2E BANK-001 passed; LEDGER-002's "Saved" message fixed (the settings forms no longer reset after saving)
- [ ] Later (in Xero, not here yet): bank rules (fill Create from the payee), live bank feeds (Open Banking provider), cash coding, a minor-adjustment line when a match is pennies out, the bank reconciliation summary report, QIF files

### K4 VAT returns and Making Tax Digital ✅
- [x] The VAT return (H3) now takes **spend / receive money** with a VAT rate (purchases / sales side, by its VAT type), as Xero's does; each document carries its source (invoice / bill, or bank transaction)
- [x] **Accounting → VAT returns** (`V44`, `MtdVatService`): review any period in **HMRC's nine boxes**, with a check against the ledger (how the VAT account moved in the period; a warning when it differs, e.g. VAT payments or journals posted to 820) and warnings for a period not ended, already filed or locked
- [x] **Making Tax Digital** (`HmrcClient` / `HttpHmrcClient`, HMRC VAT API 1.0): the VAT number; **Connect to HMRC** (OAuth 2.0, the business grants read:vat and write:vat at HMRC; the answer only accepted with our state; tokens kept encrypted, refreshed when expired); **VAT periods at HMRC** (obligations); **Submit** with HMRC's **legal declaration** ticked (admins only), the boxes sent as HMRC asks (1–5 pounds and pence, 6–9 whole pounds, finalised); HMRC's refusals shown with its code
- [x] **HMRC fraud prevention headers** on every call (WEB_APP_VIA_SERVER): browser user agent, device id, Do Not Track, screens, window size, time zone from the browser; public IP and port as our server sees them; user id; two-step sign-in (TOTP) when the user has it on; vendor product, version, public IP and forwarded
- [x] **Filed returns** kept as sent (boxes, HMRC receipt: form bundle, charge reference, payment indicator); a return filed another way (e.g. from Xero before the switch) **recorded** the same way; a period cannot be filed twice (overlapping dates refused); filing **locks the books** to the period end (as Xero suggests)
- [x] Sandbox by default (`APP_HMRC_BASE_URL`); credentials `APP_HMRC_CLIENT_ID`, `APP_HMRC_CLIENT_SECRET`, `APP_HMRC_REDIRECT_URI` (…/accounting/vat/callback), `APP_HMRC_VENDOR_PUBLIC_IP`; without them the page says so and returns can still be reviewed and recorded
- [x] Tests: `VatFilingTest` (2, against a fake HMRC) — boxes from receive / spend money with 20 % VAT (1 £20, 4 £10, 5 £10, 6 £100, 7 £50), ledger check agrees; VAT number rules; connect with the state checked, tokens encrypted; obligations; declaration required, managers refused, submission values and fraud headers, receipt kept, books locked, no second filing; a return recorded as filed elsewhere, future periods refused; an expired token refreshed. `VatReturnTest` green. E2E VAT-001 passed; finance e2e (incl. VAT-RETURN) green
- [ ] Owner: register on HMRC's Developer Hub, test in the sandbox, then apply for production (HMRC checks the fraud headers) — see TODO
- [ ] Later: HMRC's liabilities and payments, viewing a submitted return from HMRC, cash accounting and flat rate schemes, VAT rates on manual journals

### K5 The accountant: Adviser rights, exports, year-end pack ✅
- [x] **Adviser** permission (Xero's Adviser role): the chart of accounts, ledger settings, conversion balances and lock dates; works up to the *all users* lock date while others stop at the *all users except advisers* date; files VAT returns (MTD or recorded). Admins have it; replaces "admins are the advisers" of K2 / K4
- [x] **Accountant** starting role (`V45` for organisations with roles already; new organisations get it with Manager and Staff): Accounting + Adviser, every report, and everything else to look at only (sales, purchases, products, costs, stock value, dashboard, audit log) — give the accountant a login with it (Admin → Users)
- [x] **Exports** (`LedgerExportService`), CSV that opens in Excel (with a byte order mark for £): trial balance, profit and loss and balance sheet (with their comparison columns), account transactions, and the **general ledger** (every journal line in a period with account name and type, VAT rate, reversals) — an Export button on each report
- [x] **Year-end pack** (Ledger settings): one zip for the financial year ending on a day — P&L with the year before, balance sheet with the year end before, trial balance, the year's general ledger, aged debtors and creditors at the year end, VAT returns filed in the year, the chart of accounts in Xero's import headings, and a README with the lock dates
- [x] Tests: `AccountantTest` (2) — the Accountant role and its ticks; a manager stopped in the locked year while the Adviser posts there and records the VAT return, both stopped by the all-users date; trial balance, P&L and general ledger CSV; the year-end pack's eight files and README, a day that is not a year end refused. E2E ACCT-001 passed (CSV and zip download, Accountant role); BANK-001 date fixed (the server's date, not the browser's, after midnight in Asia)
- [ ] Later: PDF of the statements, Excel (.xlsx) and Google Sheets export, an accountant invited by e-mail (with the e-mail integration), fixed asset register and depreciation, budgets

## Phase L — Xero API link, against a fake Xero ✅ (L1 ✅ L2 ✅ L3 ✅ L4 ✅ L5 ✅)

Plan: [`docs/next-phases.md`](docs/next-phases.md) (owner, 2026-11-07). The functional spec §6 asks for *two-way sync with Xero or QuickBooks Online: invoices, bills, payments, stock journals* (Must). Everything is built against a fake Xero; live use needs only the owner's Xero developer app and `APP_XERO_MODE=live`.

### L1 Client layer ✅
- [x] `XeroClient` (Xero Accounting API and OAuth 2.0): contacts, sales invoices and supplier bills (ACCREC / ACCPAY), void, customer and supplier credit notes (ACCRECCREDIT / ACCPAYCREDIT), allocations, payments and refunds, delete a payment, manual journals, payments changed since a moment (for L4); authorise, code exchange, refresh, the organisations given access. Request / response shapes are Xero's own (`XeroApi`, PascalCase: ContactID, LineAmountTypes, AmountDue…); failures as `XeroException` with a kind the queue acts on (validation: needs a person; rate limit: wait Retry-After; unauthorised: refresh; unavailable: later)
- [x] `FakeXeroClient` (`APP_XERO_MODE=fake`, the default): in memory, one "Demo Company (UK) — fake" per ERP organisation, Xero-style IDs, Xero's rules (a contact on every document, unique contact names and sales invoice numbers, a payment no more than due and only on authorised documents, totals worked out, PAID when settled, void only without payments, journals that balance, deleted payments put the amount back), tokens that expire after 30 minutes with single-use refresh tokens; told to fail the next calls with a **validation error, a 429 with Retry-After, an expired token or Xero down**; payments "entered in Xero" for L4; a record of every call (to prove nothing is sent twice)
- [x] `HttpXeroClient` (`APP_XERO_MODE=live`): login.xero.com sign-in with offline access, identity.xero.com token exchange and refresh (Basic auth), GET /connections, the API with the `xero-tenant-id` header; Xero's 400 ValidationException messages, 401, 404, 429 Retry-After and 5xx turned into `XeroException`. Settings `APP_XERO_CLIENT_ID`, `APP_XERO_CLIENT_SECRET`, `APP_XERO_REDIRECT_URI` (…/finance/xero/callback)
- [x] **Connection** (`V46`, `XeroConnectionService`, `/api/xero/connection`): connect (the fake comes straight back to our callback), callback accepted only with our state, the Xero organisation chosen at once or picked from several, a session refreshing the token before it expires or when Xero says it has (in its own transaction, as the refresh token is single use), expired → connect again, disconnect; tokens encrypted with the same key as the Shopify tokens; seeing it needs the financial reports permission, changing it an adviser or admin
- [x] Tests: `XeroClientTest` (3) — connect through the fake, wrong state refused, tokens stored encrypted, an expired token refreshed and the call made again; another organisation not connected and unable to reach this one's Xero; every document type through the fake with Xero's rules (unique names and numbers, part payment, overpayment refused, credit note allocated, PAID, bill, supplier credit refunded, balanced and unbalanced journals, void with and without payments, payment deleted) and each simulated failure; the live client against a local stand-in for Xero (sign-in address and scopes, headers, a validation message, Retry-After)

### L2 Mapping and sync queue ✅
- [x] **`xero_sync_items`** (`V47`): one row per ERP item to send — its key (CONTACT:CUSTOMER:5; DOC / PAY / JNL keys as the CSV export's), kind, label, what it waits for (an invoice for its contact), the **Xero ID** once sent, status **pending / sent / failed**, attempts, last error, next try; queued once per item (a second enqueue changes nothing)
- [x] **The queue** (`XeroSyncService`), in the background every 30 s (`APP_XERO_SYNC_EVERY`; off in tests) and on demand: what is due, contacts first, each item in its own transaction with its row locked (`FOR UPDATE SKIP LOCKED`, one run per organisation at a time); **rate limit** → waits Xero's Retry-After; **Xero down** → backs off 1, 2, 4 … 60 minutes, **failed after 8 tries**; **expired token** → refreshed and tried again in the same run; **refused by Xero** (validation) → failed at once for a person to fix and **Retry**; an item waiting for another goes in the same run once that is sent; an unexpected error fails that item only
- [x] **Never twice**: a sent item is never sent again, and every call carries the item's **Idempotency-Key** (Xero's header; the fake honours it), so a send repeated after a crash between Xero's answer and our save returns Xero's first answer instead of a duplicate
- [x] **Contacts** (`XeroContactSender`): customers and suppliers with their e-mail; a contact Xero already has with the same name is **linked**, not created again (an organisation coming from Cin7 has its customers in Xero already) — `findContactByName` added to the client (fake and live: `Contacts?where=Name==…`)
- [x] Endpoints: `GET /api/xero/queue` (counts, items), `POST /api/xero/queue/{id}/retry`, `POST /api/xero/queue/run` (advisers / admins)
- [x] Tests: `XeroSyncTest` (3) — a customer sent once as a contact with its e-mail, queued twice but sent once, nothing re-sent; an existing Xero contact linked; a 429 waits then sends; Xero down backs off a minute, fails after 8 tries, Retry sends it, only failed items retried; a refusal fails at once; an expired token refreshed in the same run; a send repeated after a crash not duplicated (same Idempotency-Key); an item waiting for its contact sent in the same run; a kind with no sender yet fails saying so; another organisation sees nothing. `XeroClientTest` green

### L3 Sending ✅
- [x] **What is sent comes from the CSV export's own list** (`XeroEnqueuer` ← `AccountingExportService.pending()`): the same export start date, documents, stock journals and payments, nothing already in Xero; each document waits for its contact, each payment for its document; credit notes applied to invoices / bills are queued as **allocations** (the CSV files could not carry them)
- [x] **Invoices, bills, customer and supplier credit notes** (`XeroDocumentSender`), line for line as the CSV export (H5): sales lines on the revenue account with price and discount, bill lines on inventory (stock) or purchase charges, net per unit, the ERP's VAT amount on each line, Full VAT lines as VAT; authorised, with the ERP's numbers, dates and order reference. VAT rate names → Xero **TaxType codes from the organisation's own tax rates in Xero** (GET TaxRates, the names the CSV templates use); a name Xero does not have fails the item for a person (no guessing)
- [x] **Payments and refunds** (`XeroPaymentSender`) on the right Xero document, into the payment account's bank code (or the default payment account); **allocations** of credit notes; **COGS / stock journals** (`XeroJournalSender`) as manual journals, as the CSV file's
- [x] **One record with the CSV export** (`V48`): `accounting_exported` rows without a file batch, marked CSV or API; an item sent by the API is never put in a CSV file, an item in a CSV file is never sent by the API (counted as in Xero; a later payment finds its document in Xero by number — `findInvoiceByNumber` / `findCreditNoteByNumber`); **one way at a time** per organisation (`accounting_settings.xero_link`, `GET/PUT /api/xero/link`): on API the CSV export is refused, on CSV the queue sends nothing
- [x] Tests: `XeroSendTest` (2) — a stock count, a bill paid, a sale shipped, invoiced, part paid and credited: on the CSV link nothing sent; on the API (after a 429) two contacts, the invoice (£24, account 200, OUTPUT2, SKU description), the bill (631, INPUT2, PAID), the credit note applied (allocation), two payments into 650, COGS and stock journals; recorded as sent by the API; CSV export refused on the API link and "nothing new" on the CSV link; a second run sends nothing; an invoice first exported by CSV (imported into Xero) is not sent again and its later payment is matched by number; another organisation sees nothing. `XeroSyncTest` updated (a missing document fails saying so), `AccountingExportTest`, `XeroClientTest`, `LedgerTest`, `TaskImportTest` green

### L4 Payments from Xero ✅
- [x] **Pulled back** (`XeroPaymentPuller`, `V49`): every 5 minutes (`APP_XERO_PULL_EVERY`) with Xero's If-Modified-Since, and on demand (`POST /api/xero/payments/pull`); each payment entered in Xero is recorded against its ERP invoice, bill or credit note (refund) — found through the queue's mapping, else by number — into the payment account with the same code, reference "Xero: …", dated as in Xero
- [x] **Never sent back**: recorded as already in Xero (queue item SENT with Xero's PaymentID, one record with the CSV export); payments the ERP sent itself are skipped; a payment already pulled is not recorded twice
- [x] A payment **deleted in Xero** voids the one recorded here; a payment whose document is not found is listed **UNMATCHED**; one that cannot be recorded (e.g. Xero's bank code is not a payment account here) is listed **ERROR** with the reason (`GET /api/xero/payments`)
- [x] **Webhooks ready for live** (`POST /api/webhooks/xero`, public, `APP_XERO_WEBHOOK_KEY`): Xero's signature (base64 HMAC-SHA256 of the body) checked — a bad one gets 401 as Xero's intent-to-receive check expects; an INVOICE event for a connected organisation asks for a pull within 30 s
- [x] Scheduled runs act as "Xero" in the audit log
- [x] Tests: `XeroPullTest` (2) — £10 paid in Xero recorded on the ERP invoice into 650, listed with its invoice number, not sent back, not recorded twice; a payment made here sent to Xero and not pulled back as new; deleted in Xero → voided here; a Xero-only invoice's payment UNMATCHED, an unknown bank code ERROR; webhook with a good signature asks for a pull, a bad one or no key → 401; another organisation sees nothing

### L5 Screen ✅
- [x] **Finance → Xero** (`/finance/xero`): the connection (fake or live mode shown, status, the Xero organisation, connect, choose among several, disconnect, expired → connect again); **how documents reach Xero** (CSV files or this link; the link only once connected); **the queue**: waiting / failed / sent counts, tabs All · Failed · Waiting · **History**, each item with its tries, next try or sent time, Xero ID or problem, **Retry** on failed items, **Send now**; **payments entered in Xero** with their document, amount, status and reason, **Pull now**; Xero's callback page (`/finance/xero/callback`); changing anything needs the Adviser tick (admins have it); the CSV export renamed "Xero export (CSV)" in the menu
- [x] E2E XERO-002: connect to the fake Xero from the page, switch to the link, an invoice and its contact sent ("Sent 2, failed 0") and shown in the History, Pull now
- [x] **Full suites green**: backend 241 tests; e2e 105 tests. The e2e browser and test process now run on UTC (`timezoneId: 'UTC'`, `TZ=UTC` in `make e2e`), the server's clock — four older tests (DSH-001/002, XERO-001, PLEDGER-001) took "today" from the browser and failed after midnight in Asia

## Phase M — E-mail ✅ (M1 ✅ M2 ✅ M3 ✅ M4 ✅)

Spec §7 (Must): quotes, invoices, credit notes, statements, overdue reminders and purchase orders e-mailed from the ERP; built against a fake sender (`APP_MAIL_MODE=fake`, the default).

### M1 Sending layer ✅
- [x] `MailSender` (`integration/mail`): `FakeMailSender` keeps every e-mail in the outbox without sending (default); `SmtpMailSender` (`APP_MAIL_MODE=live`) is the live one — **SMTP needs a mail library (spring-boot-starter-mail / Jakarta Mail), not added without the owner's OK**: until then it refuses clearly and the e-mail stays in the outbox marked "Not sent"
- [x] **Settings → E-mail** (`V50`, `mail_settings`): sender name and address (checked), reply-to, SMTP host, port, username, **password stored encrypted** (only replaced when a new one is typed), STARTTLS; admins only
### M2 Templates ✅
- [x] Editable **subject and text per document type** (quote, sales order, invoice, credit note, statement, 1st / 2nd reminder, final notice, purchase order, tracking for phase O), standard wording until the organisation writes its own, back to standard in one click; **placeholders** {{customer}}, {{supplier}}, {{number}}, {{amount}}, {{amount_due}}, {{due_date}}, {{date}}, {{reference}}, {{order_number}}, {{invoices}}, {{balance}}, {{company}} ({{carrier}}, {{tracking}} for O) — an unknown one is left showing, so a typo is seen
- [x] The **PDF attached** is the document's own (I4 templates; G3 statement and reminder layouts)
### M3 Actions ✅
- [x] **E-mail** next to Print on quotes and orders, invoices and credit notes (issued), purchase orders; on the customer's statement card (statement, next reminder): a dialog with To (the customer's default contact, else the first with an e-mail; the supplier's e-mail), Cc, subject and text from the template, all editable, then Send; a missing address says who has none and where to add it, an address that is not one is refused
- [x] **Overdue → E-mail statements / E-mail reminders** for the ticked customers (reminders: every overdue customer when none ticked): G3's levels (1st, 2nd, final notice), a reminder **counted on its invoices only once the e-mail has gone**; customers without an address are listed, not stopped on
- [x] Permissions per document: sales documents need *Sale - add / edit*, purchase orders *Purchase - add / edit*, statements *Customers*, reminders *Customer credit*; settings and templates admins
### M4 Log ✅
- [x] **Outbox** (Settings → E-mail): every e-mail, when, about what, to whom, subject, status (kept in test mode / sent / not sent and why), the text and the PDF as sent; each document shows **its e-mails** (who sent what, when, to whom) — invoice, order, purchase order, customer
- [x] Tests: `MailTest` (2) — no sender address refused; SMTP password encrypted; quote (QUOTE template, the customer's default contact, £24 with VAT, kept in the fake outbox, a real PDF attached), order, invoice (due date), credit note, the invoice's own log; purchase order to the supplier's e-mail, a supplier without one refused clearly, a typed address checked and used with the user's own subject; statements to two customers, one without an address listed; overdue reminders: the first sent and counted on the invoice, the next would be the second; templates with an unknown placeholder, reset; staff cannot send reminders or edit templates; another organisation sees nothing. E2E MAIL-001 (settings, invoice e-mailed from its page, outbox, invoice's log); statements and customers e2e green
- [ ] Owner: the SMTP account or mail provider and the sender address; OK to add the mail library for live sending (TODO)

## Phase N — Warehouse ✅ (N1 ✅ N2 ✅ N3 ✅ N4 ✅ N5 ✅)

Spec §4: pick, pack and ship in the ERP so the warehouse does not need Cin7. Cin7 checked read-only on 2026-11-07 (sale SO-66097, Fulfillment #1): **Pick** (product, batch / serial, expiry, unit, quantity, location — Cin7 writes a bin as "location: bin"; Expert Safety's lines show only "Expert Warehouse", so no bins are used today) → status *To pack*; **Pack** (product, location, quantity, **Package #**) → *To ship*; **Ship** (ship date, carrier, tracking number, tracking URL, package #), with Undo. Cin7's Availability export has a **Bin** column.

### N1 Locations and bins ✅
- [x] **Bins** per warehouse (`V51`, `BinService`): code, **walk order** (the order a picker walks; new bins go last), active / inactive (not while holding stock), delete only if never used; a **default bin per product** per warehouse
- [x] **Stock per bin** as a ledger of its own (`bin_movements`), never changing a warehouse's stock — what no bin holds is *unbinned*: stock **coming in** (receipt, return, transfer, adjustment) goes into the product's default bin; stock **leaving** is taken from the unbinned stock first, then the bins, last in the walk first (picking, N2, takes it out of its bins before it ships); bins never hold more than the warehouse has
- [x] **Bin to bin** moves (or into / out of no bin), never more than a bin holds; **opening bins from Cin7's Availability export** (rows with a Bin: the bin made if new, set to OnHand, capped at what the warehouse has, preview first)
- [x] **Inventory → Bins**: the warehouse's bins in walk order with units and products, add, reorder, (in)activate, delete; a product's stock per bin, its default bin, moves; the Cin7 import. Seeing needs *Product - list*, changing *Stock transfer*, importing *Data import*
- [x] Tests: `BinTest` (2) — walk order and duplicate codes; unbinned stock, then the default bin receiving; a move and one too large; 6 leaving take the 4 unbinned then 2 from the last bin; inactive / delete rules; Cin7 bins import (preview writes nothing, new bins, capped at the warehouse's stock, unknown SKU warned); another organisation sees nothing

### N2 Pick lists ✅
- [x] **Pick lists** (`V52`, `PickPackService`) for one order or a **wave** of orders of one warehouse: each line's quantity allocated and not yet picked (nor on an open list), **taken from its bins in walk order** (what open lists have not promised), the rest from unbinned stock; lines **sorted by the walk**, then SKU; **printable PDF** (bin, SKU, product, order, quantity, a box to tick)
- [x] **Confirming** a pick takes the quantities out of their bins (into the packing area); a short pick leaves the rest for a new list; cancel an open list; picked by hand or by **scanning** (N4): a barcode or SKU counts on the next line of that product, a product not on the list is refused ("wrong product"), never more than to pick; confirm uses what was scanned, or everything when nothing was
- [x] **Sale → Pick & pack**: open orders to tick into a wave, the pick lists (to pick / picked / cancelled) with their PDF; a pick list page with a big scan box (a handheld scanner types the code and Enter), lines turning green when picked, Confirm / Cancel / Print
### N3 Pack and ship ✅
- [x] **Cartons** per order (Cin7's Package #) with weight and size: no more of a line than picked and not yet packed (none given: everything picked, in one carton); a **despatch note PDF** (deliver-to, reference, each carton's contents)
- [x] **Ship** the packed cartons: one shipment through the existing fulfilment (part shipments and backorders as D2; invoice on shipment, Shopify fulfilment as before) with carrier and tracking number; the cartons marked shipped with their shipment
- [x] The order's **warehouse stage** beside its fulfilment status: Authorised → Picking → Picked → Packed → Shipped (what is left to ship decides); a **Warehouse card** on the sale order: stage, picked / packed / shipped per line, cartons, make pick list, pack into a carton, ship cartons, despatch note
- [x] Cin7's **Auto Pick** / **Auto Pick + Pack** on the sale task import (J): the imported order arrives picked or packed (not enough stock: an error, or backordered when that is ticked)
- [x] Pick / pack / ship need the sale permissions (*Sale - list* to see, *Sale - add / edit* to act)
- [x] Tests: `PickPackTest` (3) — a wave over two orders in walk order (A-01, A-01, B-01, then no bin), PICKING, nothing more to pick while on the list, the PDF; picked: out of the bins, PICKED; packing more than picked refused, two cartons, PACKED, despatch note; shipped with tracking: SHIPPED, stock down, the other order's picked stock waiting unbinned; part stock (5 of 8) picked, packed, shipped, 3 backordered, stage back to Authorised; scanning: wrong product, barcode, SKU in any case, picked in full; Auto Pick + Pack on import → PACKED; another organisation sees nothing. E2E PICK-001 (pick list from Pick & pack, scanning with a wrong product refused, confirm, pack and ship from the order)
- [ ] Later: undo a pick (put back into its bins), choose bins by hand on the pick list, carton labels (with the couriers, phase O)

### N4 Mobile warehouse screen ✅
- [x] **Inventory → Scanner (phone)** (`/scan`): big Pick / Receive / Count buttons and one scan box; a handheld scanner acting as a keyboard types the code and Enter, matched by **barcode or SKU** (any case)
- [x] **Pick**: the open pick lists, each opening its scan page (N2); **Receive**: an ordered purchase order, scan what arrived (a product not on the order refused), "Receive n scanned" books one receipt through the existing receiving (idempotent, over-deliveries flagged as before); **Count**: the stock takes being counted, scanned per bin
- [x] Camera scanning not built: it needs a library (TODO, needs the owner's OK)

### N5 Stock adjustments and stock takes ✅
- [x] **Adjustment reasons** (`V53`, `StockAdjustmentService`): damage, write-off, sample, found, theft / lost, other (and *stock take* for counts), kept on the movement (`stock_movements.reason_code`); valued at the product's **FIFO cost** in that warehouse (else its cost price); from or into a **bin** (found stock goes straight into the bin it was found in); never below zero, in the warehouse or the bin
- [x] **Approval over a set value**: an organisation's approval value (empty = none); an adjustment worth more waits *Waiting for approval* without moving stock; a manager or admin **approves** (posted then, the stock checked again) or **rejects**; staff cannot approve or change the value
- [x] **Stock takes** (`StockTakeService`) for a whole warehouse (everything it holds and every product with a bin there), a **category** or **chosen products**; starting **freezes** what each bin (and the unbinned stock) should hold, at its cost; one stock take per warehouse at a time
- [x] **Counting** on screen (type a line's count) or by **scanner** with the bin being counted; a product or bin not on the list gets a line (expected what is there now); the **variance** and its value per line and in total as you go; lines not counted are left alone
- [x] **Posting** (a manager): each variance (count − frozen) becomes a *stock take* adjustment without the approval step, outs first; stock that moved while counting (a shipment) is **warned about**; nothing taken below what is there now; cancel instead
- [x] Journals follow the existing Xero mapping (adjustment in: Dr inventory / Cr stock discrepancy; out the other way, at FIFO cost), in the ledger and the export
- [x] **Inventory → Adjustments** (new adjustment, the approval value, waiting adjustments with Approve / Reject, the history) and **Inventory → Stock take** (start, the list with counted lines and variance value, a stock take's page with the scan box, Post / Cancel). Seeing needs *Product - list*, adjusting and counting *Stock adjustments*, approving and posting a manager or admin; all audit-logged
- [x] Tests: `StockCountTest` (3) — unknown reason, below zero in the warehouse and in a bin refused; damage posted from a bin at FIFO cost with its reason and its journal (£8); found stock into another bin; over £50 waits, under posts, staff cannot approve or set the value, a manager approves, a posted one cannot be rejected; a full stock take freezes per bin, a second one refused; barcode and SKU scans, typed counts, a new bin line; variance value £20; posting by staff refused, by a manager: 3 adjustments, a warning for the boot shipped while counting, stock and bins right; a partial take with nothing counted changes nothing; cancel; another organisation sees nothing. E2E COUNT-001 (approval value, posted and waiting adjustments, approve; a stock take started for one product, scanned on the phone page, posted)
- [ ] Later: camera scanning (library), a printed count sheet, recounts of single lines, batches and serials in counts (with phase P)

## Phase O — Couriers ✅ (against a fake courier)

Spec §4 / §7: book shipments, print labels and send tracking from the ERP. Built against a fake: which couriers and accounts Expert Safety uses is an owner question (TODO).

- [x] **Courier client** (`integration/courier`): services and prices for some parcels, book (tracking number, tracking link, label PDF), the label again, tracking, cancel; `APP_COURIER_MODE=fake|live`, default fake. **Fake courier**: Royal Mail Tracked 48 / 24 (up to 20 kg), DPD Next Day and Parcelforce Express 48 (30 kg), Evri Standard (15 kg), priced per carton plus per kg, tracking numbers in each carrier's style, a real 100 × 150 mm **test label** PDF marked TEST; live mode refuses clearly until live clients are built for the couriers the owner names
- [x] **Book at the pack step** (`V54`, `CourierService`): the order's packed cartons not yet booked (each needs a weight) → the services that can take them, cheapest first → book: carrier, service, price, consignment, tracking number and link, the label kept with the booking, the cartons on the booking; the courier's errors shown as they are, nothing saved
- [x] **Cancel** a booking not shipped yet (the courier refuses once collected): its cartons are free to book again or ship by hand; **Track**: collected, in transit, delivered (delivered marks the booking delivered)
- [x] **Shipping** the cartons (N3) uses the booking: the carrier and service, tracking number and **tracking link** go on the shipment (`fulfilments.tracking_url`), so the **Shopify fulfilment** (7.3) gets the link too; the booking is marked shipped with its shipment. Typed carrier / tracking still work without a booking
- [x] **Tracking e-mail** (phase M template *Tracking*: carrier, tracking number, `{{tracking_url}}`): "E-mail the tracking to the customer" on shipping (ticked by default) sends it to the customer's default contact after the shipment is saved — a missing address is reported, never stops the shipping; it shows in the order's e-mails and the outbox
- [x] The order's **Warehouse card**: Book courier (service with price and days), bookings with tracking link, status, Label, Track, Cancel booking; "Ships with … tracking …"; the e-mail result. Needs the sale permissions like pick / pack / ship; audit-logged
- [x] Tests: `CourierTest` (2) — services cheapest first; DPD booked (tracking, £7.58, a PDF label, the carton on it), nothing more to book; cancelled, the carton freed, Royal Mail booked; shipped: carrier, tracking number and link on the shipment, booking shipped, cannot be cancelled; tracking e-mail to the default contact in the order's e-mails; tracked to delivered; an unweighed carton refused; a 25 kg carton only DPD / Parcelforce, Evri refused; the courier down (its message, nothing booked); collected cannot be cancelled; another organisation sees nothing. `ShopifyFulfilmentSyncTest`: the tracking link reaches the Shopify fulfilment payload. E2E COURIER-001 (book, cancel, rebook, ship, e-mailed, tracking on the shipment)
- [ ] Owner: the couriers and accounts in use, then live clients (or one shipping hub) — TODO
- [ ] Later: tracking polled on a schedule, collection booking, customs data for non-UK parcels, label printers (ZPL)

## Phase P — Product master gaps ✅ (P1 ✅ P2 ✅ P3 ✅ P4 ✅ P5 ✅ P6 ✅)

Spec §1: what a PPE product needs beyond Cin7's fields.

### P1 Safety compliance per SKU ✅
- [x] **Compliance per SKU** (`V55`, `ProductComplianceService`): EN / ISO standards, CE marked, UKCA marked, protection class (Category II, S3 SRC…), certificate number, issuing (notified / approved) body, certificate expiry, notes — **never taken from another product of the family**: each size or colour holds its own, because a certificate names the models it covers
- [x] **The certificate PDF** (or a JPEG / PNG scan) attached to the SKU; a new one replaces the old; files are checked by their bytes, not their name (a renamed file is refused), up to 10 MB, kept in the database with the product (`product_files`, shared with P4)
- [x] **Certificates expiring** (Inventory → Certificates expiring): within 30 / 60 / 90 / 180 / 365 days and those already expired, soonest first, with the days left and whether the PDF is attached
- [x] **Product CSV**: the export has Standards, CEMarked, UKCAMarked, ProtectionClass, CertificateNumber, CertificateIssuer, CertificateExpiry after Cin7's columns; the import reads them (columns left out keep what the product has; a date as 2027-03-31 or 31/03/2027, a bad one warned and left)
- [x] The product page's **Compliance** section (with an expiry badge: green, amber within 60 days, red expired) and **Images and files** section (P4); seeing needs *Product - list*, changing *Product - add / edit*; audit-logged
- [x] Tests: `ProductComplianceTest` (3) — saved on one glove size, the other size of the family has nothing; the certificate replaced; expiring within 30 days: the expired boot (−5 days) then the glove, not the 90-day one, with / without file; CSV export with the values, import of a new SKU and changed columns (others kept), a bad date warned; files: a fake PDF and a PDF posing as an image refused, the type read from the bytes, images ordered, made main, deleted; another organisation sees nothing. `ProductImportServiceTest` still green. E2E COMPLY-001 (compliance saved, certificate attached, image uploaded, in the expiring report)

### P2 Kits and bundles ✅
- [x] **Kit** product type (`V56`, `KitService`; Cin7: bill of materials, kit): made of **components** (stocked products, each once, a quantity per kit; not itself, not a service or another kit); a kit **holds no stock of its own** (any stock movement on it is refused); a product that ever moved stock cannot become a kit, and a kit made something else loses its components; components cannot change while open orders still have the kit to ship
- [x] **Its stock is what the components make**: per warehouse, the whole kits the least-covered component makes — on hand from their stock, available after what open orders reserve; shown on the product page (Components section, Stock section) and **sent to Shopify** as the kit's stock (the stock sync and the product sync)
- [x] **Selling a kit** reserves its components (stock overview, reorder, dashboard and Shopify see them reserved); **allocation** gives a kit line the whole kits its components cover, first confirmed first served with the other orders wanting the same components; **shipping** takes each component at its own **FIFO cost** against the kit's line (the kit's cost of goods is their sum, so profit reports are right); a **returned** kit puts its components back at the cost they left at
- [x] **Picking** a kit line lists its components, each from its own bins in walk order; a short pick counts the whole kits picked (3 gloves of a kit of 2: 1 kit); packing and shipping in kits
- [x] Product form: Type Stock / Service / **Kit**; product CSV Type "Kit" creates a kit (an existing product's kit status is changed on its page only, warned); a product edited without a type keeps its type
- [x] Tests: `KitTest` (3) — component rules; no stock of its own; makes min(10 / 2, 3 / 1) = 3; two kits reserve 4 + 2, one left (and for Shopify); a second order gets 1, 1 backordered; shipped: components out, cost £18 (4 × £2 + 2 × £5, FIFO); a returned kit puts 2 + 1 back, the glasses at £5; picked as components from bins in walk order, a short pick = 1 kit, the rest on a new list, packed and shipped; a stocked product cannot become a kit, a kit made stock loses its components; another organisation sees nothing. E2E KIT-001 (components on the product page, what the stock makes, two kits shipped take the components)
- [ ] Later: assembling kits into stock (Cin7's assembly BOM, built ahead), a kit's own price list rules, kits inside kits

### P3 Units of measure ✅
- [x] **Extra units per product** (`V59`, `ProductUnitService`; the product page's *Units* section): name, how many of the product's own unit it holds (Pair = 2, Box of 100, Carton of 1,000), barcode, its own sell and buy price (empty: the product's price × holds); not the product's own unit or 1, each name once; a unit on open orders cannot be removed or changed
- [x] **Sell or buy in any unit**: a sale or purchase order line keeps its unit (`unit_name`, `unit_factor`), its quantity and price are per that unit (the unit's own price, else the customer's price × holds — "Retail (Box of 100)"; the same for purchase costs); the order forms offer the unit under the product; order pages, invoices, bills and their PDFs show "3 Box of 100"
- [x] **Stock and FIFO stay in the product's own unit**: reservations, allocation (whole units only: 250 make 2 boxes of 100), pick lists (picked in the own unit, whole boxes counted), shipping (3 boxes = 300 out at FIFO), returns, discontinued checks, receiving (2 cartons = 2,000 in at a thousandth of the carton cost), on order (stock pages, reorder), supplier returns and bill cost matching (a bill at £160 a carton corrects the layers to £0.16 each)
- [x] Tests: `UnitOfMeasureTest` (2) — unit rules; 2 cartons on order = 2,000, received at £0.15, billed at £160 → £0.16; 3 boxes at the box's own £45, a carton at 1,000 × £0.50, 300 reserved, shipped at £48 cost, a box returned = 100 back; a unit on an open order cannot change, an unknown unit refused; 250 in stock allocate 2 of 3 boxes, picked 200, a short pick of 150 = 1 box, shipped 100. E2E UOM-001 (unit added on the product page, 2 boxes sold take 20)
- [ ] Later: scanning a unit's barcode (counts the unit's quantity), units in the Shopify sync (Shopify has no units: variants per pack size), sales reports by unit (they count order lines as entered)

### P4 Images and attachments ✅
- [x] **Several images per product** (the first is the main one: Make main), **datasheets**, **declarations of conformity**, the certificate (P1) and other files on the product page's *Images and files* section (`product_files`, built with P1): checked by their bytes, up to 10 MB, per SKU, deleted one by one, audit-logged
- [x] **Images sent to Shopify** in the product sync (`V58`): each image not yet on that Shopify product goes once — Shopify's staged upload (`stagedUploadsCreate`, the file posted to Shopify's storage) then `productCreateMedia` — main image first, a family's images on its family product; a failed image is reported in the sync's problems and tried again next time, the product stands; a product rebuilt as a new Shopify product gets its images again
- [x] Tests: `ShopifyProductSyncTest` — two images uploaded (multipart with Shopify's parameters) and attached to the product, the main one first, nothing sent again on the next sync; `ProductComplianceTest` covers the files. E2E COMPLY-001 uploads an image
- [ ] Live Shopify: images on the real dev store (TODO, test later)

### P5 Landed cost ✅ (built, off until the owner decides)
- [x] **Setting** (`V60`; Inventory → Adjustments, managers): freight and duty on supplier bills are **an expense** (off, the default, as today) or **added to the stock cost, by value** or **by quantity**
- [x] On: when a bill is issued, its charges (freight, duty…) are **spread over the received units its lines priced** — by value (units × cost) or by units, the last share taking the pennies — and added to those **FIFO layers**; the part of a layer already sold goes to **cost of goods** (like a bill price change); the charge is **posted to inventory** (ledger and Xero export) instead of purchase charges; a **cancelled bill** takes it back; the FIFO rebuild repeats it (decided per charge when issued: changing the setting later does not move old bills); nothing received yet: the charge stays an expense
- [x] Tests: `LandedCostTest` (3) — off: freight stays an expense and on purchase charges; by value: £30 over £100 + £100 → helmets £11.50, gloves £5.75, £7.50 into cost of goods for the 5 already sold, posted to inventory, cancelled back to £10 / £5; by quantity: £1 a unit; bad mode refused, staff cannot change it. E2E LAND-001 (switched on from the screen, £20 freight on 10 at £4 → £6)
- [ ] Owner: switch it on or not, by value or by quantity (TODO)

### P6 Product lifecycle ✅ (done before P3–P5: small and on its own)
- [x] **Active / Discontinued / Archived** (`V57`): Archived is the existing *Deprecated* (Cin7's name; hidden, not sent to Shopify); **Discontinued** is new: still listed, offered on orders and sent to Shopify, **never on the reorder list**, and **sold only while stock lasts** — confirming an order needing more than the warehouse has free (on hand less other orders' reservations) is refused with what is left; no backorders
- [x] Product form, list filter and badges (amber Discontinued, grey Archived); product CSV Status ACTIVE / DISCONTINUED / DEPRECATED (Archived accepted); "Deprecate products not in the file" archives discontinued products missing from the file too
- [x] Tests: `ProductLifecycleTest` (2) — 3 of 5 confirmed, another 3 refused ("2 left"), the last 2 confirmed, then "0 left"; offered in dropdowns, not on the reorder list though below its minimum; CSV Archived / Discontinued in and out. E2E LIFE-001; products e2e green (the list says Archived)

## Phase Q — Smaller items before cutover ✅ (Q1 ✅ Q2 ✅ Q3 ✅ Q4 ✅ Q5 ✅ Q6 ✅)

### Q1 Credit hold stops the shipping ✅
- [x] A customer put on credit hold (by hand, over the limit or overdue) **after** the order was confirmed now stops the **shipping** of it too — from the order, pick and pack's Ship, imports — with the hold's reasons; a manager or admin can ship anyway with a reason (kept on the order as "Confirmed or shipped past a credit hold", audited); orders confirmed past a hold and Shopify orders (paid) ship as before
- [x] Tests: `CreditTest` — confirmed, then held: shipping refused, staff cannot override, a manager ships with a reason. E2E CREDIT-002; CREDIT-001 green

### Q2 Own number series for hand-typed supplier invoices ✅
- [x] A supplier invoice typed by hand (no purchase order) is numbered from its own series, **BILL-1, BILL-2…** (`V61`), not the sales invoices' INV-; prefix and next number in Settings → Organisation; bills of purchase orders keep the supplier's own number as before
- [x] Tests: `ListSearchTest` — two hand-typed bills BILL-n, BILL-n+1, a sales invoice still INV-; settings tests green with the older settings shape. E2E BILL-NUM-001

### Q3 Supplier credit for the price lowers the FIFO cost ✅
- [x] A supplier credit note line **without goods sent back** (a price agreed lower, a discount after the bill) takes its amount off the cost of the units that bill line priced (its matched FIFO layers, by units); the part already sold lowers cost of goods; the credit note still credits inventory in the ledger, so stock value and the ledger agree; units not received yet take nothing; the FIFO rebuild repeats it
- [x] Tests: `PurchaseLedgerTest` — 3 vests at £65.50, one vest's worth credited with the goods kept: stock value £131.00, nothing sent back, still £131.00 after a rebuild; goods sent back as before

### Q4 Returns without a credit note in *All COGS* ✅
- [x] *All COGS* (the profit reports' Show COGS switch) now takes the cost of goods **returned to stock without a credit note** (taken back, no money) in the period off their order and product, so it agrees with *within the dates*; returns with a credit note stay on the credit note as before
- [x] Tests: `SalesProfitTest` — 3 helmets invoiced at £6 cost, one back without a credit note: £12 in both modes; the existing profit tests green

### Q5 Settings in roles; several roles per user ✅
- [x] A **Settings** tick for roles: organisation and numbering, company details and logo, document templates, e-mail settings and templates, price tiers, payment accounts, Xero export accounts — no longer admin only; users and roles, API keys and FIFO rebuilds stay with admins; off in the Manager and Staff defaults (as before)
- [x] **Several roles per user** (`V62`, `user_access_roles`): their ticks add up; on the Users page the first role is chosen as before and others ticked ("also Costs"); no role = the old Manager / Staff defaults; a role's user count counts every user who has it
- [x] Tests: `PermissionTest` — Office (Settings, customers) + Warehouse (products, adjustments) add up, PERM_SETTINGS given; back to one role, then to the defaults; managers' defaults exclude Settings and Adviser. E2E ROLE-002 (two roles in the UI: Settings from one, the cost price from the other); ROLE-001 green

### Q6 P&L and Balance Sheet as PDFs ✅
- [x] **Export PDF** beside Export CSV on Accounting → Profit and loss and Balance sheet: the same columns (comparisons included), sections with their totals, gross / net profit, net assets and equity, the company name and the period, amounts as in Xero (thousands separated, negatives in brackets), page numbers (`financial` template, the existing PDF renderer — no new library)
- [x] Tests: `AccountantTest` — both PDFs made for the year (a comparison column on the balance sheet). E2E LEDGER-PDF-001

---

## Cross-cutting (done throughout)

- [x] **JWT authentication** — signing key from `APP_JWT_SECRET` (base64, ≥ 32 bytes; local value in git-ignored `.env`, see `.env.example`). Tokens survive restarts; the backend refuses to start without a valid key. Expiry 24 h
- [x] **401 → redirect to login** — axios interceptor + `AuthContext` validates token on mount via `/auth/me`
- [x] **402 → plan-limit-exceeded event** — dispatched globally, components can listen
- [x] **Server-side pagination** — all list endpoints return `Page<T>`; frontend uses `Pagination` component
- [x] **Category management** — products can be categorised; filter by category on products + stock overview
- [x] **Docker multi-stage build** — compiles Java inside Docker; `make up` = `docker compose up --build`
- [x] **DataSeeder** — seeds plans, default tenant, admin user, opening stock on first run
- [x] **Platform super admin and subscriptions** — the super admin (`admin`, made on first start; Expert Safety's own admin is `expertsafety`; password `APP_SUPERADMIN_PASSWORD` in `.env`) is in **no organisation and has no subscription** (`GET /subscription` → 204) and passes every role check. It sees only **Platform → Organisations**: every organisation with plan, subscription status, users / products / warehouses, and **gives the plan** (`PUT /tenants/{id}/subscription`, written to that organisation's audit log) or **switches an organisation off / on** (`PUT /tenants/{id}/active`: its people cannot log in, its API keys stop). An organisation's own admin now sees its plan but **cannot change it** (the self-service `POST /subscription/upgrade` is gone: there is no payment yet). Tests: `PlatformTest` (4), e2e PLAT-001; e2e organisations get their plan from the super admin (`make e2e` passes the password)
- [x] **API access per organisation, like Cin7 Core** (`V21`) — each organisation (= one subscription) has an **Account ID** (UUID on `tenants`); its admins make **Application Keys** under Settings → Integrations → **API** (name + role STAFF / MANAGER / ADMIN, at most 20 in use). Another system sends both in the headers `api-auth-accountid` and `api-auth-applicationkey` (Cin7's names) and works as that organisation only, with the key's role. The key (`ek_` + 32 random bytes) is shown once; only its SHA-256 is kept. Revoking a key, deactivating the organisation or a subscription that is no longer ACTIVE stops it at once. Keys never reach `/auth`, `/users`, `/subscription`, `/settings`, `/tenants`, `/integrations`, `/costing` (403: a person must log in). Last used is kept to the minute; audit entries name the key ("API: Warehouse scanner", actor INTEGRATION). Tests: `ApiKeyTest` (6), e2e API-001. Later: rate limits per key

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
| GET | `/customers/{id}/statement` | Statement (`type` ACTIVITY / OUTSTANDING, `from`, `to`): ledger entries, open items, ageing (G3) |
| GET | `/customers/{id}/statement/pdf` · `/statements/pdf?ids=` | Statement as PDF; ticked customers in one PDF |
| (in `/customers` body) | `addresses: [{type, defaultForType, line1, line2, city, state, postcode, country}]` | The address book (G4); left out = unchanged |
| GET/PUT | `/settings/payment-accounts` | Payment accounts (H1; PUT = the whole list, ADMIN) |
| POST | `/invoices/{id}/pay` | `{amount, paidOn, accountId, reference, method}`; overpaid sales invoice → customer credit (H1) |
| POST | `/invoices/{id}/use-credit` · `/invoices/{id}/allocate-credit-note` | Pay with customer credit / a kept credit note |
| POST | `/invoices/{id}/payments/{paymentId}/void` | `{reason}`; MANAGER/ADMIN |
| GET/POST | `/customers/{id}/credits` · `/customers/{id}/credits/{creditId}/void` | Customer credit; payment on account; remove unused (MANAGER/ADMIN) |
| GET | `/reports/aged-debtors?asAt=` · `/reports/aged-debtors/csv` | Aged debtors |
| POST | `/invoices/{id}/supplier-credit-notes` | Supplier credit note on a bill: `{number, date, lines[{invoiceItemId, quantity, returnGoods}], charges, settle, refundAccountId}` (H2) |
| POST | `/invoices/{id}/use-supplier-credit` · `/invoices/{id}/allocate-supplier-credit-note` | Pay a bill with deposits / a kept credit note |
| GET/POST | `/suppliers/{id}/credits` · `/suppliers/{id}/credits/{creditId}/void` · `/suppliers/{id}/open-credit-notes` | Supplier deposits |
| GET | `/reports/aged-creditors?asAt=` · `/reports/aged-creditors/csv` | Aged creditors |
| GET | `/reports/vat-return?from=&to=` · `/reports/vat-return/csv` | VAT return: nine boxes, by rate, documents (H3) |
| GET | `/reports/sales-profit?from=&to=&cogs=WITHIN_DATES\|ALL&groupBy=` · `/csv` | Sales profit: CUSTOMER, ORDER, REP, REP_CUSTOMER, REP_ORDER, PRODUCT (H4, MANAGER+) |
| GET | `/reports/inventory-movement?from=&to=&warehouseId=&categoryId=` · `/csv` | Inventory movement summary at FIFO value (H4, MANAGER+) |
| GET/PUT | `/accounting/settings` | Account mapping and export start date (H5; PUT ADMIN) |
| GET/POST | `/accounting/export/pending` · `/accounting/export` | What is ready; make the Xero zip (H5, MANAGER+) |
| GET/POST | `/accounting/exports` · `/{id}/file` · `/{id}/undo` · `/accounting/export/changed` | Export history, download again, undo, changed after export (H5) |
| GET | `/settings/permissions` · `/settings/roles` | Permission list; roles with ticks and user counts (I1, ADMIN) |
| POST/PUT/DELETE | `/settings/roles` · `/settings/roles/{id}` | Add, change, remove a role (I1, ADMIN) |
| PUT | `/settings/users/{id}/access-role` | Give a user a role (`accessRoleId`, null = default) (I1, ADMIN) |
| GET | `/customers/overdue` | Customers with overdue invoices and their next reminder |
| GET | `/customers/{id}/reminder` · `/customers/{id}/reminder/pdf` | The reminder they would get now / its PDF (nothing recorded) |
| POST | `/customers/{id}/reminder` | Print the reminder and record it on the overdue invoices (ADMIN/MANAGER) |
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
| GET/POST | `/settings/api-keys` | The organisation's Account ID and Application Keys / make a key (shown once) (ADMIN) |
| DELETE | `/settings/api-keys/{id}` | Revoke a key (ADMIN) |
| GET  | `/tax-rates` | The organisation's VAT rates (`usage` SALE / PURCHASE) |
| GET  | `/dashboard/reorder` | Products whose available stock + on order is at or below their minimum (`search`, `page`, `size`) |
| POST | `/import/sales`, `/import/purchases` | Cin7 sale / purchase task CSV (`stage` DRAFT / ORDER / INVOICE, `shipping` NONE / NOW / ALREADY, `warehouseId`, `apply=false` previews) (DATA_IMPORT) |
| GET  | `/import/logs`, `/import/{sales,purchases}/template` | Imports done / Cin7's template headings (DATA_IMPORT) |
| GET  | `/cutover/check` | Go-live check: the figures to compare with Cin7's (DATA_IMPORT) |
| GET  | `/health` | Public uptime check: app and database (200 / 503) |
| GET/POST/PUT/DELETE | `/ledger/accounts` | Chart of accounts (POST/PUT/DELETE: ADMIN); `/ledger/accounts/import` Xero chart file (`apply=false` previews) |
| GET/PUT | `/ledger/settings`, `/ledger/opening-balances` | Conversion date, account mapping, conversion balances (PUT: ADMIN) |
| GET/POST/PUT | `/ledger/manual-journals` | Manual journals; `/{id}/post`, `/{id}/void` (a draft is deleted) |
| GET  | `/ledger/trial-balance`, `/ledger/account-transactions`, `/ledger/journals` | Ledger reports (ACCOUNTING) |
| GET  | `/ledger/profit-and-loss`, `/ledger/balance-sheet` | Financial statements (`compare`, `step` MONTH / QUARTER / YEAR) |
| GET/PUT | `/ledger/periods` | Financial year end and lock dates (PUT: ADMIN) |
| GET  | `/bank/accounts` | Bank accounts with statement and ERP balances, items to reconcile |
| POST | `/bank/accounts/{code}/statements` | Import a CSV / OFX statement (`apply=false` previews) |
| GET  | `/bank/accounts/{code}/lines`, `/bank/accounts/{code}/unmatched` | Statement lines (`status`) with suggestions / ledger items not reconciled |
| POST | `/bank/lines/{id}/match`, `/create`, `/transfer`, `/unreconcile`; DELETE `/bank/lines/{id}` | Reconcile a statement line |
| GET/POST | `/bank/transactions`; POST `/bank/transactions/{id}/void` | Spend / receive money and transfers |
| GET/PUT/POST/DELETE | `/vat/mtd`, `/vat/mtd/vrn`, `/vat/mtd/connect`, `/vat/mtd/callback` | Making Tax Digital connection (changes: ADMIN) |
| POST | `/vat/mtd/obligations`, `/vat/mtd/submit` | HMRC's VAT periods; submit a return (ADMIN, declaration) |
| GET/POST | `/vat/review`, `/vat/returns`, `/vat/returns/manual` | Review a period; returns filed; record one filed elsewhere (ADVISER) |
| GET/PUT/DELETE | `/mail/settings`, `/mail/templates/{type}` | E-mail settings and templates (changes: ADMIN) |
| POST | `/mail/preview`, `/mail/send`, `/mail/statements`, `/mail/reminders` | Compose / send one e-mail; bulk statements; overdue reminders |
| GET  | `/mail/outbox` (`docKey`), `/mail/outbox/{id}/attachment` | The outbox and each document's e-mails |
| GET  | `/ledger/export/{trial-balance,profit-and-loss,balance-sheet,account-transactions,general-ledger}.csv`, `/ledger/export/year-end` | CSV exports and the year-end pack zip (ACCOUNTING) |
| POST | `/stock/import` | Cin7 stock file → stock in one warehouse set to the file's OnHand (`warehouseId`, `apply=false` previews) |
| GET  | `/audit-log` | Audit log (filterable) |
| GET  | `/tenants` | Every organisation with its plan and usage (SUPER_ADMIN) |
| GET  | `/subscription` | The organisation's subscription + usage (read only; 204 for the super admin) |
| GET  | `/plans` | All available plans |
| PUT  | `/tenants/{id}/subscription` | Give an organisation its plan / status / end date (SUPER_ADMIN) |
| PUT  | `/tenants/{id}/active` | Switch an organisation on or off (SUPER_ADMIN) |

---

## What to build next

Agreed order (each phase is done when its feature works, its tests are written, all existing tests pass, then this file is updated):

1. ~~**Persistent JWT / secret key**~~ ✅ done — see Cross-cutting
2. ~~**Phase 8 — Customers**~~ ✅ done — see Phase 8 (Cin7 layout + CSV import)
3. ~~**Phase 6 — Dashboard**~~ ✅ done — see Phase 6; brought to the functional spec in steps: ~~6.1 period, status counts, reorder, top/worst sellers~~ ✅ → 6.2 sales rep, VAT, bills due/overdue → 6.3 PO part receipts + supplier bills, revenue vs expense by month. Full plan: [`docs/roadmap.md`](docs/roadmap.md)
4. **Phase 7 — Shopify integration** ✅ (~~7.1 connection~~ → ~~7.2 order import~~ → ~~7.3 fulfilment~~ → ~~7.4 cancellations / refunds~~ → ~~7.5 reliability~~ → ~~7.6 stock~~, phase F). Shopify access tokens get their own encryption key, handled like `APP_JWT_SECRET`
5. Then: Stripe billing, notifications, custom fields (former Phase 4), advanced inventory, purchasing, reporting; production hardening throughout
