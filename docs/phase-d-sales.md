# Phase D — Sales (spec §2)

Plan for the sales module: what Cin7 does today, what the ERP has, the steps, and what "done" means for each.
Rules it follows: [`business-rules.md`](business-rules.md) (reserve on confirm, FIFO, VAT precedence, rep per order).

## What Cin7 does (live account, read-only, 2026-10-30)

Seen on the sales list and on sale **SO-66003** (AE Yates Ltd, *Backordered*).

- **One sale, several stages** shown as icons on the list and as panels on the order:
  Quote (optional — "Skip quote") → Sale order (*Authorised*) → Fulfilment(s) (*Pick → Pack → Ship*, more than one per order) → Invoice(s) → Payments → Credit note & restock.
- **Header:** customer, contact, phone, email, **reference** (the customer's PO number, e.g. PO-024297), billing address, **price tier** (e.g. Contract), **terms** (30 Day EOM), **sales rep** (required, e.g. Dwaine Silverblade), revenue **account** (200: Sales), **tax rule** (20% (VAT on Income)), *tax inclusive* tick box, location, date, ship-by, ship-to company / contact / address, shipping notes, **carrier** (Expert Van), comments.
- **Lines:** product, comment, quantity, price, **discount %**, **tax rule per line**, **margin %**, total, drop-ship. Plus *additional charges / services* (e.g. carriage).
- **Totals:** before tax 518.85, tax 103.77, total 622.62 (GBP).
- **Backorder:** 14 units ordered, the pick list holds the 11 in stock; the order shows *Backordered* and "3 to pick".
- **Invoicing:** invoice INV-118553 (507.00) already raised for what could be supplied, "115.62 to invoice" for the rest — invoices can come before shipping and cover part of an order.
- **Documents:** sale order PDF attached and e-mailed from Cin7.
- **List:** order #, date, customer, reference, documents (invoice numbers), ship by, total; filters "All sales but VOIDED", "… and rejected", "… and completed", archived quotes; tabs General / Accounting / Fulfillment.

## What the ERP has

Sale orders DRAFT → CONFIRMED → COMPLETED / CANCELLED with a separate fulfilment status; partial shipments (no pick / pack step); cancel remaining; returns with restock; Shopify import. Lines have quantity and unit price only. Invoices exist but are entered by hand, without VAT. Customers already store terms, tax rule, price tier, discount, sales rep.

**Missing:** quotes, VAT, discounts, sales rep and reference on the order, totals ex / inc VAT, margin, reservation / available stock, backorder status, invoices from the order, credit notes, PDFs.

## Steps

Each step is built, tested and checked on the real data before the next one.

### D1 — Order header, VAT and totals ✅
- Order gets: **customer reference**, **sales rep** (copied from the customer, editable; Shopify orders = *Web Sales*), **terms** (copied from the customer), ship-by, carrier, shipping notes.
- **Tax rates** per organisation, seeded for the UK with Cin7's names (20% (VAT on Income), Zero Rated Income, No VAT, 5%, exempt, reverse charge).
- Each line: **discount %** (customer's discount as default), **tax rule** chosen by the precedence rule (line override > product > customer > 20 %), rate and VAT amount **stored on the line**; overriding needs MANAGER or above and is audited.
- Order totals: before tax, tax, total; **margin %** per line from the product's cost (FIFO cost once costing exists).
- Dashboard 6.2 (first part): revenue ex-VAT, sales by rep.

*Done when:* an order for a customer with a 20 % product and a zero-rated product shows the right VAT per line and in total; a line override is refused for STAFF and audited for MANAGER; old orders keep their figures when a rate changes; the dashboard's revenue equals Σ lines ex-VAT.

### D2 — Reservation, available stock and backorders ✅
- Confirming reserves stock; **available = on hand − reserved** shown on Stock Overview, Products and the order lines; shipping or cancelling ends the reservation.
- Not enough available at confirm: allowed, order shows **Backordered** with the quantity short per line; shipping can only take what is on hand.
- Shopify orders reserve when imported (see business rules).
- Reorder list switches to Cin7's *available* + on order (expect ~446 like Cin7 once open orders exist).

*Done when:* two orders cannot both promise the last unit unseen (concurrent test); a backordered order shows the shortfall; cancelling releases it; stock overview's available matches on hand − Σ open confirmed quantities.

### D3 — Quotes ✅
- An order can start as a **quote** (optional, like Cin7's "Skip quote"); a quote reserves nothing; **convert to order** in one click; expiry date; quotes listed and filtered separately.

*Done when:* a quote converts into a draft or confirmed order with the same lines and prices, and expired quotes can't be converted without being renewed.

### D4 — Invoices and credit notes from the order ✅
- Create an **invoice from the order** (all or part of the lines, VAT carried over), numbered per organisation; the order shows *invoiced / to invoice* like Cin7.
- **Credit note & restock** from an invoice: return reasons, restock or write off (reuses the returns), refund or credit to account.
- Payments as today, on invoices and credit notes.

*Done when:* invoiced + to invoice = order total at every step; a credit note reduces the customer's balance and restocks what was marked so.

### D5 — Documents
- **PDFs** for quote, sale order, invoice, credit note (company details, VAT number, lines with VAT, totals). E-mail sending comes later with the e-mail integration (spec §7).

## Decisions (2026-10-30)
1. **Invoice timing:** both — invoices by hand from the order (as in Cin7 today) **and** an organisation setting to create one automatically on each shipment.
2. **Order numbers:** continue Cin7's series — a per-organisation sequence with a settable next number (set to Cin7's next number at cutover).
3. **Additional charges** (carriage etc.): in D1 — charge lines without a product, each with its own tax rule, in totals and invoices.
4. **Pick / pack:** later, with the warehouse work (spec §4); phase D keeps one-step shipping.
5. **Shopify VAT:** parked for phase F.
