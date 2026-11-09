# Review findings

Part of [`review-and-test-plan.md`](review-and-test-plan.md). **Each page or area is done in two steps: first the detail (this file), then the fix.** Nothing is fixed until its finding has been written here and shown to the owner.

Each finding records:
- **Where**: the page or area, with code references
- **What is wrong**: in plain words
- **Evidence**: code lines, EXPLAIN plans, screenshots, figures
- **Impact**: who is affected, and how badly (money, stock, data leak, speed, layout)
- **Proposed fix**: the steps, and the test that proves the fix
- **Severity**: Blocker (wrong money or stock, data leak) · Major (function broken or very slow) · Minor (layout, wording)
- **Status**: Found → Agreed → Fixed (commit)

## Design rules agreed in the review (apply to every page)

- **D1 List and creation are separate pages** (2026-11-08). A list page only lists: search, filters, columns, bulk actions, and a "+ New" button that goes to a separate page. Creating happens on its own page (`/x/new`), with the same layout as the record page (`/x/{id}`), where viewing and editing also happen. No create or edit forms inside list pages, and no `?edit=` links back to a list. Only small set-up records (a few fields, no lines) may use a short pop-up. See R2 and R3.
- **D2 Ask before doing** (2026-11-09). Actions that delete, cancel, void, move stock or money, or send something outside the ERP ask first in the app's own pop-up (not the browser box), with a red button for the dangerous ones. See R7.

---

## R1 Search does not use its indexes (slow with big data)

- **Where**: the top-bar search (`SearchService`) and the list searches in `SaleOrderRepository`, `InvoiceRepository`, `PurchaseOrderRepository`, `ProductRepository`, `CustomerRepository`, `SupplierRepository` and `StockMovementRepository`. Pages: Sale orders, Invoices, Purchase orders, Products, Customers, Suppliers, Movements log, and the top bar.
- **What is wrong**: migration V36 added trigram indexes for "contains" searches, but Postgres doesn't use them. It reads every row of the organisation and checks each one.
  1. Queries wrap empty values, e.g. `lower(coalesce(invoice_number, ''))`, while the index is on `lower(invoice_number)`, so the two don't match. The wrapping isn't needed in WHERE, because a NULL never matches a LIKE.
  2. Some searched columns have no index at all: sale orders' customer and external reference, POs' supplier reference, products' supplier product code, suppliers' contact name and e-mail, customer contacts' name and e-mail. With one unindexed column in an `A OR B OR C` search, the whole table is scanned.
- **Evidence** (2026-11-08):
  - On the local database, EXPLAIN shows a tenant index scan with the search as a *Filter* for products, invoices and sale orders, even with `enable_seqscan = off`.
  - On a throwaway table of 1,000,000 orders:

    | Query | Time |
    |---|---|
    | As written today | 73 ms |
    | Indexes added, wrapping kept | 80 ms (indexes ignored) |
    | No wrapping, every column indexed | 6 ms (BitmapOr of the three indexes) |

- **Impact**: not visible on today's data (26k products, 3k orders, 633 invoices). Each search grows with the table, the top bar runs about 7 such queries per key typed, and organisations share one database. Expect about a second per search at around a million rows. Speed only: the results are correct.
- **Proposed fix**:
  1. Remove `coalesce` from the WHERE parts of the searches (sort orders can keep it).
  2. One Flyway migration with trigram indexes for the missing columns.
  3. A backend test that runs EXPLAIN on each search and fails if an index isn't used.
- **Severity**: Major (speed with big data)
- **Status**: Found

## R2 "New" opens a form on top of the list, not on its own page like Cin7 (rule D1)

