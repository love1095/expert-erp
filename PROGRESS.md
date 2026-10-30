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
- [ ] Stock on hand from Cin7 (opening balances) — until then every imported product has 0 stock and shows "Sold out"

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
| GET  | `/dashboard` | Dashboard figures for the last `days` days (default 30) |
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
3. ~~**Phase 6 — Dashboard**~~ ✅ done — see Phase 6
4. **Phase 7 — Shopify integration** 🔄 (~~7.1 connection~~ ✅ → ~~7.2 order import~~ ✅ → ~~7.3 fulfilment~~ ✅ → 7.4 cancellations/returns → 7.5 reliability → 7.6 stock sync). Shopify access tokens get their own encryption key, handled like `APP_JWT_SECRET`
5. Then: Stripe billing, notifications, custom fields (former Phase 4), advanced inventory, purchasing, reporting; production hardening throughout
