# Review and test plan: structure, every page, every function

Written 2026-11-08, after phases A–Q. Cutover is still a long way off. Before building anything new, we look at the whole ERP again: how it is organised, every page one by one, and every business flow from start to finish. Problems found go into a findings log and get fixed in batches.

Size today: 66 pages, 52 backend controllers (about 327 endpoints), 66 backend test classes, 55 e2e files (about 120 tests). Most e2e tests check one feature. Few follow a whole business flow, and none compare the reports with each other.

---

## Part 1: Structure review (menus, naming, overlaps)

Things already visible from the menu (`inventory-app/components/nav.ts`) and the pages. Each one needs a decision: **keep / merge / move / rename**.

| # | Observation | Question |
|---|---|---|
| S1 | **Two VAT pages**: Finance → *VAT return* (H3, built from documents) and Accounting → *VAT returns (MTD)* (K4, filed to HMRC). | Keep only one? Do the two give the same figures for the same quarter? |
| S2 | **Reports in two menus**: Finance has Reports, aged debtors/creditors and 4 profit reports. Accounting has P&L, balance sheet, trial balance and journals. | Group all reports under Reports (like Cin7), leaving only the ledger work under Accounting? |
| S3 | **Two Xero entries**: *Xero* (API) and *Xero export (CSV)*, but only one mode can be active per organisation. | Merge into one Xero page that shows the active mode? |
| S4 | **Stock History** and **Movements Log** look like they overlap, and they use the old page layout (plain `h1`, not `PageHeader`). | Merge them, or say clearly how they differ, then move them to the new layout? |
| S5 | **Invoices** sits under *Sale* but also lists supplier invoices and supplier credit notes. | Split into Sale → Invoices / Credit notes and Purchase → Bills / Supplier credits? |
| S6 | **Suppliers** has a list only. Customers have a record page (`/customers/[id]`), suppliers don't. | Add a supplier record page (contacts, addresses, bills, deposits, statement)? |
| S7 | Quotes, credit notes, bills and payments have **no list of their own**. They are reached through orders and invoices. | Does the warehouse or accounts team need these lists (Cin7 has them)? |
| S8 | **Warehouses** and **Invoices** have no permission in the menu, so every user sees them. | Should they? |
| S9 | Roles are set inside **Users** (Admin). Settings has 7 pages, some admin-only. | Should Admin and Settings be one menu? |
| S10 | **Scanner (phone)** sits in the Inventory menu on desktop. | Show it only on phones / as a separate link? |
| S11 | Page layouts: older pages (from phases A–C) and newer Cin7-style pages don't match: list toolbar, record header, buttons. | List the pages to bring up to the new layout. |
| S12 | Names: "Movements Log" vs "Stock Movements" (title), "Transfer" vs "Stock transfer". | Make menu name = page title everywhere. |

Output of Part 1: a short **target menu** (new tree) agreed with the owner before any page moves.

### Proposed target menu (2026-11-09, waiting for the owner's OK)

What was checked for the proposal:
- **S1**: the MTD page (`MtdVatService.review`) builds its nine boxes by calling the same `VatReturnService.vatReturn(from, to)` as Finance → VAT return, so the two pages **always give the same figures**. The MTD page adds the ledger check, the declaration, filing and the list of returns filed. Two pages = two places to look at the same numbers.
- **S4**: *History* and *Movements Log* both call the same `getMovementsPage` with the same filters. The only difference: Movements Log also has a form to add a stock movement by hand, which does the same job as *Adjustments*. History has a CSV export. Both load the whole product list into a drop-down (see R5).

```
Dashboard
Sale         Quotes* · Sale orders · Pick & pack · Invoices · Credit notes* · Customer payments* · Customers
Purchase     Purchase orders · Reorder · Bills* · Supplier credits* · Supplier payments* · Suppliers (+ record page, S6)
Inventory    Stock overview · Products · Stock movements (History + Movements Log merged) · Transfers ·
             Adjustments · Stock takes · Certificates expiring
Reports      Sales profit summary · Profit by sales rep · Sales by product · Inventory movement summary ·
             Aged debtors · Aged creditors · Overdue · Profit and loss · Balance sheet · Trial balance
             (one Reports page with these as cards, like Cin7; the menu shows the same list)
Accounting   Bank accounts · Chart of accounts · Manual journals · VAT returns (one page: boxes + transactions + MTD filing) ·
             Account transactions · Journal report · Xero (one page: API or CSV, whichever is on)
Settings     Organisation · Users & roles · Audit log · Warehouses & bins · Document templates · E-mail ·
             Integrations · Subscription · Import sales & purchases · Go-live check · Ledger settings
Phone        Scanner: shown on phones only (bottom bar), not in the desktop Inventory menu
```
`*` new list pages (S7), each with search and filters; their records already exist.

