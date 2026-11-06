# Expert ERP — Next phases (for Claude Code)

Plan for the work left after phases A–K, written by the owner on 2026-11-07 after checking the functional spec
(`ERP System Functional Specification.docx`). Read `PROGRESS.md`, `docs/TODO.md` and the functional spec before
starting a phase. Build one phase at a time, in this order: **L → M → N → O → P → Q**.

## Rules for every phase
- **Never change anything in Cin7.** Cin7 may only be looked at (read-only) to copy how a screen or rule works. Write down what was checked and the date, as in earlier phases.
- **No live external systems.** Xero, couriers and e-mail are built against fake implementations behind an interface. A setting picks fake or live (`APP_<SYSTEM>_MODE=fake|live`, default `fake`). The live clients are finished later, when the owner provides accounts.
- Every schema or enum change gets a **Flyway migration**. Hibernate stays on `ddl-auto=validate`.
- Every new table carries `tenant_id`, and every query is tenant-filtered. **Add a test that another organisation sees nothing.**
- New screens follow the existing Cin7-like layout. New actions respect roles and permissions (I1), and every change is recorded in the audit log.
- **Done means:** the feature works; backend tests (JUnit + Testcontainers) and at least one Playwright e2e test are written; the full test suites are green; `PROGRESS.md` is updated (a new phase section, ticked items) and `docs/TODO.md` is updated (open items, items needing the owner).
- If something needs the owner (an account, a login or a decision), don't guess. Use a clearly marked placeholder and add the question to `docs/TODO.md` under *Needs the owner*.
- **Don't add new libraries without asking.** List any you need in `docs/TODO.md` under *Needs the owner's OK*.

## Phase L — Xero API link (against a fake Xero)
Goal: everything the CSV export sends can be sent through the API, with payments syncing both ways. Live use only needs credentials and the mode switched to live.

- **L1 Client layer** — a `XeroClient` interface with methods for contacts, invoices, credit notes, bills, supplier credit notes, payments, manual journals and allocations. A `FakeXeroClient`: in-memory, per tenant, returning Xero-style IDs, request and response shapes from Xero's public API docs; it can simulate a validation error, a rate limit (HTTP 429), an expired token and Xero being down. A `HttpXeroClient` skeleton with OAuth 2.0 (authorise, callback, token refresh, choosing the Xero organisation); tokens stored encrypted like the Shopify tokens.
- **L2 Mapping and sync queue** — a table linking each ERP document to its Xero ID, with status (pending / sent / failed), last error and attempts. The queue is processed in the background with retry and backoff; a failed item is shown and can be retried; the same item is never sent twice.
- **L3 Sending** — the same rules and account mapping as the CSV export (H5): contacts, invoices, credit notes, bills, supplier credit notes, COGS / stock journals, payments and refunds. Respect the export start date. Share the `accounting_exported` records with the CSV export, so a document sent by CSV is never sent by API, and the other way round. Only one mode (CSV or API) is active per organisation.
- **L4 Payments from Xero** — payments entered in Xero are pulled back and recorded against ERP invoices and bills. Polling in fake mode, webhooks ready for live.
- **L5 Screen** — on Finance → Xero: connection status, mode (fake / live), the queue with failed items and retry, and the history.
- **Tests:** each document type round-trips through the fake; a 429 is retried; an expired token is refreshed; no duplicates after a retry; switching between CSV and API exports nothing twice; another organisation is isolated.
- **Owner items for TODO:** the Xero developer app (client ID and secret) and the Demo Company, for the live test. Keep "live test on the Demo Company" in *Test later with live systems*.

## Phase M — E-mail
Goal: send quotes, invoices, credit notes, statements, overdue reminders, purchase orders and tracking from the ERP (spec §7, Must).
- **M1 Sending layer:** a `MailSender` interface. In fake mode, mails are stored and listed in an Outbox screen without being sent. The live mode is SMTP, with settings per organisation; the SMTP password stored encrypted.
- **M2 Templates:** editable e-mail subject and body per document type, with placeholders (customer name, document number, amount, due date). The PDF from the document templates (I4) is attached.
- **M3 Actions:** an E-mail button on quotes, invoices, credit notes, statements and POs, with the recipient taken from the customer or supplier contacts; bulk statements; overdue reminders using the G3 rules.
- **M4 Log:** each document shows what was sent, when, to whom and by whom.
- **Tests:** each document type produces the right mail and attachment in the fake outbox; a missing e-mail address gives a clear error; another organisation is isolated.
- **Owner items for TODO:** the SMTP account or mail provider, and the sender address.