- **Where**: the "+ New" buttons on Sale orders (`sale-orders-page.tsx:81`), Purchase orders (`purchase-orders-page.tsx:83`), Products (`products-page.tsx`), Customers (`customers-page.tsx:161`), Suppliers (`suppliers-page.tsx:56`), Invoices (`invoices-page.tsx:147`), Warehouses, Manual journals, Document templates.
- **What is wrong**: "+ New" opens the form inside the list page, above the list, and the button turns into "Cancel".
  - Sale orders and purchase orders jump to the record page after saving. Editing them uses the same form again, inside the record page (`sale-order-view.tsx:127`, `purchase-order-view.tsx:88`).
  - Products and customers are edited on the **list** page too: the record page's Edit button goes back to `/products?edit=ID` and `/customers?edit=ID` (`product-view.tsx:60`, `customer-view.tsx:59`).
  - In Cin7, "New" opens the record page itself, empty, with the same layout and tabs as an existing record. Creating, viewing and editing all happen on that one page.
- **Evidence**: the code above. The forms are large: sale order form 390 lines, products page 447, customers page 379. All of that sits in the list page.
- **Impact** (no money or stock risk; how the system is to use and to keep up):
  - There's no address for a new record (`/sale-orders/new`), so you can't bookmark it, open it in a new tab or use the browser Back button.
  - A long form, such as an order with many lines, pushes the list down. Refreshing the list or a filter can lose what was typed.
  - It works differently from Cin7, which the staff know.
  - Products and customers have two places with the same fields: the list form and the record page.
- **Proposed fix**:
  1. A `/new` page for the main records, laid out like the record page (header, tabs, lines), saving to the same page with its ID: `/sale-orders/new` (quote or order), `/purchase-orders/new`, `/products/new`, `/customers/new`, `/suppliers/new` (with a supplier record page, see S6 in the plan), `/invoices/new` (hand-typed invoice or bill), `/accounting/manual-journals/new`.
  2. Edit happens on the record page itself (no more `?edit=` back to the list). One form component per record type, used for both new and edit.
  3. Small set-up records (warehouses, bins, templates, chart accounts) keep a short form or pop-up, like Cin7's settings screens.
  4. Update the e2e tests that click "+ New …" (7 files, e.g. "+ New SO" in 10 places) and add one test per new page: open `/x/new` directly, save, land on `/x/{id}`, then Back.
- **Severity**: Minor to Major (no wrong data; it affects daily use and matching Cin7)
- **Design agreed (2026-11-08)**: a new page for records with lines or tabs (sale orders and quotes, purchase orders, products, customers, suppliers, hand-typed invoices and bills, manual journals); a short form or pop-up only for small set-up records (warehouses, bins, templates, chart of accounts, payment accounts, roles, API keys).
- **Status**: Agreed, implement later. Belongs with the Part 1 structure decisions (target menu and pages).

## R3 Credit notes and returns: where they start, and two buttons that overlap

- **Where**: invoice page "Credit note & restock" (`invoices/credit-note-form.tsx`), bill page "Supplier credit note" (`invoices/supplier-credit-note-form.tsx`), both opened inside `invoice-view.tsx:118`; sale order page **Return** (`sale-order-view.tsx`, `createSaleReturn`); Invoices list (`invoices-page.tsx`), where credit notes are mixed in with a "Credit note" label.
- **What is wrong / could be better**:
  1. A credit note can only be started from the invoice or bill page, not from the order, which is where staff usually start (in Cin7 it is a stage of the sale or purchase).
  2. The credit note form opens inside the invoice page. It has lines, so under R2's rule it belongs on its own page.
  3. **Return** on the order (stock back, no money) and **Credit note & restock** on the invoice (money, optional stock back) are two separate buttons for related jobs, and they can be mixed up.
  4. There's no list of credit notes or supplier credits of their own (see S5 and S7 in the plan).
  5. **To check (not tested yet)**: can the same goods be returned on the order *and* restocked on a credit note, so the stock and its cost come back twice? Test both orders of doing it, on stock, FIFO cost, All COGS (Q4) and the ledger.
