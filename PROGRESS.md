# Expert ERP — Project Progress

## Architecture

| Layer | Tech |
|---|---|
| Backend | Spring Boot 3.5, Hibernate 6.6, Spring Security (JWT), Spring Data JPA |
| Database | PostgreSQL 16 (Docker) |
| Frontend | Next.js 14 (App Router), React Query v5, Axios, Tailwind CSS |
| Infra | Docker Compose, multi-stage Dockerfile, Makefile (`make up`) |

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
- [x] **Movement types** — `PURCHASE_IN`, `SALE_OUT`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `TRANSFER_IN`, `TRANSFER_OUT`
- [x] **Reference tracking** — `reference_type` (ORDER, TRANSFER, ADJUSTMENT, MANUAL) + `reference_id` on every movement
- [x] **Stock History (timeline)** — all movements per product, sorted by time, paginated
- [x] **Validation** — `InsufficientStockException` (HTTP 422) before any OUT movement; prevents negative stock
- [x] **Purchase Orders** — create order → receive → auto-creates PURCHASE_IN movements per line
- [x] **Sale Orders** — create order → fulfill → auto-creates SALE_OUT movements per line
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
| POST | `/auth/register` | Register org + first admin user |
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
| POST | `/sale-orders/{id}/fulfill` | Fulfill SO → OUT movements |
| POST | `/sale-orders/{id}/cancel` | Cancel SO |
| GET  | `/audit-log` | Audit log (filterable) |
| GET  | `/tenants` | List tenants (SUPER_ADMIN) |
| PUT  | `/tenants/{id}/toggle` | Enable/disable tenant (SUPER_ADMIN) |
| GET  | `/subscription` | Current subscription + usage |
| GET  | `/plans` | All available plans |
| POST | `/subscription/upgrade` | Switch plan (ADMIN) |

---

## What to build next

1. **Phase 4 — Custom Fields**: add `extra_fields JSONB` column to `products` table; let admins define schema; render dynamic form in React
2. **Billing integration**: Stripe webhook to drive subscription status changes
3. **Dashboard**: KPI cards (total products, low-stock count, recent movements, order totals)
4. **Notifications**: in-app or email for low-stock events (hook into `StockChangedEvent`)
