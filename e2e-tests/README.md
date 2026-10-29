# Expert ERP — end-to-end tests

Browser (UI) and API tests written with [Playwright](https://playwright.dev). Backend unit/integration tests live in `inventory-service/src/test` (JUnit + Testcontainers).

## Run

The app must already be running: backend + Postgres via `make up`, frontend via `npm run dev` in `inventory-app`.

```bash
cd e2e-tests
npm install
npx playwright install chromium   # first time only

npm test                # everything
npm run test:p0         # only P0 (critical) tests
npx playwright test tests/sale-orders          # one module
npx playwright test -g "SO-004"                # one test case by ID
npm run test:ui         # interactive runner (watch, step through, time-travel)
npm run report          # open the last HTML report
npm run catalog         # write test-catalog.json: every test case with its ID and tags
```

Point at another environment with `E2E_BASE_URL=https://staging.example.com npm test`. Never point it at production.

## How tests are isolated

Every test signs up its **own new tenant** (`E2E Org …`) through `/api/auth/register`, upgrades it to ENTERPRISE (unless the test sets `plan`), and creates two warehouses. Tests never share data, can run in parallel, and never touch real tenants. Test tenants do accumulate in the dev database.

## Conventions

- **Test case ID** at the start of the title: `SO-004 cancelling after a partial shipment ...`. IDs are stable; never reuse one.
  Prefixes: `AUTH`, `PRD`, `STK`, `PO`, `SO`, `SEC`, `MT` (multi-tenant), `PLAN`, `AUD`.
- **Tags**: priority (`@P0` critical … `@P3`) and module (`@sale-orders`, `@security`, …).
- **Seed through the API, act through the UI, verify both.** Use `api` (see `fixtures/api.ts`) to create products, stock and orders; click through the screen under test; check results with `api.stock(...)` etc.
- **Locate elements by role, label or test id** — never by CSS classes.
- **Screen steps used by several tests go in `fixtures/pages/`** (e.g. `SaleOrdersPage.ship(...)`), so a UI change is fixed in one place.

## Fixtures (`fixtures/test.ts`)

| Fixture | What you get |
|---|---|
| `page` | Browser page logged in as the tenant's ADMIN; `confirm()` dialogs auto-accepted |
| `api` | ADMIN API client for the same tenant |
| `session` | Token, user and password of that ADMIN |
| `warehouses` | `{ main, second }` created for the test |
| `plan` (option) | `test.use({ plan: 'FREE' })` to test plan limits |
| `loggedIn` (option) | `test.use({ loggedIn: false })` for login/logout tests |

Other helpers in `fixtures/api.ts`: `registerTenant` (a second tenant, e.g. for isolation tests), `addUser(api, 'STAFF')`, `anonymousApi`.
