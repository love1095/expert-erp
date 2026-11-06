# Cutover runbook: moving an organisation from Cin7 Core to Expert ERP (phase J)

The functions are built (phase J). This page is the order to use them in. Do a **rehearsal** on a copy first (a restored
backup, or a test organisation), then the real switch over one weekend. Nothing here changes Cin7: everything is read or
exported from it.

## What the ERP has for the move

| Data | Where in the ERP | From Cin7 |
|---|---|---|
| Products, prices | Products → Import | Inventory → Products → Export |
| Customers, contacts, addresses, contract prices | Customers → Import | Sale → Customers → Export |
| Stock on hand per warehouse, at Cin7's value | Stock Overview → Import stock on hand | Inventory → Availability → Export (one location at a time) |
| **Open sale orders** | Settings → **Import sales & purchases** → Sale tasks: *Invoice status: No invoice yet*, *No picking* | Sale → Import CSV file → template; fill from Cin7's sale list / Sales report |
| **Unpaid customer invoices** | Sale tasks: *Authorised*, *Already shipped in the old system*, *Export imported tasks to Xero* **unticked** | the same template, with Payment rows for part payments |
| **Open purchase orders** | Purchase tasks: *No bill yet* (and Received rows for part receipts) | Purchase → Import CSV file → template |
| **Unpaid supplier bills** | Purchase tasks: *Authorised*, *Already received in the old system*, Xero **unticked** | the same template |
| Number series (SO / INV / CR / PO) | Settings → Organisation | Cin7's last numbers + 1 |
| Check the totals | Settings → **Go-live check** | Cin7's Availability, Aged Receivables / Payables, Backorder, Pending PO reports |

Import rules (Cin7's, plus safety): rows grouped by customer / supplier + **InvoiceNumber**; a customer or supplier
not found by name is created (as Cin7; the preview lists them, so check for typos); products must exist; a file is
imported **all or nothing**; **Preview** first; a number already used is refused, so a file cannot be imported twice;
every import is in the **Logs** tab and the audit log. *Already shipped / received* marks the lines done **without moving stock**: the stock already left
(or arrived) in Cin7 and is in the opening stock.

## Before the weekend
1. Server: the app on its own machine with HTTPS and a domain; `.env` with production secrets (`APP_JWT_SECRET`,
   Shopify key, `APP_SUPERADMIN_PASSWORD`).
2. Backups: cron `infra/backup.sh` daily (`make backup` by hand), copy `backups/` off the server; run
   `make restore-check` once to prove a backup restores.
3. Monitoring: cron `infra/monitor.sh` every 5 minutes on another machine (or an uptime service on `/api/health`).
4. Users: one login per person, roles set (Admin → Users → Roles), two-step sign-in required if wanted.
5. Document templates with the bank details and terms (Settings → Document templates).
6. Xero account mapping (Accounting export → settings).
7. **Rehearsal**: run every step below on a copy; fill the Go-live check; fix what does not match.

## Friday evening: stop Cin7
1. Staff stop entering in Cin7.
2. Export from Cin7: products, customers, stock (per location), open sale orders, unpaid invoices, open POs, unpaid bills,
   and the report totals (Availability, Aged Receivables, Aged Payables, Backorders, Pending Purchase Orders).
3. Note Cin7's last SO, invoice, credit note and PO numbers.

## Saturday: load and check
1. `make backup` (the empty-state backup to go back to).
2. Import products → customers → stock per warehouse.
3. Import open sale orders, unpaid invoices, open POs, unpaid bills (Preview, then Import).
4. Set the number series to Cin7's last numbers + 1.
5. **Go-live check**: type Cin7's figure on each line; every line must be green. Differences: fix and re-check.
6. Xero: set the **export start date** to Monday (the first day in the ERP), so nothing Cin7 already sent is sent again;
   turn **off** Cin7's Xero autosync.
7. Shopify: turn **off** Cin7's Shopify link, then connect the ERP's (Settings → Integrations) and push stock. Both on at
   once would import web orders twice and fight over stock levels.
8. `make backup` (the go-live backup).

## Monday: go live
- Everyone works in the ERP. Watch the first orders through: confirm → ship → invoice → Xero export.
- Keep Cin7 (read-only, subscription kept) for at least a month.

## Rollback plan (if something serious goes wrong in the first days)
1. Decide by an agreed time (e.g. Tuesday noon) whether to go back.
2. Turn the ERP's Shopify link off and Cin7's back on; turn Cin7's Xero autosync back on.
3. Re-enter in Cin7 what was done in the ERP since Monday: the audit log and Sales / Purchases lists list it.
4. Fix the problem, then repeat the cutover on a later weekend (restore the Friday backup, re-import).

## After a month
- Compare the ERP's Sales Profit Summary with Cin7's figures for a full month.
- Cancel Cin7.
