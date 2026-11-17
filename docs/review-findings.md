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
- **Status**: Fixed 2026-11-13 (V67, SearchIndexTest)

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
- **Status**: Fixed 2026-11-09 (owner: "start implement"):
  - New pages: `/sale-orders/new`, `/purchase-orders/new`, `/products/new`, `/customers/new`, `/suppliers/new`, `/invoices/new` (hand-typed invoice or bill), `/accounting/manual-journals/new`. Saving opens the record's page; Cancel and "Back to list" go back to the list.
  - One form component per record, for new and edit: `product-form.tsx`, `customer-form.tsx`, `supplier-form.tsx`, `invoice-form.tsx`, `journal-form.tsx` (sale and purchase order forms already were).
  - Edit on the record page: products and customers no longer go back to `/products?edit=` / `/customers?edit=`. The list's Edit (or More → Edit) opens `/x/{id}?edit=1`.
  - New record pages: **suppliers** (`/suppliers/{id}`: details, what is owed, open bills, recent POs and bills, Edit, Delete; S6 first step: statement and deposits still to come) and **manual journals** (`/accounting/manual-journals/{id}`: lines, Post / Edit for a draft, Void; backend `GET /api/ledger/manual-journals/{id}`). The top-bar search opens the supplier page.
  - Side effect for R5: the Invoices list no longer loads every product (only the new invoice page does).
  - Left as they are (small set-up records, as agreed): warehouses, bins, templates, chart of accounts, payment accounts, roles, API keys. Credit notes move with R3.
  - Tests: e2e NEW-001 (every "+ New" opens its own page, Cancel and Back return, no record form on a list), NEW-002 (supplier new → record page → edit), NEW-003 (hand-typed invoice); updated SO, PO, product, customer, plan, ledger and Shopify tests; backend LedgerTest (one journal).

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
- **Point 5 checked in flow test F1 (2026-11-15): it fails in one of the two ways.**
  - *More back than was shipped*: refused. A return, on the order or through a credit note, is limited to "shipped and not yet returned" (120 shipped, 10 back: an 11th… 111th is refused; in `OrderToCashFlowTest`).
  - *The same goods twice*: **allowed**. 20 boots, 10 shipped (10 left). The warehouse records 5 back on the order with Return → 15. Accounts then raise the credit note for those 5 and leave "restock" ticked → **20 in stock, 5 too many**, and the order shows 10 returned. Nothing links the return already recorded to the credit note, so the form cannot know the goods are already back. The stock value and the cost of goods move with it (5 × the order's average cost twice).
  - **Proposed fix (small, before the rest of R3; needs the owner's OK)**: each order line remembers how many returned units a credit note has already covered. A credit note with "restock" first takes the units already returned and not yet credited (no new stock movement), and only the remainder comes in as new stock. The form says so on the line: "5 already back in stock on 15/11/2026, not put back again". A return recorded after the credit note works the other way round: units credited with restock are not offered for a second return (already true: they count as returned). Test: both orders of doing it, stock, FIFO cost and ledger unchanged by the second step.
- **Severity**: Minor (layout and flow) for points 1–4; **Major for point 5** (wrong stock and cost after an easy mistake; not a Blocker since nothing is wrong until someone ticks restock for goods already returned).
- **Status**: **Point 5 fixed 2026-11-15** (owner's OK): each order line keeps `restock_credited_quantity` (V71; returns made by earlier credit notes are counted as covered). A credit note with "restock" takes the units a return already brought back first, with no stock movement, and only the rest comes in; the credit note form shows "N already back in stock from a return on the order: not put back again". `CreditNoteTest`: both orders of doing it, stock and cost of goods unchanged by the second step. Still possible, and not preventable by the system: the warehouse records a second Return for goods a credit note already put back, while other units of the line are still out (the order's return list names the credit note). Points 1–4 implement later.

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
- **Status**: Fixed 2026-11-13 (type-to-search box on the four pages, e2e STK-020)

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
- **Status**: Steps 1 and 2 fixed 2026-11-09 (owner: "implement popup to confirm for actions"):
  - `components/ConfirmDialog.tsx`: `ConfirmProvider` + `useConfirm()`. Title, explanation, figures, Cancel and the action word; red with Cancel focused for dangerous actions; Escape or a click outside cancels; optional "type the name to confirm"; quick fade (off with reduced motion).
  - All 23 browser `confirm()` boxes replaced.
  - Ticked-row actions that change rows always ask, with the count and the first names.
  - About 70 more actions now ask: ship, receive, return, transfer, stock movement, adjustment (save, approve, reject), stock take (start, post, cancel), pick lists, packing, courier booking and cancelling, confirm and convert orders, mark PO as ordered (also "Create & mark ordered"), enter a bill, issue and cancel invoices, credit notes, supplier credits, payments, credit used, supplier deposits, customer credit, journals post/void, bank transactions, reconcile create/transfer/unreconcile/void, statement and chart and bin and stock and task imports, VAT submit / mark filed / HMRC disconnect, Xero send/pull/retry/switch/disconnect, Shopify import, refunds, stock push, product sync, webhooks, e-mail send, deletes (bins, files, accounts, logo, statement lines), 2FA off and new codes, credit hold release.
  - Not asking (on purpose): plain saves (step 3, owner to choose a or b), previews, look-ups, test connection, and actions that already ask for a reason with `prompt()` (void payment, credit hold, remove credit, dismiss refund; to move into the pop-up with a reason box later).
  - Tests: e2e CONF-001 (red pop-up, Cancel / Escape / click outside keep the customer, Delete removes it), CONF-002 (confirm order asks with the number, Cancel leaves a draft); the fixture presses the pop-up's button for other tests (`autoConfirm`), `clickAndConfirm()` where a test reads the result through the API.
  - **Still open**: step 3 saves, owner to choose a) or b); `prompt()` reasons into the pop-up.

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
  4. **Animations** (owner, 2026-11-09): small ones that show what happened, not decoration.
     - Loading: grey placeholder rows instead of "Loading…".
     - Feedback: a "Saved ✓" message that fades out after 3 s; a spinner on the button while it works (no double clicks).
     - Pop-ups and the Filters side panel: quick fade with a slight scale or slide (about 150 ms), background dimmed.
     - Lists: a new or changed row flashes a light highlight; a removed row fades out; status tab counts change smoothly.
     - The order progress step fills in when the status moves on; the menu groups and "More details" open smoothly.
     - Dashboard: charts grow in and the big numbers count up, once, on first load.
     - Hover and keyboard focus: gentle row highlight, clear focus outline.
     - Rules: nothing longer than about 300 ms, no bouncing or whole-page slides, no row-by-row animation of long lists; one set of speeds in the building blocks (phase 1); everything off when the device is set to reduce motion (`prefers-reduced-motion`); Tailwind first, a library such as `motion` only with the owner's OK.
  5. **Footers** (owner, 2026-11-09): no website-style footer across every page (the page area scrolls, so it would take room from the data or never be seen; Cin7 and Xero have none). Instead:
     - Sidebar bottom and user menu: Help · What's new · version · Support (needed once the ERP is sold to other companies).
     - List pages: a footer bar under the table with "Displaying 1–20 of 340", paging and page size; on money lists (invoices, payments, aged debtors…) the **totals of the filtered rows**; with ticked rows, "3 selected · £1,240.00".
     - Creation and edit pages: the sticky Save bar (point 3).
     - Record pages: a quiet last line, "Created by … on … · Last changed by … on … · View history" (link to the audit log).
     - Printed documents (invoices, POs, statements): company number, VAT number, registered address, bank details, "Page 1 of 2"; checked with the document template review.
     - Login and register: "© Expert ERP · Privacy · Terms".
  6. **Design rule D3** (to agree): every page is built from these blocks only. New colours, sizes or one-off buttons are not allowed in pages.
  7. **Tests**: the e2e tests use labels and roles, so most keep working; screenshots of each page before and after go in the review notes.
