# Phase E — Purchasing (spec §3)

Plan for the purchasing module: what Cin7 does today, what the ERP has, the steps, and what "done" means for each.
Rules it follows: [`business-rules.md`](business-rules.md) (FIFO costing, VAT precedence, "on order" in the reorder rule).

## What the spec asks (Must unless marked)

- **Supplier records:** contacts, payment terms, currency, lead time, minimum order value, supplier SKU and cost per product.
- **Purchase orders:** create, e-mail as PDF, track part and full receipts.
- **Reorder suggestions:** quantities from min / max levels, sales velocity and lead time; raise POs in bulk.
- **Goods receiving:** receive against the PO, on screen (scanner later), flag over / short deliveries.
- **Supplier bills:** three-way match PO vs receipt vs bill, flag price differences.
- **Landed costs:** spread freight, duty and import charges across the received lines.
- *Should:* backorder-to-PO, multi-currency (EUR / USD / CNY, rate recorded, cost in GBP), supplier price list import.

## What Cin7 does (live account, read-only, 2026-10-30)

Seen on the purchase list (8,447 purchases, numbers up to **PO-19073**) and on **PO-19069** (Orn Clothing, *Partially received*) and **PO-19064** (Portwest, *Fully billed*). Screenshots: `~/Documents/expertsafety/design-pages/purchasing/`.

- **One purchase, several stages**, shown as icons on the list: order → bill (invoice) → stock received → paid, each green / amber / grey. The list also shows *Not billed / Fully billed*, *Not received / Partially received / Received*, *Unpaid*, and *Sent* (e-mailed to the supplier).
- **Header** (same three columns as a sale): supplier, contact, phone, vendor address; **terms** (30 Day EOM), required by, inventory account (631: Inventory Control), tax rule **20% (VAT on Expenses)**, tax inclusive; *blind receipt*, date, location (Expert Warehouse), ship-to address.
- **Order lines:** product, comment, **supplier SKU** (e.g. 3200.BK.L), unit, quantity, price, discount, tax rule, total. **Additional costs** (description, reference, qty, price, tax). Totals grid *order lines / additional costs / total × before tax / tax / total*. **Supplier deposit** and balance due.
- **Receiving is separate from the order** ("Invoicing and receiving #1"): a stock receipt with its own date and lines. PO-19069 ordered 18 units and received 13 on 30/10 → *Partially received*; "Add stock receiving" adds another receipt.
- **Bill (invoice):** the supplier's **invoice number** (006387333, shown as *Document #* on the list), invoice date, due date (from the terms), lines with account (631), **additional costs** (Shipping £12 + VAT on account 310), payments, "Verify invoice", **View in Xero**. PO-19064 was ordered at £393.00 and billed at **£407.40** because of the £14.40 shipping on the bill — Cin7 expensed it (account 310) rather than adding it to stock cost.
- **Credit notes** against a purchase, attachments (the PO PDF), history.
- **Reorder screen:** pick a supplier (or *Select all*), see its products below reorder level, choose quantities, click *Reorder* → one PO per supplier.

## What the ERP has

