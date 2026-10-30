# Expert ERP — Business rules

Decisions taken before building stock import, sales, purchasing and finance (roadmap phase B, 2026-10-30).
Each rule says what was decided, then how it is built. Country: **UK**, currency **GBP**.

## 1. Stock quantities

| Term | Meaning | How it is known |
|---|---|---|
| On hand | Physically in a warehouse | Σ stock movements (IN − OUT), as today — never stored |
| Allocated | Reserved for confirmed sale orders, not yet shipped | Σ (quantity − shipped − cancelled) over the open lines of reserving orders |
| Available | Can still be promised | On hand − allocated (can go below 0 = backorder) |
| On order | Ordered from suppliers, not yet received | Σ open purchase order quantities − received |

**Decided**
- Stock is reserved when a sale order is **confirmed**; the reservation ends when the line ships or is cancelled.
- **Shopify orders reserve stock as soon as they are imported** (also the drafts kept for review, because the customer has already bought). May change later.
- Confirming is allowed when not enough is available: the shortfall is a **backorder**, shown on the order and the dashboard.
- Stock goes to orders **first confirmed, first served**, and **shipping is strict like Cin7**: a line ships at most what is allocated to it, never stock promised to an earlier order (decided 2026-10-30).

**Built as**
- Allocated is computed from order lines like on-hand is computed from movements: no allocation table, nothing to get out of step. Per product and warehouse.
- Confirm / import lock the product rows (as stock changes already do) so two orders cannot both take the last unit unseen.
- Shopify receives **available**, never below 0 (phase F).

## 2. Costing — FIFO

**Decided:** FIFO (every product in Cin7 is FIFO today).

**Built as**
- Every inbound movement carries a **unit cost** and opens a **cost layer** (product, warehouse, date, quantity left, unit cost):
  purchase receipt = PO line cost (+ landed cost later); opening balance / adjustment in = cost given, else the product's cost price; restocked return = the cost it left at.
- Every outbound movement takes from the **oldest layers first** and records what it took (layer, quantity, cost) — that is the **COGS** of the sale line, so gross margin per order, product and customer comes from real costs.
- A transfer moves layers to the other warehouse at their original cost and date.
- Stock can't go negative (already enforced), so FIFO always finds enough layers.
- Layers are cost bookkeeping only; quantities still come from movements, and layers can be rebuilt by replaying the movements in order.
- Stock value at any date = what is left in the layers at that date, per warehouse.
- A supplier bill at a different price than the PO corrects the layer (and the COGS already taken from it) through a cost adjustment, never by editing history.

## 3. VAT

**Decided**
- Which rate applies to a sale line, first match wins:
  1. an **explicit override on the order line** — only for users allowed to (MANAGER and above), with validation, audited;
  2. the **product's sale tax rule** (Cin7 `SaleTaxRule`);
  3. the **customer's default tax rule** (Cin7 `TaxRule`);
  4. the organisation default: 20 %.
- Purchases work the same way with the product's purchase tax rule and the supplier's tax rule.

**Built as**
- Per-organisation **tax rates**, seeded for the UK and named as in Cin7 so imports match: `20% (VAT on Income)` 20 %, reduced 5 %, `Zero Rated Income` 0 %, exempt, `No VAT` (outside the scope), reverse charge (purchases); purchase-side equivalents.
- The rate's code, percentage and VAT amount are **copied onto each line** when the order is made: later rate changes never change old orders or invoices.
- VAT per line, rounded half-up to the penny; totals = Σ lines.
- Override validation: the rate must exist and be active; a lower rate than the product's needs a reason; reverse charge only for a customer with a VAT number.

## 4. Sales rep

**Decided**
- A new order takes the **customer's sales rep**; it can be changed on the order.
- Shopify orders get the rep **Web Sales** (organisation setting).

**Built as:** a rep on each sale order (name, as Cin7 uses names), so "sales by rep" counts each order once, whoever the customer's rep is today.

## 5. Products and Shopify

**Decided:** the **ERP is the master** for product data (descriptions, images, prices, variants). Shopify gets it when **Sync products to Shopify** is clicked; edits made in Shopify are overwritten at the next sync.

**Rule for the plan:** if Shopify work starts taking too long, pause it and carry on with the other modules (phase F can move later).

## 6. Accounting — designed for any package

**Decided:** the package is not chosen yet; design so that **Xero, QuickBooks Online and Sage** can all be connected, and a file export works before any of them.

**Built as**
- The ERP keeps its own sales and purchase ledgers (invoices, credit notes, bills, supplier credits, payments, COGS / stock journals) and is never the general ledger.
- Each finished document is put on an **export queue** (outbox): one row per document and package, with status, the package's id once sent, and the last error. Sending is retried, never doubled (the package id / reference makes it idempotent), and a reconciliation page lists what failed or differs.
- One **adapter per package** behind one interface (connect, push contact, push invoice / credit note / bill / payment / journal, read back status); the first adapter is a **CSV export** that any package can import.
- **Mappings per organisation:** account codes (sales, COGS, stock, VAT, debtors, creditors, bank), tax rates → the package's tax codes, ERP customers / suppliers → the package's contacts.
- Making Tax Digital VAT returns stay in the accounting package; the ERP provides the VAT report by period.

## Still open
- Couriers and courier accounts in use (phase I / warehouse).
- Do Shopify prices include VAT? (UK stores usually do; decides how Shopify order lines are split into net + VAT.) — **parked, handled with the Shopify work (phase F)**
- Should the financial year start (1 December, seen in Cin7) be an organisation setting? Assumed yes.