## Phase N — Warehouse (spec §4)
Goal: pick, pack and ship in the ERP, so the warehouse doesn't need Cin7. Look at Cin7's pick / pack / ship screens read-only first.
- **N1 Locations and bins:** bins per warehouse, default bin per product, stock per bin. Receiving puts stock into a bin, and transfers can go bin to bin. Opening bins from Cin7's stock-per-location export if available.
- **N2 Pick lists:** per order, or as a wave over several orders, sorted by bin walk order; printable PDF; confirming the pick moves the order to Picked. Order status Authorised → Picking → Picked → Packed → Shipped, without breaking the existing fulfilment status.
- **N3 Pack:** cartons with weight and dimensions per order, a despatch note PDF; part shipments and backorders as in D2. Also Cin7's Auto Pick / Auto Pick + Pack options in the task import (J).
- **N4 Mobile warehouse screen:** a phone-friendly page for picking, receiving and counting; a handheld scanner acting as a keyboard scans into a focused field, matched by barcode or SKU. Camera scanning only if a library is approved.
- **N5 Stock adjustments and stock takes:** adjustment reason codes (damage, write-off, sample, found…); adjustments over a set value need approval; full and partial stock takes: freeze the expected quantities, count (scanner or screen), variance report, then post the adjustments. Journals follow the existing Xero mapping.
- **Tests:** pick → pack → ship on a part-stock order; wave sorting by bin; scanning the wrong product is refused; stock take variances post the right movements and journals; nothing goes below zero.

## Phase O — Couriers (against fake couriers)
Goal: book shipments, print labels and send tracking (spec §4 and §7, Must).
- A `CourierClient` interface: services and prices, create a shipment, get the label, tracking, cancel. Start with a `FakeCourierClient` returning a test label PDF and a tracking number.
- Live clients for the couriers the owner names (likely Royal Mail Click & Drop, DPD, Parcelforce, Evri), or one shipping hub — only once the owner gives the list and accounts.
- **Flow:** choose a service at the pack step; book, print the label; save the tracking number on the shipment; push it to Shopify (the existing 7.3 fulfilment) and e-mail it to the customer (phase M).
- **Tests:** booking through the fake; tracking reaches the Shopify fulfilment payload and the outbox; a cancelled shipment frees the order.
- **Owner items for TODO:** the couriers and accounts in use (already in TODO).

## Phase P — Product master gaps (spec §1)
- **P1 Safety compliance fields per SKU:** EN/ISO standards, CE/UKCA marking, protection class, certificate PDF upload and expiry date — never inherited from another product in the family; a report of certificates expiring in the next N days; in the product CSV import and export.
- **P2 Kits and bundles:** a kit made of components; available stock = the minimum over the components; selling a kit takes stock from the components at FIFO cost per component; shown on Shopify with that stock.
- **P3 Units of measure:** extra units per product (pair, box of 100, carton) with conversion factors; sell or buy in any unit; stock and FIFO in the base unit; prices per unit.
- **P4 Images and attachments:** several images per product, datasheets and declarations of conformity; images pushed to Shopify in the product sync.
- **P5 Landed cost** (spec: Must; earlier plan: only if wanted): freight and duty on a bill spread over the received lines (by value or quantity) and added to their FIFO cost. Ask the owner whether to switch it on (TODO).
- **P6 Product lifecycle:** Active / Discontinued / Archived; selling a discontinued product is blocked once its stock is zero.
- **Tests:** a kit's stock and cost; selling by the box lowers base-unit stock correctly; landed cost changes FIFO and COGS; certificates are per SKU and not inherited.

## Phase Q — Smaller items before cutover
From `docs/TODO.md` *Build later*, where they affect day-to-day work:
- Stop shipping for orders confirmed before a credit hold.
- Hand-typed purchase invoices get their own number series (not INV-).
- Supplier credit notes for a price change (no goods returned) lower the FIFO cost.
- A return without a credit note appears in *All COGS*.
- Settings permissions inside roles; several roles per user.
- PDF export of the P&L and Balance Sheet.

## Later (not before cutover)
Bank rules, cash coding and an Open Banking feed; pivot reports like Cin7's; reordering by sales velocity and lead time; multi-currency; custom fields and dynamic forms; API key rate limits; drop-ship; batch and expiry tracking; the B2B portal.

## Prompt to start a phase
> Read PROGRESS.md, docs/TODO.md, next-phases.md and the functional spec. Implement Phase L (or the next unfinished phase) following the Rules for every phase. Don't change anything in Cin7. Use fake clients for every outside system. Work in the sub-steps listed (L1, L2…). After each sub-step, run the tests, update PROGRESS.md and docs/TODO.md, and tell me what's done and what needs the owner.