- **How the design work is done** (owner, 2026-11-09): no separate design tool (Figma) for now. The pages are changed directly in the code on the dev environment and approved page by page:
  1. **Building blocks first**: built once on dev, the owner approves them, every page then uses them.
  2. **Each page**: the detail first (what will change), then the change on dev, then screenshots before and after (desktop and phone) for the owner to OK or mark up, then the commit. Each page is one commit, so it can be undone.
  3. **Three model pages first**: Sale orders (list), New sale order (creation), the sale order page (record). Once agreed, the other pages follow them and only need a quick look.
  4. **Big layout changes** (e.g. the new sale order page): a rough screenshot or sketch of the layout before building.
  - Figma only later, if a designer is hired or a brand look is wanted for selling the ERP.
- **Order of work** (later, in phases, owner 2026-11-09): 1) building blocks; 2) the three model pages; 3) the other pages module by module with the page recheck, together with R2 (`/new` pages) and R7 (pop-ups).
- **Severity**: Minor (look and ease of use)
- **Status**: Found, plan agreed; **do later in phases** (on the to-do list in `docs/TODO.md`).
  - **Started 2026-11-09** (owner: the forms are hard to read, parts not easy to tell apart, like Cin7's sale screen): `components/ui/Section.tsx` with `Section` (white box, bold title, thin line, buttons on the right), `SubHeading` and `StickyActions` (Save bar fixed at the bottom with the total). The **sale order form** now has Customer details · Accounting details · Shipping details side by side, Products (Order / Quote lines), Additional charges and services, Comments and Totals; the **purchase order form** Supplier details · Delivery details, Products, Additional costs, Memo and Totals. Sales rep (sale order and customer forms) picked from a list like Cin7: the names already used on customers and orders plus the web sales rep (`GET /api/customers/sales-reps`); a new name can still be typed. Terms (sale order, purchase order, customer, supplier) picked from a list like Cin7: the terms already used (most-used spelling, once each) or Cin7's usual ones for a new organisation, each with what it means for the due date (`GET /api/lists/payment-terms`); stops typos that made an invoice due on its own date. Better than Cin7: addresses shown as one line with "Change" (the saved-address list stays), the customer's defaults shown in the empty boxes ("30 Day EOM (customer's)"), the Save bar with the total always in view. Other forms follow in the R8 phases.
  - **Animations, 2026-11-09** (owner: "can implement animation now?"): one set of speeds in `globals.css` (120 / 180 / 280 ms) and everything off with `prefers-reduced-motion`; grey placeholder rows instead of "Loading..." on 15 list pages (`ui/Skeleton.tsx`); a "Saved / created" message that slides in and goes after 3 s (`ui/Toast.tsx`) on the product, customer, supplier, sale order, purchase order, invoice and journal forms; a spinner in busy Save buttons (`aria-busy`, no double clicks); menus, search results and pop-ups fade in; sidebar groups open and close smoothly (closed groups `inert`); the order progress icons change colour smoothly; dashboard figures count up once and the chart bars grow in once (`ui/CountUp.tsx`); gentle hover on buttons, links and rows; a clear keyboard focus outline. Not yet: status-tab counts (the tabs come with the R8 list pages) and highlighting the row just changed (needs the list pages to know what was saved).

