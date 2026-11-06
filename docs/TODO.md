# TODO — things waiting on someone or something

Open items gathered from `PROGRESS.md` (where each one is explained under its step). Tick here and there when done.

## Needs the owner (an account, a login or a decision)
- [ ] **Xero developer app** at developer.xero.com: client ID + client secret for the Xero API link
- [ ] **Xero Demo Company (UK)** in the owner's Xero login: to test the Xero link before cutover (never the real Expert Safety Xero before cutover)
- [ ] **Purchase charges account**: which Xero account bill carriage / duty go to (407 Import Duty is a placeholder in the local data)
- [ ] **Cin7 read-only check**: PriceTier1 = Retail and PriceTier2 = Contract (assumed from the data)
- [ ] **Customer contacts import**: CustomerContacts_11_4_2026.csv is built but not run — load it?
- [ ] **Couriers** and courier accounts in use today (phase I / warehouse)
- [ ] **Financial year start** as an organisation setting (1 December assumed, seen in Cin7)

## Decide at cutover (phase J)
- [ ] **Xero export start date**: the day the ERP takes over from Cin7, after the opening stock import (local import of 30/10/2026, £99,818.37, would double Xero's stock if exported)
- [ ] Sale order / purchase order / invoice number series = Cin7's next numbers
- [ ] Connect the real Expert Safety Xero; switch off Cin7's Xero autosync the same day
- [ ] Check the sales profit figures against Cin7's for one month once orders are migrated

## Test later with live systems
- [ ] **Shopify (live store)**: a price change updates only that product (GLGH-WG-310RP-08 → expect 1 updated); stock push to the real dev store (would set stock for 13,959 linked SKUs); Shopify payouts (money paid into the bank, less fees) not matched to orders yet; Shopify-paid invoices tested with a fake order only
- [ ] **Xero, first real import** (Demo Company first): credit notes as negative lines in the invoice file, *Full VAT* (C79 import VAT) lines, penny differences where Xero recomputes discounted lines

## Phase K — ERP accounting (after cutover)
- [ ] Have an accountant review the ledger design (accounts posted per document) before building
- [ ] Run K alongside Xero for a few months at Expert Safety; move off Xero at a year end once the figures match
- [ ] HMRC Making Tax Digital: register as a software developer, pass the sandbox tests
- [ ] Choose an Open Banking provider for live bank feeds (cost per connection), or statement import only

## Build later (deliberately left for now)
- [ ] **Xero API link**: contacts, invoices, credit notes, bills, payments both ways, journals, retries — built against a fake Xero, live-tested with the Demo Company
- [ ] Payments on account (customer credit / supplier deposits) in the Xero files
- [ ] E-mailing documents, statements and reminders (with the e-mail integration, spec §7)
- [ ] Reports like Cin7's: drag-and-drop pivot, more fields, saved layouts; Sales Overview, Customer Orders by Product, Stock Value Inventory Aging (phase I)
- [ ] Roles (I1 done): Settings permissions inside roles (now admin only), several roles per user, Cin7's other ticks when those modules exist
- [ ] Expert Safety's real users: set their roles at cutover (in Cin7 all 5 are on FULL)
- [ ] API key rate limits (calls per minute per Application Key)
- [ ] Supplier credit notes for a price (no goods back) don't lower FIFO cost yet; landed cost only if wanted
- [ ] A return without a credit note is on no document for *All COGS*
- [ ] Cin7's deprecated tax rules (5 % income, Exempt, Reverse Charge) still active here; cash accounting VAT scheme not built
- [ ] Shipping not stopped for orders confirmed before a credit hold
- [ ] Hand-typed purchase invoices still take INV- numbers
- [ ] Shopify orders imported before the VAT split keep their old lines
- [ ] Product: Combined view (families), alternative products, product images, extra units of measure, channels, Merge products
- [ ] Accounting tab on sale orders; supplier tax-rule field on the supplier form
- [ ] Reorder by sales velocity / lead time / minimum order value; multi-currency; supplier price-list import
- [ ] Custom fields and dynamic forms