- **Evidence**: the code above; PROGRESS.md D4b (credit notes), H2 (supplier credit notes), Q3 and Q4.
- **Impact**: daily use and matching Cin7. If point 5 fails, it would mean wrong stock and wrong cost (Blocker).
- **Proposed fix** (implement later):
  1. Keep credit notes always linked to an invoice or bill. No "+ New credit note" on a list (needed for VAT, balances, restock cost and Xero).
  2. Add a **Credit note** button on the sale order and purchase order pages that asks which invoice or bill to credit.
  3. Give the credit note form its own page, `/invoices/{id}/credit-note`, which opens the credit note's page once saved.
  4. Make Return and Credit note one **Return / credit** action with two ticks: *put goods back in stock* and *give money back or credit*. That covers goods + money, goods only and money only.
  5. Add lists Sale → Credit notes and Purchase → Supplier credits.
  6. Tests: the three cases of point 4; double restock refused (point 5); e2e from the order page.
- **Severity**: Minor (layout and flow); Blocker if point 5 fails
- **Status**: Found, implement later. Point 5 is checked first in flow test F1.

## R4 Simple and Advanced purchases and sales (Cin7) — decision to confirm

- **Where**: purchase order and sale order record pages.
- **What**: Cin7 makes you choose *Simple* (one receipt and one invoice, or one shipment and one invoice) or *Advanced* (several of each) when an order is created. The ERP has one kind that already does both (several receipts and bills per PO; several shipments and invoices per sale order; part, over and short deliveries).
- **Recommendation**: keep one kind, and copy how Simple *feels*:
  - show the Receipts / Bills (Shipments / Invoices) cards only once they're used
  - one button, **Receive and bill all** for purchases and **Ship and invoice all** for sales, for an order done in one go
- **To check first**: in Cin7 (read-only), which kind Expert Safety uses, and whether staff rely on anything only Simple has.
- **Status**: Recommendation, waiting for the owner's OK and the Cin7 check; implement later with the purchase and sale page review.

## R5 Stock pages load every product into a drop-down (slow with big data)

- **Where**: Stock history (`stock-history-page.tsx:28`), Movements Log (`stock-movements-page.tsx:26`), Transfer (`stock-transfer-page.tsx:18`) and Invoices (`invoices-page.tsx:65`) call `getProducts()` → `GET /products/options`, which returns **every** active product of the organisation.
- **What is wrong**: the product filter or picker is a plain `<select>` filled with the whole list. Other pages (sale and purchase order lines) already search products as you type.
- **Evidence**: the code lines above; Expert Safety has 13,959 products (26k in the local copy with deprecated ones). Measure in the Inventory page recheck: response size and time to open each page.
- **Impact**: each of these pages downloads and draws thousands of options before it can be used; with a big catalogue (or several organisations of that size) the pages get slow and the drop-down is hard to use. Speed and ease of use only; data is right.
- **Proposed fix**: use the same type-to-search product picker as the order lines (server search with `q`, 20 results), on all four pages. Test: the pages no longer call `/products/options` (e2e network check).
- **Severity**: Major (speed with big data)
- **Status**: Found, fix in the Inventory page recheck (with S4, which merges two of these pages).

## R6 The whole window scrolls and the app slides off the top (owner's screenshot, 2026-11-09)