## R9 The product box on order lines is a search box, not a selection list like Cin7 (owner, 2026-11-09)

- **Where**: `inventory-app/app/sale-orders/product-picker.tsx`, used on sale order lines (`sale-order-form.tsx:261`), purchase order lines (`purchase-order-form.tsx:182`) and contract prices (`customers/contract-prices.tsx:89`).
- **What is wrong**:
  1. Clicking the box shows nothing: the list only appears after **2 letters** are typed (`q.length < 2`).
  2. It looks like a text box: placeholder "Search SKU or name", no ▾ arrow. In Cin7 the product field is a selection list: click it, the list opens, then type to narrow it down.
  3. At most **15** matches and no way to see more (no scrolling to load the next ones).
  4. No keyboard use: ↑ ↓ to move, Enter to pick, Esc to close do not work; Tab leaves the box without picking.
  5. On a purchase order it shows the **selling** price and available stock; a buyer needs the cost and what is already on order.
- **Evidence**: the code above (lines `if (!open || q.length < 2)`, `size: 15`, `placeholder="Search SKU or name"`).
- **Impact**: order entry is slower and feels different from Cin7; staff have to know part of the SKU or name before they see anything. No wrong data.
- **Proposed fix** (one shared component, so all three places change at once):
  1. A **selection list** (combobox): the box shows "Select a product" and a ▾ arrow; a click (or focus) opens the list straight away with the first products (A–Z by SKU), and **scrolling loads 20 more** each time, so all 14,000 can be reached without loading them all at once.
  2. **Typing narrows the list** from the first letter (SKU, name, barcode), as now but with no 2-letter minimum.
  3. **Keyboard**: ↑ ↓ move, Enter or Tab picks the highlighted product, Esc closes; the chosen product shows as "SKU — name" with × to clear.
  4. **Columns per use**: sale lines: SKU, name, available, selling price (or the customer's tier/contract price). Purchase lines: SKU, name, on hand, on order, cost; when a supplier is chosen, **that supplier's products first**.
  5. Speed with big data: each page of 20 is one indexed query (the search part depends on R1's index fix); nothing loads all products.
  6. The same component then replaces the full drop-downs on Stock history, Movements Log, Transfer and the new invoice page (finding R5).
  7. Tests: the existing e2e tests keep working (same label and list names); new e2e: the list opens on click with no typing, scrolling loads more, keyboard picking, PO shows cost.
- **To check first**: in Cin7 (read-only), exactly what its product list shows on a sale line and a purchase line (columns, order), to copy it.
- **Severity**: Minor to Major (daily order entry)
- **Status**: Fixed 2026-11-09 (owner: "go ahead"):
  - Backend `GET /api/products/picker?q=&supplierId=&page=&size=` (`ProductPickerService`): active products A–Z by SKU, the supplier's products first, search on SKU, name or barcode, one page with `more` (no count), on hand / available / on order / prices for that page only; cost hidden without the cost permission. Allowed for product viewers **and** sale/purchase editors and the Customers permission: the old search called `/api/products`, which needs "Product view", so a sales-only user saw no products on order lines.
  - `product-picker.tsx` rewritten as a selection list: "Select a product ▾", the name with the SKU small under it (owner), opens on click, scroll loads 20 more, typing narrows from the first letter, ↑ ↓ Enter Tab Esc, quick fade. Sale lines: available + price; purchase lines: on hand, on order, cost, "Supplier's" tag.
  - Used on sale order lines, purchase order lines (with the PO's supplier) and contract prices.
  - Customer (sale orders) and Supplier (purchase orders) boxes: the browser's datalist (e-mail on the right, different in each browser) replaced by the app's own list under the box, name on the left with the e-mail (or contact) small under it, like Cin7 (owner); a new name can still be typed (`components/NameCombobox.tsx`, e2e PICK-003).
  - Tests: backend `ProductPickerTest` (A–Z paging, archived left out, barcode search, supplier first, on hand/available/on order); e2e PICK-001 (opens with no typing, 20 then 25 on scroll, narrows, keyboard, Esc), PICK-002 (PO: supplier's product first with on order and cost); SO-008 updated.
  - **Not done**: the Cin7 look-only check (the Chrome extension was not connected), so columns may still be adjusted; the four full drop-downs of R5 stay for the Inventory recheck (S4 merges two of those pages).

## R10 The platform's Organisations page draws every organisation at once (slow with big data)

- **Where**: Platform → Organisations (`inventory-app/app/platform/organisations/organisations-page.tsx`), `GET /api/tenants`.
- **What is wrong**: the API sends every organisation and the page draws one row per organisation, each with two drop-downs (plan, subscription). Search filters in the browser.
- **Evidence** (2026-11-09, local database after many e2e runs: each test registers its own organisation): **7,973** organisations; `GET /api/tenants` 0.07 s but **2.1 MB**; the page takes long enough to draw that e2e PLAT-001 timed out waiting for the redirect after the super admin signs in (the page shows correctly a few seconds later).
- **Impact**: only the platform super admin; grows with every organisation that subscribes. No wrong data.
- **Proposed fix**: server-side paging and search for `/api/tenants` (50 per page, search by name or slug), the page asks for one page at a time; counts (users, products, warehouses) only for that page. Test: an EXPLAIN-backed backend test, and PLAT-001 passes with thousands of organisations.
- **Severity**: Major (speed with big data, platform admin only)
- **Status**: Found

---

## Sale pages recheck (2026-11-17)

Checked: `/sale-orders` (list, new, record as draft / confirmed / shipped), `/picking` (list, pick list), `/invoices` (list, new, record), `/customers` (list, new, record), `/overdue`. How: the code of each page and its API; a browser run on a throwaway organisation with 25 customers and 25 orders in every state (desktop 1440 px and phone 390 px, console, failed requests, screenshots); a role with Sale view + Customers only; SQL statements counted per list with 120 orders, 100 invoices and 120 customers.

What passed: every page opens with no browser console error and no failed request, at both widths; the server refuses what each role may not do and no organisation sees another's data (F8, F9); D1 holds (lists have no forms, "+ New" goes to `/x/new`); D2 holds for the list actions (Issue, Cancel, Void ask first). Expert Safety's local copy has only 11 sale orders and 8 invoices, so speed on real volumes is not proven yet (R18).

## R11 Sale order and invoice lists load each row's parts one by one (slow with big data)

- **Where**: `SaleOrderService.findAll` → `toResponse` per order (lines, charges, warehouse, customer, each line's product: lazy, one query each); `InvoiceService.findAll` → `toResponse` per invoice (items, payments); `PickPackService.pickLists` (one query for the ids, then two per pick list, `LIMIT 100`).
- **What is wrong**: the page query is fast, but every row then fetches its own parts. Nothing batches them (`hibernate.default_batch_fetch_size` is not set, no fetch joins). The pick list list has no paging and silently stops at 100.
- **Evidence** (Hibernate statistics, test database):

  | List | Page of 20 | Page of 100 |
  |---|---|---|
  | Sale orders | 47 statements | **448** statements (765 entities) |
  | Invoices | 66 statements | **306** statements |
  | Customers | 5 | 5 (already batched) |
  | Pick lists | 1 + 2 per list, up to 201 | |

- **Impact**: every user of these lists; the time grows with the page size chosen (20 / 50 / 100) and with the database's latency in production. Data is right.
- **Proposed fix**: set `spring.jpa.properties.hibernate.default_batch_fetch_size=50` (one query per kind of part for the whole page) and check the two lists again; where still needed, a fetch join for lines and products. Pick lists: paged like the other lists, and their lines read in one query for the page. Test: a backend test that counts statements and fails above 15 for a page of 100 sale orders or invoices.
- **Severity**: Major (speed with big data)
- **Status**: Found

## R12 "Newest first" has no index to sort by (sale orders, invoices)

- **Where**: both lists sort by `created_at DESC, id DESC` (`SaleOrderService.findAll`, `InvoiceService.findAll`). Indexes: `sale_orders (tenant_id, ordered_at)`, `invoices (tenant_id, type, issue_date)`; none on `created_at`.
- **What is wrong**: to show the first 20, the database reads every order (or invoice) of the organisation and sorts them, on every page and every refresh.
- **Evidence**: `EXPLAIN SELECT * FROM sale_orders WHERE tenant_id = 1 ORDER BY created_at DESC, id DESC LIMIT 20` → `Sort` over `Index Scan using idx_sale_orders_tenant_ordered` (all the organisation's rows, then sort).
- **Impact**: grows with the number of orders and invoices: fine at Expert Safety's size today, slow after a few years or for a larger customer of the ERP.
- **Proposed fix**: migration with `(tenant_id, created_at DESC, id DESC)` on `sale_orders` and `invoices`; check that the status filter still uses it (or add `(tenant_id, status, created_at DESC)`). Test: EXPLAIN on the big dataset shows `Limit → Index Scan`, no `Sort`.
- **Severity**: Major (speed with big data)
- **Status**: Found

## R13 The customer box loads every customer (size 9999)

- **Where**: `getCustomers()` (`lib/api.ts:75`, `GET /customers?size=9999`) on Sale orders (`sale-orders-page.tsx:58`, only to name the "One customer" filter), New sale order (`new/page.tsx:15`) and editing an order (`sale-order-view.tsx:120`).
- **What is wrong**: the same pattern R5 and R9 fixed for products. Every customer, with contacts, is downloaded to fill a drop-down; past 9,999 customers the rest are silently missing.
- **Evidence**: the code lines above; 343 customers in Expert Safety's copy today, Cin7's customer list to be imported at cutover.
- **Impact**: slower opening of the sale pages as the customer list grows; a customer beyond 9,999 cannot be chosen.
- **Proposed fix**: a type-to-search customer box like the product box (server search, 20 results, name with small account code); the list's filter chip reads only the one customer (`GET /customers/{id}`). Test: e2e checks these pages no longer call `/customers?size=9999`.
- **Severity**: Major (speed with big data, and a hard limit)
- **Status**: Found

## R14 Sale pages offer actions the role may not do

- **Where**: no Sale page checks the role before showing a button (`can(` is used nowhere in `app/sale-orders`, `picking`, `invoices`, `customers`, `overdue`).
- **What is wrong**: a user whose role has Sale view and Customers only (no Sale add/edit, no void) is offered: "+ New SO", "Cancel order", "Create invoice", "Ship items", "+ Shipment", "+ Invoice", "Record return" (sale order); "+ New Invoice", "Cancel" (invoices list); "Cancel invoice", "Credit note & restock", the Record payment form (invoice). Each one ends in "Your role does not allow this". The bulk actions on the list (Undo, Fill and authorise, Void) are offered too.
- **Evidence**: browser run as that role, buttons read from each page.
- **Impact**: no harm to data (F8: the server refuses each one), but users meet errors and cannot tell what they may do; Cin7 hides what the role cannot do.
- **Proposed fix**: show each action only with its permission: add/edit/ship/invoice/payment = Sale edit; cancel/void/undo = Sale void; price and discount boxes read-only without Change prices; credit limit / hold = Customer credit; contract prices = Customer prices. Test: extend `security/roles-menu.spec.ts` with a view-only role that must see no action button on these pages, and an edit role that sees them.
- **Severity**: Major (function: users hit refusals)
- **Status**: Found

## R15 No phone layout: the menu takes most of a phone screen

- **Where**: the app frame (`components/AppShell.tsx`, `components/Sidebar.tsx`): the side menu is always 240 px wide, at every screen width.
- **What is wrong**: on a 390 px phone the menu keeps 240 px open, the page gets about 150 px, titles break word by word and the top bar's search and organisation name are squashed. The menu can be collapsed by hand to 64 px, but not on its own and not as a drawer.
- **Evidence**: screenshots at 390 px of every Sale page (e.g. the sale order record: the title "Sale SO-2 for Probe Customer 1" on five lines, the Shipments card cut off). The earlier check (R6, LAY-002) looks for a window wider than the screen, which this is not: the content squeezes instead.
- **Impact**: everyone on a phone, and above all the warehouse (Pick & pack, Scanner: S10 agreed the Scanner is for phones).
- **Proposed fix**: below 768 px the menu is hidden behind a menu button and slides in over the page; the top bar shows the menu button, the page name and the user only (search behind an icon). Test: LAY-002 also fails when the page's content area is narrower than 340 px at 390 px.
- **Severity**: Major (phones unusable)
- **Status**: Found

## R16 Records do not show their own history

- **Where**: sale order, invoice and customer record pages (`sale-order-view.tsx`, `invoice-view.tsx`, `customer-view.tsx`).
- **What is wrong**: checklist point 6. What happened to a record (created, confirmed, shipped, invoiced, e-mailed, cancelled, by whom, when) is only in Admin → Audit log, which has to be searched. The e-mails sent for a document are only in Settings → E-mail → Outbox. Cin7 shows each sale's log on the sale.
- **Evidence**: no history or activity panel in the three views; the audit log has the entries (F1 checked all 15 steps).
- **Impact**: users and the owner cannot see on the order who changed it or whether the invoice went out.
- **Proposed fix**: a "History" card at the bottom of each record: its audit entries (newest first, who, what, the changed values in words) and the e-mails sent for it from the outbox (`GET /api/mail/outbox?docKey=` exists); needs a small `GET /api/audit-log?entity=&id=` filter if it is missing. Test: e2e confirms an order and sees "Confirmed by …" in its history.
- **Severity**: Major (missing function on the checklist)
- **Status**: Found

## R17 Small look and wording issues on the Sale pages

- **Where / what**:
  1. Sale order record, Warehouse card: the headings "Picked Packed Shipped" run together and "0/2" spills past the card's edge (`warehouse-card.tsx`).
  2. Invoice record: dates shown raw (`2026-11-17`) and amounts as "GBP 36.00", where the order and the lists show 17/11/2026 and £36.00; "Made from: Sale order ›" does not say which order (`invoice-view.tsx`).
  3. Invoices list: "Loading…" text instead of the skeleton rows the other lists show; the due date is drawn with `new Date(…).toLocaleDateString` (a day early for a browser west of UTC; the other pages use `ClientDate`); errors pop up as a browser `alert()` (also Customers list, delete) instead of the app's message.
  4. Out-of-date wording now that e-mail exists (phase M): Overdue says "E-mail comes later"; the bulk "Email" action on four lists says "Comes with e-mail sending" and does nothing.
  5. Pick list page (`/picking/[id]`) and the Pick & pack list keep the old layout: no "Back to list", no status badge, no card layout (S11: moved during the recheck).
- **Evidence**: screenshots of the browser run; the code lines named.
- **Impact**: looks unfinished; item 3's date shift and item 4's dead button can mislead.
- **Proposed fix**: fix each in place; item 4: the bulk Email sends each ticked document with its default template (as the record's E-mail button), with the app's confirm pop-up (D2); item 5 with the R8 look, before/after screenshots for the owner.
- **Severity**: Minor
- **Status**: Found

## R18 Speed on real volumes not measured yet

- **Where**: all lists and reports (checklist point 10).
- **What is wrong**: Expert Safety's local copy has 16,591 products and 343 customers but only 11 sale orders and 8 invoices, and the timings from the dev server (1.2 to 4 s per page) include Next.js compiling the page on first open, so they say nothing about real speed.
- **Proposed fix**: before signing off speed, build the big fake dataset the plan asks for (about 1 million orders, invoices and stock movements over many organisations, made by SQL in a separate database) and time each list on a production build (`next build` + `next start`), with EXPLAIN for each list query; R11 and R12 first.
- **Severity**: Major (unknown)
- **Status**: Found

## All other pages recheck (2026-11-17)

Checked: Purchase (`/purchase-orders` list, new, record as draft / ordered / received, bills tab; `/reorder`; `/suppliers` list, new, record), Inventory (stock overview, products list / new / record, warehouses, bins, stock movements, stock history, transfer, adjustments, stock takes list and record, scanner, certificates), Finance / Reports (reports page, the six core reports, the four Cin7 profit and movement reports, VAT return, aged debtors and creditors, Xero), Accounting (bank accounts and one account, chart, manual journals list / new, P&L, balance sheet, trial balance, account transactions, journal report, VAT returns, ledger settings), Admin / Settings (users, audit log, organisation, subscription, integrations, Xero, Shopify, API, templates, e-mail, import, go-live), Dashboard, My account, sign-in and register. How: the code of each page; a browser run on a throwaway organisation (6 products, 1 supplier, 23 purchase orders in every state, a stock take) at 1440 px and 390 px (65 pages each, console, failed requests, content width, screenshots); a role with Purchase view, Product view, Dashboard and the sale / purchase / inventory reports only; SQL statements of **every GET list and report of the API** (113) after 3 and after 30 rounds of trade (sale to credit note, purchase to payment, journal, bank line); EXPLAIN of the list queries on the dev database (36,756 audit entries, 9,233 stock movements over all organisations).

What passed: every page opens, no browser console error and no failed request except one (R22 item 6); sign-in and register work at phone width; the server refuses what the role may not do (F8) and no organisation sees another's (F9); 106 of the 113 API lists and reports read the same number of statements whatever the data (they are batched or plain SQL); reports answered in under 110 ms at this size; D1 and D2 hold on the pages checked; the product record has its Activity log. Platform pages (super admin) were checked in R10 and not again. Speed on real volumes stays with R18.

## R19 Purchase and Inventory pages offer actions the role may not do (as R14)

- **Where**: no Purchase page and most Inventory pages check the role before showing a button (`can(` is not used in `app/purchase-orders`, `reorder`, `suppliers`, `stock-overview`, `stock-movements`, `stock-transfer`); products check some.
- **What is wrong**: a role with Purchase view + Product view only is offered: "+ New PO", Receive, Cancel (list); Cancel PO, Edit, Mark as ordered, Receive stock, + Receipt, + Deposit, Enter bill, + Bill (record); "Create draft PO" (reorder); Import, "+ New Supplier", Edit, Delete (suppliers); Edit, + Deposit (supplier record); "Import stock" (stock overview); Import, "+ Add Product", Edit (products); "+ Record Movement" (stock movements). Each ends in "Your role does not allow this".
- **Evidence**: browser run as that role, buttons read from each page.
- **Impact**: as R14: no harm to data, but users meet refusals.
- **Proposed fix**: done together with R14, one rule for every page: Purchase edit = new / edit / order / receive / bill / deposit; Purchase void = cancel / undo / void; Product edit = new / edit / import products; Stock adjust = record movement / import stock / adjustments; Stock transfer = transfer; suppliers new / edit / import = Purchase edit, delete = admin (as the server). Test: `security/roles-menu.spec.ts` grows a view-only pass over these pages.
- **Severity**: Major (function)
- **Status**: Found

## R20 Purchase orders and stock movements also load each row's parts one by one; no index for their lists (as R11, R12)

- **Where**: `GET /api/purchase-orders` and its CSV export (lines, products, warehouse, supplier, bills per order); `GET /api/stock-movements` and its CSV export (product and warehouse per movement). Indexes: `purchase_orders` has none on `(tenant_id, created_at)`; **`stock_movements` and `audit_log` have no index starting with the organisation at all**.
- **Evidence**: statements per call, after 3 rounds → after 30: purchase orders **32 → 275**, purchase export 33 → 276, sale export 36 → 306, stock movements 6 → 24, stock movement export 6 → 33 (every other list stays flat). EXPLAIN on the dev database: the audit log page reads backwards through *all organisations'* entries and throws away the others (`Rows Removed by Filter: 1544` for 20 rows), the stock movement page the same (`6152` removed); the purchase list sorts all the organisation's orders.
- **Impact**: the audit log and stock movements slow down with **every organisation's** data, not only one's own: on the shared server this grows with each new customer of the ERP. The purchase list as R11.
- **Proposed fix**: with R11 (`default_batch_fetch_size`) and R12, one migration: `stock_movements (tenant_id, created_at DESC, id DESC)`, `audit_log (tenant_id, created_at DESC, id DESC)`, `purchase_orders (tenant_id, created_at DESC, id DESC)`. The statement-count test of R11 covers these lists and both exports.
- **Severity**: Major (speed with big data)
- **Status**: Found

## R21 Hand-typed invoice and bill: currency starts on USD, and every order is loaded to link one

- **Where**: `app/invoices/invoice-form.tsx` (used by `/invoices/new` for both sale invoices and supplier bills): `useState("USD")`, choices USD / EUR / GBP / VND; `getSaleOrders({ size: 9999 })` and `getPurchaseOrders({ size: 9999 })` to fill the "linked order" box.
- **What is wrong**: a UK organisation's hand-typed invoice or bill is saved as **USD** unless the user changes it; the ledger posts the amounts as pounds, so the document says USD while the books say GBP. VND is a leftover. The link box downloads every order with all its lines (with R11: hundreds of queries per 100 orders) and silently stops at 9,999.
- **Evidence**: the code lines; on the dev database 27 invoices already have currency USD (made by e2e runs through this form).
- **Impact**: wrong currency on printed documents and in Xero exports; slow form once there are many orders.
- **Proposed fix**: currency starts on the organisation's currency (GBP), choices from the organisation's settings, no VND; the backend refuses a currency other than the organisation's until multi-currency exists. The linked order is a type-to-search box (as R13). A check of the 27 USD rows on dev (test data, no real data affected).
- **Severity**: Major (data: wrong currency)
- **Status**: Found

## R22 Small look and wording issues on Purchase and Inventory pages

- **Where / what**:
  1. Purchase record: Shipping details "Date" shown raw (`2026-11-17`); the Products table is wider than its card, so Tax rule and Total are cut off at 1440 px; status shown as the raw word `RECEIVED` / `ORDERED` (list and supplier page).
  2. Purchase list: dates drawn with `new Date(…).toLocaleDateString` (a day early in a browser west of UTC; also bills tab, supplier deposits, receipts); Receive errors pop up as a browser `alert()`; **Cancel on a list row fails silently** (no error shown); the bulk "Email" action does nothing ("Comes with e-mail sending").
  3. New purchase: "Tax inclusive (later)" shown greyed out (owner's choice, keep or hide); the page sends a price preview with an empty form on opening, which the server answers 400 (the only failed request of the whole run).
  4. Warehouses: Delete fails silently when the server refuses (no `onError`).
  5. Stock Transfer: subtitle in programmer's words ("creates TRANSFER_OUT + TRANSFER_IN atomically"), no list of past transfers, old layout; Stock movements: breadcrumb "Movements Log", title "Stock Movements", History a separate page: all three already agreed to move (S4, S11, S12).
  6. Bank account page: no "Back to list", "Statement balance —" with nothing after it.
  7. Records without their own history (as R16): purchase order, supplier, bill; the product record has one (Activity log).
- **Evidence**: screenshots of the browser run; the code lines named.
- **Proposed fix**: each in place with the page's move to the new layout; a shared `onError` for list actions that shows the app's message; status words through the same labels the badges use; R16's History card on purchase order, supplier and bill.
- **Severity**: Minor (item 2's silent cancel and item 4: Major for the user, small to fix)
- **Status**: Found

## R23 Money reports rebuild the whole ledger on every open

- **Where**: `LedgerService.sync(false)`, called first by the trial balance, P&L, balance sheet, account transactions, bank pages (3 places), MTD VAT review and the ledger exports. `SYNC_EVERY_MS = 0`.
- **What is wrong**: each open re-reads **every document since the conversion date** (invoices, payments, credits, bank lines, stock movements, manual journals), works out what the ledger should hold, compares it with every journal posted, and posts the difference, under a per-organisation lock (`pg_advisory_xact_lock`). So two people opening reports at once wait for each other, and the work grows with every year of history.
- **Evidence**: the code; at the probe's size the reports take 45–110 ms, so it is invisible today.
- **Impact**: checklist point 10 names exactly this. Year 3 with tens of thousands of documents: each report open reads them all.
- **Proposed fix**: post journals when a document changes (the services that issue, pay, ship already call one place), and keep `sync` as a check / repair run at night and on demand ("Check the ledger" on Ledger settings). Until then: sync only documents changed since the last sync (`updated_at` watermark per organisation). Measure on the big dataset (R18) before and after.
- **Severity**: Major (speed with big data)
- **Status**: Found

## R24 Lists that silently stop, and no limit on page size

- **Where**: manual journals (`LIMIT 500`), journal report (`LIMIT 500`), account transactions (`LIMIT 20000`), stock takes (`LIMIT 200`), pick lists (`LIMIT 100`, R11): none has paging and none says rows are missing. On the other side, every paged list accepts any `size` (`?size=99999` is served; with R11 that is thousands of queries in one call).
- **Evidence**: the SQL in `LedgerService` and `StockTakeService`; no `Math.min` on any page size in the backend.
- **Impact**: after a few years the oldest journals and stock takes cannot be found on their pages; one heavy request can slow the server for everyone.
- **Proposed fix**: paging (page + size, newest first) on these four lists, or at least "Showing the latest 500, narrow the dates" when the limit is hit; one shared rule capping `size` at 200 for every list (exports stream separately). Test: a list at its limit says so; `size=99999` returns 200 rows.
- **Severity**: Major (data hidden, robustness)
- **Status**: Found

## R25 Phone layout on every page (R15 confirmed)

- **Evidence**: at 390 px the content area is **150 px wide on all 65 pages** of this run (menu 240 px). Sign-in and register are fine (no menu).
- **Proposed fix**: R15 covers it; no separate work.
- **Status**: Found (part of R15)

## R26 Users & Roles: Cin7's page compared with ours (owner, 2026-11-17)

- **Seen in Cin7** (`/PageAccess`, look only, nothing changed or saved): Settings → **Users & Roles**, one page with two tabs.
  - **Users** tab: columns Status (Owner / Active), User (e-mail), Roles (one or more), Co-owner, a remove ×. Buttons **Export users**, **Invite a user**, **Invite an adviser**. Expert Safety: owner + 5 users, all on role FULL.
  - **Roles** tab: roles on the left ("+ Add new role"; a role opens to show **its users, each with ×, and "+ Add user"**: users are put on a role from the role side too). On the right the permissions, grouped (Audit, CRM, Dashboard, Financials, Inventory, **Locations**, Manufacturing, Purchase, Reports, Sale, Settings, **Subscription**, Tasks, Integrations…), each line with two columns **Full access** and **Read-only access**, a tick per group heading, buttons "Full access" / "Read-only access" that tick everything, Save / Cancel (Save stays grey until something changes). About 250 lines; Sale alone has view / authorise per stage (quote, order, pick, pack, ship, invoice and credit note), Purchase the same (order, stock received, invoice); Audit has Audit report, **E-mail log**, **Login history**; Locations gives access per warehouse (Expert Shop, Expert Warehouse); Settings has one line per settings page (users and roles, templates, payment terms, tax rules, locations, units, export to CSV…); Subscription has view details, update subscription, payment history, change billing address.
- **Ours** (`/users`, under Admin): one page, the users table (username, e-mail, role, access, status, created, "+ Invite User"), the two-step sign-in switch, and a Roles card under it (roles on the left with their user count, ticks on the right in groups). 29 permissions, one tick each (no full / read-only columns), coarser than Cin7 (e.g. one "Sale - Add, edit and authorise" for every stage), no per-warehouse access, Settings is one tick.
- **Differences that matter**:
  1. Place and layout: Cin7 has Settings → Users & Roles with two tabs; ours is Admin → Users with roles below (S9 already agreed: into Settings as "Users & roles").
  2. Users tab: no Export users, no Invite an adviser (our ADVISER is a tick on a role), no Owner / Co-owner (we have ADMIN), a user has one role (Cin7: several).
  3. Roles tab: users cannot be added to / removed from a role on the role itself; no "Full access" / "Read-only access" buttons or group ticks; Save always active.
  4. Permissions: no read-only column; no view / authorise per stage; **no access per warehouse**; Settings, Audit (e-mail log, login history) and Subscription (needed for the new subscription pages: who may change the plan and see payments) are one tick or missing.
- **Proposed fix** (for the owner's OK; can be done in steps):
  - **Step A, the page** (with S9 / R8): Settings → Users & roles, tabs Users | Roles like Cin7; Users: status (Owner / Active / Invited / Disabled), e-mail, roles, two-step, last sign-in, Export users, Invite a user, Invite an adviser (= a user with the Adviser role); Roles: users listed under each role with × and "+ Add user", group ticks, "Full access" / "Read-only access" buttons, Save only after a change and asks first (D2). Old `/users` leads to the new page; `AccessMatrixFlowTest` and `roles-menu.spec.ts` stay green.
  - **Step B, permissions closer to Cin7**: split *Add, edit and authorise* into view / authorise per stage where we have the stage (sale: quote, order, pick & pack, ship, invoice & credit note; purchase: order, receive, bill); Settings one tick per page; Audit: audit log, e-mail log (outbox), login history (new: sign-ins kept with time and address); Subscription: view, change plan, payment history (with the subscription steps). Existing roles keep what they could do (each old tick becomes its new ticks).
  - **Step C, access per warehouse**: a role (or user) may see / work in chosen warehouses only; lists, stock and documents filtered by it on the server (F9-style test).
  - **Step D (ask first)**: several roles per user (rights added together), Owner / Co-owner.
- **Severity**: Function gap (owner's request)
- **Status**: Found, waiting for the owner's choice of steps