| # | Proposal |
|---|---|
| S1 | **Merge.** One *VAT returns* page under Accounting: pick the period, see the boxes and the transactions behind them (today's Finance page), then file through MTD or record it as filed. Finance → VAT return becomes a link to it. |
| S2 | **One Reports menu** for every report, money and stock; Accounting keeps only ledger work. The ACCOUNTING / REPORTS_FINANCIAL permissions stay on each report as today. |
| S3 | **Merge** into one *Xero* page: shows the mode that is on (API or CSV) and its screens; switching mode from there. |
| S4 | **Merge** into *Stock movements* (History's CSV export + Movements Log's filters). Remove the hand-made movement form; *Adjustments* is the one way to change stock by hand. Move to the new layout. |
| S5 | **Split**: Sale → Invoices and Credit notes; Purchase → Bills and Supplier credits. Same records and record page; only the lists are filtered. |
| S6 | **Add** a supplier record page like the customer page (contacts, addresses, bills, deposits, statement). With R2 (`/suppliers/new`). |
| S7 | **Add** list pages for quotes, credit notes, bills, supplier credits and payments (Cin7 has them; accounts staff look for them). |
| S8 | Warehouses move to Settings (admin and SETTINGS permission). Invoices get SALE_VIEW; Bills get PURCHASE_VIEW. |
| S9 | **One Settings menu** with Users & roles and Audit log in it; the Admin menu goes. |
| S10 | Scanner only on phones (bottom bar or the phone home screen); a QR link on the Stock overview page for desktop users. |
| S11 | List pages without the new `PageHeader` (checked in the code), to move during the page recheck: Stock history, Movements Log, Transfer, Warehouses, Users, Audit log, Settings → Organisation, Subscription, Integrations, Platform → Organisations. The three Cin7 profit reports use their own header from `report-parts.tsx`: check they match. |
| S12 | Menu name = page title everywhere: *Stock movements*, *Stock transfers*, *Stock adjustments*, *Stock takes*. |

Nothing moves until the owner agrees. Moving pages is done in step 5 of Part 4, after the flow tests.

---

## Part 2: Page-by-page recheck

### The checklist for every page
1. **Opens** without errors (browser console and backend log are clean) on an empty organisation and on Expert Safety's data.
2. **Right people**: hidden from the menu and refused by the API (403) for a role without the permission; works for a role that has it.
3. **Lists**: search, every filter, sort, paging, column choice, CSV export, empty state. Totals match the rows.
4. **Records**: create, edit, required fields and validation messages, delete/void (and blocked when not allowed), status changes.
   **Rule D1**: the list page has no create/edit form; "+ New" opens a separate page (`/x/new`) laid out like the record page.
5. **Numbers**: amounts, VAT, totals, stock and costs are right. Check at least one record by hand.
6. **Trail**: the change shows in the Audit log; the document shows its history (e-mails, payments).
7. **Other organisation**: a second tenant sees nothing (by URL and by API).
8. **Print/e-mail**: PDF opens with the right template; the e-mail lands in the outbox with the attachment.
9. **Look**: same layout as the Cin7-style pages; usable at phone width where the warehouse uses it.
10. **Speed**: opens in under 2 s on Expert Safety's data (13,959 products) **and on a big fake dataset** (about 1 million orders, invoices and stock movements over many organisations). Searches and lists use their indexes (EXPLAIN). Look out for work that grows with the data on every read: the ledger brought up to date before each read, the FIFO rebuild at start-up, reports.

**For each page, in this order: detail, then fix.** First write the detailed finding in [`review-findings.md`](review-findings.md) (what was checked, what is wrong, evidence, impact, proposed fix) and show it to the owner. Only then make the fix. Example: R1, search not using its indexes.

E2E column = e2e spec files that open the page by URL. A 0 can still mean the page is reached by clicking, so check it.

### Pages

**Dashboard / sign-in / account**
- [ ] `/` Dashboard (e2e 5+)
- [ ] `/login`, `/register` (register: e2e 0), `/account` (password, 2FA)
- [ ] `/platform/organisations`: super admin (e2e 0)

**Sale**
- [ ] `/sale-orders` list, `/sale-orders/[id]` record: quote → order → pick/pack/ship → invoice → payment → credit
- [ ] `/picking` list, `/picking/[id]` pick list (e2e 0 on the record)
- [ ] `/invoices` list, `/invoices/[id]`: invoice, credit note, supplier invoice, supplier credit
- [ ] `/customers` list, `/customers/[id]`: addresses, contacts, prices, credit, statement
- [ ] `/overdue`: reminders

**Purchase**
- [ ] `/purchase-orders` list, `/purchase-orders/[id]`: receive (part/over/short), bill, landed cost, deposits, supplier credit
- [ ] `/reorder`
- [ ] `/suppliers` (e2e 0)

**Inventory**
- [ ] `/stock-overview`, `/products`, `/products/[id]`: units, kit, compliance, images, lifecycle, Shopify link
- [ ] `/warehouses` (e2e 0), `/bins` (e2e 0)
- [ ] `/stock-movements`, `/stock-history` (e2e 0): see S4
- [ ] `/stock-transfer`, `/stock-adjustments` (approval, landed cost setting)
- [ ] `/stock-takes`, `/stock-takes/[id]` (e2e 0 on the record)
- [ ] `/scan`: at phone width with a keyboard scanner
- [ ] `/certificates`

**Finance**
- [ ] `/reports`, `/reports/core/[key]` (six core reports)
- [ ] `/reports/sales-profit` (e2e 0 by URL), `/reports/profit-by-rep` (e2e 0), `/reports/sales-by-product`, `/reports/inventory-movement`
- [ ] `/vat-return`, `/aged-debtors`, `/aged-creditors`
- [ ] `/finance/xero` (+ `/finance/xero/callback`, live only), `/accounting-export`

**Accounting**
- [ ] `/accounting/bank`, `/accounting/bank/[code]`: import, reconcile, spend/receive/transfer
- [ ] `/accounting/chart`, `/accounting/manual-journals`
- [ ] `/accounting/profit-and-loss`, `/accounting/balance-sheet`, `/accounting/trial-balance`
- [ ] `/accounting/account-transactions` (e2e 0), `/accounting/journals` (e2e 0)
- [ ] `/accounting/vat` (+ `/accounting/vat/callback`, HMRC sandbox only), `/accounting/settings` (lock dates, year end)

**Admin / Settings**
- [ ] `/users` (roles, several roles, 2FA reset), `/audit-log`
- [ ] `/settings/organisation`, `/settings/subscription`, `/settings/integrations`, `/settings/integrations/shopify`, `/settings/integrations/api` (e2e 0)
- [ ] `/settings/templates`, `/settings/email`, `/settings/data-import`, `/settings/go-live`

---

## Part 3: Testing every function by business flow

Feature tests already exist. What is missing is **whole flows on one set of known data, checked against the money and the stock at the end**. Each flow below becomes one long e2e test (or a backend test where the UI adds nothing), run on a fresh test organisation with fixed data.

| Flow | Steps | Must agree at the end |
|---|---|---|
| F1 Order to cash | customer with contract price + credit limit → quote → order → credit hold check → pick (bins) → pack (cartons) → courier (fake) → ship → invoice → e-mail → part payment → overpayment → credit note + return | stock per bin, FIFO COGS, customer balance = statement = aged debtors, ledger 610/200/310/631 |
| F2 Procure to pay | reorder → PO → part receipt into bins → over/short → bill at a different price → landed cost (setting on) → supplier credit (price) → return to supplier → deposit → payment | FIFO layers and cost corrections, supplier balance = aged creditors, ledger 800/310 |
| F3 Kits and units | sell a kit and boxes of 100; return one | component stock, base-unit stock, cost per component |
| F4 Stock control | transfer, adjustment above the approval value, full stock take and partial stock take with variances | movements, valuation report, journals |
| F5 Shopify (fake) | order in → VAT split → fulfil with tracking → refund → stock push → product + image sync | order/invoice/payment, stock, nothing sent twice |
| F6 Month end | bank statement import → reconcile → manual journal → VAT return (H3 and MTD fake) → P&L / BS / TB → lock date | **report tie-out**: TB balances; P&L = sales profit report (with COGS); VAT H3 = VAT MTD; stock on BS = inventory valuation; debtors/creditors on BS = aged reports |
| F7 Xero (fake) | CSV export of a period, then switch to API: nothing sent twice; payment pulled back from Xero | export records, invoice paid |
| F8 People and access | Admin, the starting roles (Manager, Staff, Accountant), a custom role with single ticks, and the Adviser tick open every page | a menu and API test per role: a full matrix, not samples |
| F9 Two organisations | run F1 in tenant B | tenant A sees nothing on any page or report |
| F10 Cutover tools | task import (sale + purchase) → go-live check → backup + restore on a copy | numbers match the import file |

Also for every flow: **the audit log** has each step, and **nothing goes below zero**.

### Test data
- A **seed script** for a fixed test organisation (about 10 customers, 5 suppliers, 30 products including a kit, a product with units, a discontinued product, 2 warehouses with bins). It is loaded fresh before each flow run, so expected figures can be written into the tests.
- A **copy of Expert Safety's local data** for the page recheck (speed, real names, 13,959 products). Never the live systems.

### How we run it
- Backend: the full suite (`make backend`). E2E: 2 workers (`make e2e`); the machine freezes with 4.
- When memory is short: only the area under test, then the full run at the end.

---

## Part 4: Findings log and order of work

Every problem goes into [`review-findings.md`](review-findings.md) in detail before it is fixed: where, what is wrong, evidence, impact, proposed fix, severity (**Blocker**: wrong money/stock or data leak · **Major**: function broken or very slow · **Minor**: layout/wording), status (Found → Agreed → Fixed).

Order:
1. **Part 1 structure**: write the target menu, owner agrees (no code yet).
2. **Part 3 tie-out first (F6)**: the cross-report checks find the biggest errors fastest.
3. **F1, F2, F4** (money and stock), then F3, F5, F7, F8, F9, F10.
4. **Part 2 page recheck**, module by module (Sale → Purchase → Inventory → Finance → Accounting → Admin/Settings), with the owner looking at the screens alongside.
5. Fix Blockers straight away. Group Majors and Minors per module. Move pages to the agreed menu (S1–S12).
6. **Part 5 tutorials**, once every function is finished and the pages have stopped moving (after step 5).
7. Only then go back to the *Later* list or cutover.

---

## Part 5: Tutorials (after all functions are finished)

Written last, so the screens they show don't change afterwards. Audience: the people who use the ERP every day (sales, purchasing, warehouse, accounts), new staff, and later other companies buying the ERP.

- **Guides by role and job**, step by step with screenshots:
  - *Sales*: quote → order → ship → invoice → payment, credit notes and returns, customers and prices, credit hold
  - *Purchasing*: reorder → PO → receive → bill → pay, supplier credits
  - *Warehouse*: bins, pick and pack, courier labels, the phone scanner, stock takes, adjustments
  - *Accounts*: payments, statements and reminders, VAT return, bank reconciliation, month end, Xero
  - *Admin*: users and roles, two-step sign-in, settings, templates, e-mail, Shopify
- **Screenshots made automatically**: the flow tests F1–F10 already click through every step. A tutorial mode takes a screenshot at each step on the seeded test organisation, so the pictures can be made again with one command whenever a screen changes.
- **"Coming from Cin7"**: where each Cin7 screen and action is in the ERP (a table by Cin7 menu), for Expert Safety's staff at cutover.
- **Help in the app**: a "?" on each page linking to its guide.
- **Format**: pages inside the app (or a help site) that print to PDF. Short screen videos only if the owner wants them.
- **Done means**: every page has a guide, and a member of staff who has never used the ERP can complete each job using only the guide.