- **Where**: the app frame (`inventory-app/components/AppShell.tsx`): `<main className="flex-1 overflow-auto p-6">`. Seen on Sale orders (owner's screenshot) and reproduced on Settings → Organisation.
- **What is wrong**: the page is meant to scroll inside `main` only, with the top bar and the menu fixed. But `main` is not `position: relative`. So any element inside a page that is `position: absolute` without a positioned parent is placed against the browser window, not against `main`. That's common: `sr-only` hidden labels and file inputs, drop-downs. When such an element sits low on a long page, the window itself becomes taller than the screen. A mouse-wheel scroll then moves the whole window: the top bar, the logo, the first menu items and the page title disappear off the top, and an empty grey band appears at the bottom.
- **Evidence**:
  - Owner's screenshot (`~/Pictures/Screenshots/expert/Screenshot from 2026-11-09 15-05-35.png`): no top bar, menu starts at "Purchase", list title and "+ New" gone, empty band under the app, and two scroll bars.
  - Playwright, 1500×800 window, every menu page checked: Settings → Organisation makes the window **1630 px** tall for an 800 px screen. The cause is the hidden logo file input (`company-details.tsx:85`, `sr-only`). After 10 wheel turns the window had scrolled **830 px** and the screen was blank grey.
  - Same page with `main` set to `position: relative` (changed in the browser): window **800 px**, wheel scrolling moves only the page content (window scroll 0, content scroll 1888).
  - Sale orders with 7 to 9 test orders did not overflow in the test browser. On the owner's screen it may come from the Shopify import panel or other data-dependent elements (only shown with Shopify connected), from browser extensions adding elements to the page, or from a page opened before. The fix below covers all of these.
- **Impact**: all users, any page. Nothing is lost (layout only), but the menu and the page buttons go out of reach until the user scrolls back up, and it looks broken.
- **Proposed fix**:
  1. `AppShell.tsx`: `main` gets `relative`, so hidden and absolute elements stay inside the scrolling page area.
  2. The signed-in frame does not let the window scroll at all (`overflow-hidden` on the frame / `body` for signed-in pages only; login and register keep normal scrolling for phones).
  3. An e2e test: open every menu page at 1500×800 and at phone size, scroll with the wheel, and fail if the window scrolls or is taller than the screen.
- **Severity**: Minor (layout), but every user sees it
- **Status**: Fixed 2026-11-09: `main` is `relative`, and the window can't scroll while signed in. e2e LAY-001 (desktop) and LAY-002 (phone) open every menu page, scroll with the wheel and check the window: both pass.

## R7 Ask before doing: a confirmation pop-up before create, update, delete and other actions (owner, 2026-11-09)

- **Where**: every page with a button that changes data. The app has **225 actions** (`useMutation` in 60+ page files).
- **What is wrong today**:
  1. Only **23 actions ask first** (delete product/customer/supplier/contract price, cancel invoice/PO/quote, reject quote, close PO short, receive all, undo Xero export, switch off an organisation…). The others run on the first click, including ones that move stock or money or go outside the ERP, for example:
     - *Stock*: ship, pick/pack, post a stock take, stock adjustment, stock transfer, bin move, scanner receive, stock import
     - *Money*: issue invoice, record/void payment, allocate credit, use customer credit, supplier deposit, credit notes, bank reconciliation match/undo, manual journal post, ledger run
     - *Outside the ERP*: send e-mail, send stock to Shopify, apply Shopify refund, run Xero sync, book a courier, VAT disconnect
     - *Removing*: delete warehouse, bin, role, template, chart account, product file, logo; revoke API key; reset two-step sign-in; disconnect Xero/Shopify/HMRC
  2. The 23 that ask use the browser's own box (`confirm()`): grey, headed "localhost:3000 says", no red for dangerous actions, and the text can't be laid out (no list of what will change). Chrome also offers a "Don't allow this site to show more dialogs" tick; once ticked, those boxes stop showing, and the actions are refused without a message.
- **Evidence**: `grep "confirm(" inventory-app` = 23; `grep "useMutation"` = 225. Mutation list per page kept in the review notes.
- **Impact**: one wrong click can ship an order, post a stock take, void a payment or send an e-mail to a customer. Most can be put right afterwards, but only with extra documents (credit note, adjustment, journal) and the audit trail shows the mistake.
- **Proposed fix**:
  1. **One shared pop-up**, `ConfirmDialog` with a `useConfirm()` hook, in the app's style. It has a title, one short sentence on what will happen, the key figures (e.g. "3 lines · 12 units leave Main warehouse · £1,240.00"), and two buttons: *Cancel* and the action word (e.g. "Ship", "Delete"). Escape or a click outside means Cancel. It replaces all 23 browser boxes.
  2. **Three kinds**, so people are not asked too often:

     | Kind | Pop-up | Examples |
     |---|---|---|
     | **Danger**: removes, cancels, voids, disconnects or can't be undone | Red button; focus starts on *Cancel*; for the worst ones (delete organisation data, reset 2FA, disconnect HMRC/Xero) the name must be typed | delete, cancel, void, reverse, revoke, disconnect, reset, undo export, close short |
     | **Posting**: moves stock or money, or sends something outside | Normal button with a summary of what changes | confirm order, ship, receive, issue invoice, payment, stock take post, adjustment, transfer, send e-mail, Shopify/Xero runs, courier booking, imports, raise POs from reorder |
     | **Save**: create or update from a form the user has just filled in | **Owner to choose**, see below | new/edit customer, product, order header, settings |

  3. **Saves: owner to choose**:
     - **a)** a pop-up on every save ("Create customer *ACME Ltd*?")
     - **b)** *(recommended)* no pop-up, but a clear "Saved" message, and a warning when leaving a page with unsaved changes. A pop-up only when the edit changes money or stock already booked, e.g. editing lines on a confirmed order, changing a price on an issued invoice, or changing a product's cost or unit.
     Cin7 doesn't ask on plain saves; asking every time trains people to click OK without reading.
  4. **Bulk actions** (ticked rows) always ask, and say how many rows: "Delete 12 products? Products with stock movements are kept."
  5. **The server checks don't change**: the pop-up is only for people; the API still refuses what isn't allowed.
  6. **Tests**:
     - The e2e fixture accepts browser boxes on its own today (`page.on('dialog')` in `fixtures/test.ts`). Add a helper `confirmAction(page, 'Ship')` and update the tests that click these buttons.
     - New e2e tests: Cancel leaves everything as it was; the action word runs it; Escape cancels; every danger button opens the red pop-up (one test walks every page and checks).
