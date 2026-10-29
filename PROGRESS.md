# Expert ERP — Project Progress

## Architecture

| Layer | Tech |
|---|---|
| Backend | Spring Boot 3.5, Hibernate 6.6, Spring Security (JWT), Spring Data JPA |
| Database | PostgreSQL 16 (Docker) |
| Frontend | Next.js 16 (App Router, React 19), React Query v5, Axios, Tailwind CSS |
| Infra | Docker Compose, multi-stage Dockerfile, Makefile (`make up`) |
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
- [x] **30 test cases**: AUTH (5), PRD (2), STK (3), PO (1), SO (7), SEC (5), MT multi-tenant (4), PLAN (2), AUD (1)

### CI
- [x] GitHub Actions: backend JUnit + Testcontainers job; end-to-end job (Docker Compose backend, built frontend, Playwright) with the HTML report uploaded as an artifact

### Bugs found by the tests and fixed
- [x] SKU / warehouse / category / supplier names were unique across **all** tenants (leftover global constraints) → `V3__per_tenant_uniqueness.sql`
- [x] Wrong-password error never shown (401 interceptor reloaded the login page) → login call excluded from the redirect
- [x] Unhandled errors surfaced as HTTP 403 (`/error` was secured) → `/error` permitted; DB constraint violations → HTTP 409
- [x] Sign-up trusted a client-supplied `role` (SUPER_ADMIN was blocked only by a DB check) → founders are always ADMIN; only a SUPER_ADMIN can grant SUPER_ADMIN; unknown role → 400; DB now accepts SUPER_ADMIN
- [x] Invoice list pagination never worked (wrong prop name) — caught by the CI type-check

---

## Cross-cutting (done throughout)

- [x] **JWT authentication** — random key per startup (all old tokens invalid after rebuild)
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
| GET/POST | `/warehouses` | List / create warehouses |
| GET/PUT/DELETE | `/warehouses/{id}` | Get / update / delete warehouse |
| GET/POST | `/categories` | List / create categories |
| GET/POST | `/suppliers` | List / create suppliers |
| GET/PUT/DELETE | `/suppliers/{id}` | Get / update / delete supplier |
| GET/POST | `/stock-movements` | List / record movement |
| POST | `/stock-movements/transfer` | Transfer between warehouses |
| GET  | `/stock-movements/export/csv` | CSV export |
| GET  | `/stock/overview` | Stock levels (paginated, filterable by category) |
| GET/POST | `/purchase-orders` | List / create POs |
| POST | `/purchase-orders/{id}/receive` | Receive PO → IN movements |
| POST | `/purchase-orders/{id}/cancel` | Cancel PO |
| GET/POST | `/sale-orders` | List / create SOs |
| POST | `/sale-orders/{id}/confirm` | DRAFT → CONFIRMED |
| POST | `/sale-orders/{id}/fulfill` | Ship given line quantities (no body = all remaining) → SALE_OUT movements |
| POST | `/sale-orders/{id}/cancel` | Cancel SO, or cancel remaining quantities if partly shipped |
| POST | `/sale-orders/{id}/returns` | Record customer return → SALE_RETURN_IN for restocked lines |
| GET/POST | `/invoices` | List / create invoices |
| GET | `/invoices/{id}` | Get invoice |
| POST | `/invoices/{id}/issue` | DRAFT → ISSUED |
| POST | `/invoices/{id}/pay` | Record payment |
| POST | `/invoices/{id}/cancel` | Cancel invoice |
| GET  | `/audit-log` | Audit log (filterable) |
| GET  | `/tenants` | List tenants (SUPER_ADMIN) |
| PUT  | `/tenants/{id}/toggle` | Enable/disable tenant (SUPER_ADMIN) |
| GET  | `/subscription` | Current subscription + usage |
| GET  | `/plans` | All available plans |
| POST | `/subscription/upgrade` | Switch plan (ADMIN) |

---

## What to build next

Agreed order (each phase is done when its feature works, its tests are written, all existing tests pass, then this file is updated):

1. **Persistent JWT / secret key** — tokens currently die on every backend restart; Shopify token encryption needs the same secret handling
2. **Phase 8 — Customers (minimal)** — a Customer entity instead of a free-text name on sale orders
3. **Phase 6 — Dashboard** — KPI cards and charts from aggregation queries
4. **Phase 7 — Shopify integration** (7.1 connection → 7.2 order import → 7.3 fulfilment → 7.4 cancellations/returns → 7.5 reliability → 7.6 stock sync)
5. Then: Stripe billing, notifications, custom fields (former Phase 4), advanced inventory, purchasing, reporting; production hardening throughout
