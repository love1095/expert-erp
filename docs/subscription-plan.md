# Subscription plans: pricing page, sign-up with a trial, payment by Stripe

Owner's decisions (2026-11-17):
- People choose and buy a plan in **both** places: a public Pricing page before sign-up, and Settings → Subscription inside the app.
- Payment by **Stripe** (Checkout + Billing), built with a fake Stripe first like the fake Xero, live keys later.
- **The owner gives the plans** (names, prices in £ monthly and yearly, what each includes). The pages read the plans from the database, so they are built now and filled with the owner's plans when they arrive.
- A new company starts with a **14-day free trial**, no card needed; at the end it pays or the organisation becomes read-only.

## What exists today

- Plans Free / Pro / Enterprise (`plans` table, seeded by `DataSeeder`): $0 / $49 / $199 a month, limits on products (50 / 500 / unlimited), users (3 / 25 / unlimited), warehouses (2 / 10 / unlimited). Prices shown in **$**.
- `/register` creates an organisation on the Free plan. No plan choice, no trial, no payment.
- Settings → Subscription shows the plan, usage bars and the three plan cards with "contact Expert ERP to change your plan". Only the super admin gives a plan (Platform → Organisations).
- Limits are checked when a product, user or warehouse is added (`SubscriptionService`, `PlanLimitExceededException`); no module is tied to a plan.

## The pages

### 1. Public Pricing page `/pricing` (no sign-in)
- Header: logo, "Sign in", "Start free trial".
- Monthly / Yearly switch (yearly shows the saving).
- One card per plan from `GET /api/plans` (public): name, one-line who it is for, price in £ (ex VAT, "+ VAT" shown), main limits, the modules it has, button **Start 14-day free trial** → `/register?plan=<code>&billing=monthly|yearly`. One plan can be marked "Most popular". A last card "Need more? Contact us" if the owner wants one.
- A compare table under the cards: every limit and module, ticks per plan.
- Short FAQ: what happens after the trial, can I change plan, VAT, cancel any time, where the data is kept.
- Footer: © Expert ERP · Privacy · Terms (texts from the owner).
- Works at phone width (cards one under the other, the compare table scrolls sideways inside its box).

### 2. Sign-up `/register`
- Shows the chosen plan and billing at the top ("Growth, monthly: 14 days free, then £x + VAT a month"), with "Change" back to `/pricing`. Opened without a plan: the plan the owner marks as default.
- Same fields as today (organisation, username, e-mail, password); e-mail becomes required (trial reminders and Stripe receipts go there).
- Creates the organisation on that plan with status **TRIAL**, trial ending in 14 days. No card asked.

### 3. Inside the app
- **Trial banner** at the top for the admin: "Trial: 9 days left · Choose your plan" (amber in the last 3 days). Other users see nothing until the last 3 days.
- **Settings → Subscription** (admin; others see their plan read-only):
  - Current plan, status (Trial / Active / Payment failed / Cancelled / Read-only), next payment date and amount, usage bars.
  - Plan cards with Monthly / Yearly switch: **Choose** (from a trial) or **Upgrade / Downgrade** → Stripe Checkout (first payment) or a change on the Stripe subscription (later changes, prorated by Stripe).
  - **Manage billing** → Stripe's customer portal (card, billing address, VAT number, invoices, cancel).
  - Invoices list (date, amount, paid, PDF link from Stripe).
- **Read-only** (trial ended unpaid, or payment failed and 7 days of grace over): every page opens, lists, reports and exports work, nothing can be created or changed; a red bar "Your subscription has ended: choose a plan to carry on" with the button. Data is kept.
- **Downgrade** over a limit (e.g. 600 products on a 500 plan): refused with what to reduce, or applied at the end of the paid period (owner to choose when the plans arrive).

### 4. Platform (super admin)
- Can still give any organisation a plan by hand (status **MANUAL**, no Stripe): Expert Safety stays like this.
- Plans page: edit plans (name, prices, limits, modules, public or hidden, order, most popular) and their Stripe price ids.
- Stripe keys on Platform → Integration apps (as Xero's): secret key, webhook signing secret, test or live.

## How payment works (Stripe)
- `StripeClient` interface with a **fake** (dev and tests: Checkout returns straight to the app as paid, webhooks sent by the fake) and a **live** client; `APP_STRIPE_MODE=fake|live` like Xero.
- Choose / upgrade: the server creates a Checkout Session (subscription mode, the plan's monthly or yearly Stripe price, customer = the organisation, Stripe Tax for UK VAT) and the browser goes to Stripe; back on `/settings/subscription?paid=1`.
- What is true comes from Stripe's **webhooks** (`/api/webhooks/stripe`, signature checked): `checkout.session.completed`, `customer.subscription.updated / deleted`, `invoice.paid`, `invoice.payment_failed` → the organisation's plan, status, period end. The return page only shows "We are confirming your payment" until the webhook arrives.
- Every change is in the audit log (who chose what, payments, failures).

## Data
- `plans`: code, name, description, monthly and yearly price in £, Stripe price ids, limits (products, users, warehouses), modules (accounting + MTD VAT, Xero, Shopify, API, … as the owner lists them), public, sort order, most popular.
- `subscriptions`: status (TRIAL, ACTIVE, PAST_DUE, CANCELLED, READ_ONLY, MANUAL), billing (monthly / yearly), trial ends at, current period end, cancel at period end, Stripe customer id, Stripe subscription id.
- `subscription_invoices` (copied from Stripe's webhooks): number, date, amount, VAT, status, PDF link.

## Steps (each built, tested and committed on its own)
1. **Plan model** (prices in £, monthly and yearly, modules, public, order) and the **public Pricing page**; the owner's plans entered when they arrive (placeholders meanwhile).
2. **Sign-up with a plan and a 14-day trial**, trial banner, **read-only** at the end; e-mail reminders 3 days and 1 day before (phase M e-mail).
3. **Stripe, fake first**: Checkout, webhooks, Settings → Subscription with choose / upgrade / downgrade, invoices, Manage billing, payment failed → 7 days → read-only.
4. **Modules by plan**: pages and API of a module the plan does not have are hidden and refused (F8's access test extended).
5. **Platform**: plans page, Stripe keys, manual plans.
6. **Live Stripe in test mode** with the owner's Stripe account, then live.

Tests: backend `SubscriptionFlowTest` (trial → read-only, checkout → active, payment failed → grace → read-only, downgrade over a limit, manual plan untouched), webhook signature refused when wrong; e2e: pricing page → sign-up → trial banner → choose plan (fake Stripe) → active.

## Needs the owner
- **The plans**: names, £ prices monthly and yearly (ex VAT), limits, which modules each has, the default plan, "Most popular", a "Contact us" plan or not.
- **Stripe account** (test keys first; live keys at launch).
- Is Expert ERP's company **VAT registered** (VAT on the subscription, Stripe Tax)?
- Texts for **Terms** and **Privacy**.
- Downgrade over a limit: refuse, or apply at the end of the paid period.