- **Order of work**:
  1. The shared pop-up, then replace the 23 browser boxes. Small; can be done straight away.
  2. Danger and Posting actions, module by module with the Part 2 page recheck (Sale → Purchase → Inventory → Finance → Accounting → Settings), so each page is touched once.
  3. Saves, as the owner chooses in point 3.
- **Severity**: Major (a wrong click changes stock or money with no warning)
- **Status**: Found, plan written. Owner (2026-11-09): do it later; on the to-do list in `docs/TODO.md` (Review fixes to do). Still to choose a) or b) for saves.

## R8 A cleaner look for list pages and creation pages (owner, 2026-11-09)

- **Where**: all list pages (`ListToolbar`, `ListHead`), all create/edit forms (sale order form, customer form, product form…), record pages (`DetailParts`). Screenshots taken 2026-11-09 at 1500×900: Sale orders list, New sale order, Sale order SO-6, New customer.
- **What makes them look busy today**:
  - **List pages**
    1. Empty columns full of "—": on Sale orders, Reference, Documents and Sales rep are empty on most rows, yet each takes a column.
    2. The *Progress* icons are tiny and pale (5 icons of 14 px). It's hard to see at a glance where an order is.
    3. The status filter is a drop-down ("All quotes and orders"). Cin7 and most modern apps use **tabs with counts** (All · Draft · Confirmed · To ship · To invoice), which take one click and show the workload.
    4. Three fonts in one row: order number in a typewriter font, names bold, numbers plain.
    5. A *Refresh* button that isn't needed (the list refreshes on its own).
    6. **Dates follow the browser's language**: a browser set to US English shows `11/9/2026` for 9 November (`ClientDate.tsx:10`, `toLocaleDateString()` with no locale). The owner's screen shows `29/10/2026`. Should always be UK `09/11/2026` or `9 Nov 2026`.
  - **Creation pages**
    1. One long wall of boxes with the same weight: the new sale order shows 10 fields, two full address blocks (13 empty boxes), then the **lines, the most important part, below the fold**.
    2. No sections: customer, delivery, pricing and notes are mixed in one grid.
    3. Fields the customer already fills in (terms, price tier, sales rep, carrier, addresses) are shown empty and open, although they are copied from the customer on save.
    4. Placeholders that look like values ("None"), and labels in two styles ("Payment Term", "Credit Limit (£)" vs "Ship by", "Customer reference").
    5. The Save / Cancel buttons are at the very bottom of a long form, and "Cancel" at the top is the black main button.
    6. Browser-default drop-downs and date boxes next to the app's own inputs, so heights and styles differ.
  - **Record pages** (fine overall, small things): five buttons of the same weight in the header (Print, E-mail, Cancel order, Edit, Confirm order); long product names cut off ("Safety b…") while the table has room; an empty *Shipments* card on a draft.
