# Expert ERP — Roadmap to replace Cin7 Core

What is **done** lives in [`PROGRESS.md`](../PROGRESS.md). This file is the plan: what is left, in which order, and how each piece is built.

Sources: the owner's *ERP System Functional Specification* (Must / Should / Could per Cin7 module; kept outside the repo) and the live Cin7 Core account, read through Claude in Chrome (read-only).

## Order

The spec's phase 1 ("Replace Cin7 Core") sets the order: build a module before the dashboard widgets that read its data.

| # | Phase | Contents | Unlocks |
|---|---|---|---|
| A | Wrap up current work | Shopify price-change sync test; commit the product catalogue work, then dashboard 6.1 | Clean starting point |
| B | Rules for the next modules | Stock allocation, costing (average or FIFO), VAT model; owner questions below | C–E built once, not redesigned |
| C | Cin7 stock migration | Stock on hand as opening balances; suppliers if missing | Real stock, Shopify not "Sold out", accurate reorder list |
| D | Sales (spec §2) | Quotes, VAT 20 % / 0 %, sales rep on each order, allocation + available stock, backorders, invoice on shipment, credit notes, PDFs | Dashboard 6.2 |
| E | Purchasing (spec §3) | Authorise, part / over / short receipts, supplier bills with three-way match, reorder suggestions → bulk POs, landed cost | Dashboard 6.3 |
| F | Shopify 7.4–7.6 | Cancellations and refunds, retries and monitoring, stock push | The integration that "cannot fail" |
| G | Customers and pricing (spec §5) | Price tiers, contract prices, credit hold, statements | |
| H | Finance and accounting (spec §6) | Sales / purchase ledgers, aged debtors and creditors, VAT report, COGS, Xero or QuickBooks sync | Spec phase 1 complete |
| I | Reports, permissions, search, templates (spec §8) | Core reports, cost prices hidden from non-owner roles, global search, document templates, 2FA | |
| J | Cutover | Open orders and balances, reconciliation, backups, monitoring, rollback plan | Switch off Cin7 |

G–I can partly run alongside D–F. Warehouse depth (bins, pick lists, barcode scanning, stocktakes, couriers) and automation rules are the spec's phase 2, after cutover.

### Dashboard (spec §8)
- 6.1 ✅ period presets, sales / purchase status counts, reorder list, top / worst sellers
- 6.2 sales by rep, revenue ex-VAT, bills due / overdue — after D
- 6.3 purchase orders not received / not invoiced, revenue vs expense by month — after E

## How each feature is built

1. Look at how Cin7 really behaves (Chrome, read-only) and what the spec asks.
2. Write acceptance criteria; list open questions and settle them **before** coding anything that touches stock, money or orders.
3. Compare with the code; short plan.
4. Backend (Flyway migration for any schema or enum change), then frontend.
5. Backend tests (Testcontainers) and Playwright cases; run both full suites.
6. Check it in the running app on the real (local) Cin7 data.
7. Update `PROGRESS.md` once it is done; commit on `main` when asked.

## Open questions

For the owner (from the spec's own list):
- Accounting package: Xero, QuickBooks Online or Sage?
- Couriers and courier accounts in use today?
- Shopify or the ERP as master for product descriptions and images?

For us, before the phase that needs them:
- Allocation (D): reserve stock on confirm, or on authorise? Do Shopify orders reserve on import?
- Costing (B): weighted average or FIFO? Landed cost spread by value or by quantity?
- VAT (D): rate per product (Cin7 `saleTaxRule`) or per customer (`tax_rule`), and which wins?
- Sales rep (D): copied from the customer's rep onto each order, editable per order? "Web Sales" for Shopify orders?
- Financial year starts 1 December (seen in Cin7) — make it a tenant setting?