Purchase orders DRAFT → RECEIVED / CANCELLED; the supplier is a **free-text name** (not the supplier record); lines are quantity and unit cost only; **receiving is all-or-nothing**; no PO number series (shown as PO-{id}), no VAT, no additional costs, no required-by date. "Purchase" invoices exist but are typed by hand and not matched to the PO. Products already have a default supplier, supplier product code, cost price, minimum stock and reorder quantity (imported from Cin7: 15,616 / 20,588 products have a default supplier, 2,029 a minimum). The dashboard has a reorder list (available + on order ≤ minimum). Suppliers have terms, currency, tax number, one contact. **FIFO cost layers are decided but not built yet** (receipts don't create layers).

## Steps

Each step is built, tested and checked on the real data before the next one.

### E1 — The purchase order like Cin7 ✅
- PO linked to the **supplier record** (terms, currency, tax rule and contact copied, editable); **PO number series** per organisation continuing Cin7's (next = PO-19074 at cutover); order date, **required by**, supplier reference.
- Lines: **supplier SKU** and cost defaulted from the product (last cost or cost price), discount %, **purchase tax rule** (20% (VAT on Expenses), zero-rated, reverse charge…) by the same precedence as sales; **additional costs** (freight, duty…).
- Totals grid (lines / additional costs / total × before tax / VAT / total).
- Stages: DRAFT → **ORDERED** (Cin7's *Authorised*; editable until then) → closed by receiving; CANCELLED. Edit drafts.
- The PO page and list in the new layout: icons order → received → billed → paid, Documents column (receipts, bill numbers).

*Done when:* a PO for a Portwest product fills supplier SKU, cost and 20 % VAT by itself; the totals match Cin7's grid for PO-19069 (202.39 + 40.48 = 242.87); numbering continues from the setting.

### E2 — Goods receiving (part, short, over) ✅
- **Receipts** against an ordered PO, several per PO, each with its date, warehouse and quantities per line; stock comes **in at the PO line cost**.
- Per line: ordered, received, **outstanding**; receiving status *Not / Partially / Fully received*; "close short" ends a line without receiving the rest.
- **Over-delivery** and **short delivery** flagged on the receipt and the PO (see question 2).
- **On order** = ordered − received on open POs, used by the reorder list and product pages.

*Done when:* PO-19069's case works: 18 ordered, 13 received → partially received, stock +13, 5 still on order; a second receipt completes it; an over-delivery is flagged.

### E3 — Supplier bills and three-way match ✅
- **Bill** from the PO (like invoices from sale orders): supplier invoice number, invoice date, **due date from the supplier's terms**, lines prefilled with what was received and not billed yet, additional costs (e.g. shipping on the bill).
- **Three-way match** per line: ordered vs received vs billed quantity, PO price vs bill price; differences flagged (see question 3) and shown on the PO and the bill.
- Billing status *Not / Partially / Fully billed*; payments on bills (part payments), *Unpaid / Paid*; supplier credit notes.
- Purchase ledger basics: bills due / overdue (dashboard 6.3), per-supplier balance.

*Done when:* PO-19064's case works: bill 006387333 for the 5 units at 65.50 plus £12 shipping = £407.40 shows the £14.40 difference from the PO as an extra cost, not as a price difference; a bill for more than was received is flagged.

### E4 — Reorder suggestions → POs in bulk ✅
- Reorder page like Cin7's: suppliers with products to reorder, suggested quantity per product, choose / change, **create one draft PO per supplier** in one click.
- Suggested quantity: first Cin7's rule (reorder quantity, or up to minimum when there is none); then **sales velocity and lead time** (needs a lead time on the supplier — see question 5).
- *Should:* backorder-to-PO (a backordered sale line → the supplier's draft PO).

*Done when:* the reorder page lists the same products as the dashboard's reorder list, grouped by default supplier, and creating POs for two suppliers gives two draft POs with the suggested quantities.

### E5 — FIFO cost layers and landed costs
- Build the **FIFO cost layers** from the business rules: every stock-in creates a layer (receipt = PO cost), every stock-out consumes the oldest; COGS per shipment.
- **Landed costs:** freight / duty / import charges from the PO or the bill spread over the received lines (by value or by quantity) and added to those layers.
- A bill price different from the PO corrects the layer and the COGS already taken (cost adjustment, history never edited).

*Done when:* receiving 10 at £5 then 10 at £6 and shipping 15 gives COGS £80; £10 freight spread by value over a receipt raises those layers' unit cost accordingly.

### Later / with other phases
- **PO PDF and e-mail to the supplier** — with D5 (all PDFs together).
- Multi-currency and supplier price list import (*Should*).
- Importing open Cin7 POs — at cutover (phase J).

## Decisions (2026-10-30)
1. **No approval step:** whoever creates a PO can mark it ordered (Cin7's *Authorise*).
2. **Over-delivery:** accepted into stock and flagged on the receipt and the PO.
3. **Bill prices as in Cin7:** the stock cost follows the bill price; a bill line priced differently from the PO is flagged (three-way match).
4. **Charges on bills** (shipping, duty) stay an **expense**, like Cin7 today (account 310) — not added to stock cost. Landed cost (E5) is therefore reduced to the FIFO layers; spreading charges into stock cost is left for later if wanted.
5. **Reorder quantities:** Cin7's rule (reorder quantity, or up to the minimum); lead time, minimum order value and sales velocity later.
6. **Order:** the Cin7 flow — E1 order → E2 receiving → E3 bills → E4 reorder → E5 FIFO.