- **Impact**: no wrong data. Pages look crowded, new staff don't know where to look, and orders take longer to enter. It matters more once the ERP is sold to other companies.
- **Proposed fix**: a small design system first, then each page moved to it during the page recheck (with R2's `/new` pages, so each page is redone once).
  1. **Building blocks** in `components/ui`: `Button` (primary in the brand colour, secondary, quiet, danger), `Field` (label + input + help + error, one height for text, select and date), `Section` (a card with a heading and an optional "More" toggle), `StatusTabs` (tabs with counts), `StickyActions` (the Save bar fixed at the bottom of a form), `Money` and `DateText` (UK format, numbers aligned).
  2. **List pages**: title + "+ New" on top; status **tabs with counts** under the title; one search box and a "Filters" button (filters open in a side panel, with chips showing the active ones); columns chosen by the user, and empty optional columns hidden by default; a clear progress bar or a coloured step label instead of 5 pale icons; one font, with numbers aligned right; no Refresh button.
  3. **Creation and record pages** (same layout, rule D1):
     - The header: title, status, and one main button; the other actions in a "More" menu (Print, E-mail, Cancel…).
     - **Sections**: *Customer* (pick the customer; their address, terms and price tier show as a short summary with "Change"), *Lines* (straight after the customer, full width, with totals on the right), *Delivery* (warehouse, ship by, carrier; addresses collapsed until "Change"), *Notes*.
     - The rarely used fields are inside "More details", closed by default.
     - A **sticky bar** at the bottom: "Cancel" (quiet) and "Save" (brand colour), always in view; a warning when leaving with unsaved changes (shared with R7 b).
     - Labels in sentence case, help text under the field, no "None" placeholders.
  4. **Design rule D3** (to agree): every page is built from these blocks only. New colours, sizes or one-off buttons are not allowed in pages.
  5. **Tests**: the e2e tests use labels and roles, so most keep working; screenshots of each page before and after go in the review notes.
- **How the design work is done** (owner, 2026-11-09): no separate design tool (Figma) for now. The pages are changed directly in the code on the dev environment and approved page by page:
  1. **Building blocks first**: built once on dev, the owner approves them, every page then uses them.
  2. **Each page**: the detail first (what will change), then the change on dev, then screenshots before and after (desktop and phone) for the owner to OK or mark up, then the commit. Each page is one commit, so it can be undone.
  3. **Three model pages first**: Sale orders (list), New sale order (creation), the sale order page (record). Once agreed, the other pages follow them and only need a quick look.
  4. **Big layout changes** (e.g. the new sale order page): a rough screenshot or sketch of the layout before building.
  - Figma only later, if a designer is hired or a brand look is wanted for selling the ERP.
- **Order of work** (later, in phases, owner 2026-11-09): 1) building blocks; 2) the three model pages; 3) the other pages module by module with the page recheck, together with R2 (`/new` pages) and R7 (pop-ups).
- **Severity**: Minor (look and ease of use)
- **Status**: Found, plan agreed; **do later in phases** (on the to-do list in `docs/TODO.md`).
