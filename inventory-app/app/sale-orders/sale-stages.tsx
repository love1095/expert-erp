import { SaleOrder } from "@/types";

// Cin7's row of stage icons on the sales list: quote → order → shipped → invoiced → paid.
// Green = done, amber = part done / in progress, grey = not yet, faint = not available (skipped, e.g. no quote, or
// after a cancel), red = where it was cancelled.
export type StageState = "done" | "part" | "todo" | "na" | "off";
type State = StageState;

const COLOR: Record<State, string> = {
  done: "text-emerald-600",
  part: "text-amber-500",
  todo: "text-gray-300",
  na:   "text-gray-200",
  off:  "text-red-400",
};

const ICON: Record<string, string> = {
  quote: "M7.5 8.25h9m-9 3H12m-9.75 1.5c0 1.6 1.12 2.99 2.7 3.22 1.13.17 2.27.3 3.43.38V21l4.12-4.12a1.14 1.14 0 01.8-.33 48.3 48.3 0 005.58-.47c1.6-.23 2.72-1.62 2.72-3.22V6.74c0-1.6-1.12-2.99-2.72-3.22A48.4 48.4 0 0012 3c-2.4 0-4.74.17-7.03.52C3.37 3.75 2.25 5.14 2.25 6.74v6.01z",
  order: "M2.25 3h1.39c.51 0 .96.34 1.09.84l.38 1.44M7.5 14.25h11.22l2.03-7.5H5.11M7.5 14.25L5.11 5.28M7.5 14.25l-2.1 2.1c-.47.47-.14 1.28.53 1.28H18.75M9 20.25a.75.75 0 11-1.5 0 .75.75 0 011.5 0zm9 0a.75.75 0 11-1.5 0 .75.75 0 011.5 0z",
  ship: "M8.25 18.75a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m3 0h6m-9 0H3.38c-.62 0-1.13-.5-1.13-1.13V14.25m17.25 4.5a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m3 0h1.13c.62 0 1.13-.5 1.12-1.13a17.9 17.9 0 00-3.21-9.86 2.06 2.06 0 00-1.58-.86H14.25M16.5 18.75h-2.25m0-11.18v-.96c0-.57-.42-1.05-.98-1.1a60.1 60.1 0 00-10.04 0c-.56.05-.98.53-.98 1.1v7.64m12 0V7.57m0 6.68H2.25",
  invoice: "M19.5 14.25v-2.63a3.38 3.38 0 00-3.38-3.37h-1.5A1.13 1.13 0 0113.5 7.13v-1.5a3.38 3.38 0 00-3.38-3.38H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.63c-.62 0-1.13.5-1.13 1.13v17.25c0 .62.5 1.12 1.13 1.12h12.75c.62 0 1.12-.5 1.12-1.12V11.25a9 9 0 00-9-9z",
  // Goods in (purchase receipts)
  receive: "M20.25 7.5l-.63 10.63a2.25 2.25 0 01-2.24 2.12H6.62a2.25 2.25 0 01-2.24-2.12L3.75 7.5m8.25 3v6.75m0 0l-3-3m3 3l3-3M3.38 7.5h17.25c.62 0 1.12-.5 1.12-1.12v-1.5c0-.63-.5-1.13-1.12-1.13H3.38c-.63 0-1.13.5-1.13 1.13v1.5c0 .62.5 1.12 1.13 1.12z",
  paid: "M2.25 8.25h19.5M2.25 9h19.5m-16.5 5.25h6m-6 2.25h3m-3.75 3h15a2.25 2.25 0 002.25-2.25V6.75A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25v10.5A2.25 2.25 0 004.5 19.5z",
};

const WORD: Record<State, string> = { done: "done", part: "in part", todo: "not yet", na: "not available", off: "cancelled" };

// After a cancel / rejection nothing more will happen: steps not reached are not available
export function closeStages<S extends { state: State }>(stages: S[], closed: boolean): S[] {
  return closed ? stages.map(s => (s.state === "todo" ? { ...s, state: "na" as State } : s)) : stages;
}

export function stagesOf(o: SaleOrder): { key: string; label: string; state: State }[] {
  const wasQuote = o.status === "QUOTE" || o.status === "REJECTED" || o.quoteValidUntil != null;
  const invoices = (o.invoices ?? []).filter(i => i.kind !== "CREDIT_NOTE" && i.status !== "CANCELLED");
  const invoiced = Number(o.invoicedTotal ?? 0);
  const toInvoice = Number(o.toInvoiceTotal ?? 0);
  const paid = invoices.reduce((t, i) => t + Number(i.amountPaid), 0);
  const isOrder = o.status === "CONFIRMED" || o.status === "COMPLETED";

  return closeStages<{ key: string; label: string; state: State }>([
    // Made as an order straight away: Cin7 shows the quote step as not available
    { key: "quote", label: "Quote",
      state: o.status === "REJECTED" ? "off" : o.status === "QUOTE" ? "part" : wasQuote ? "done" : "na" },
    { key: "order", label: "Order",
      state: o.status === "CANCELLED" ? "off" : isOrder ? "done" : o.status === "DRAFT" ? "part" : "todo" },
    { key: "ship", label: "Shipped",
      state: o.fulfilmentStatus === "FULFILLED" ? "done" : o.fulfilmentStatus === "PARTIALLY_FULFILLED" ? "part" : "todo" },
    { key: "invoice", label: "Invoiced",
      state: invoiced > 0 ? (toInvoice > 0.004 ? "part" : "done") : "todo" },
    { key: "paid", label: "Paid",
      state: invoiced > 0 && paid >= invoiced - 0.004 ? "done" : paid > 0 ? "part" : "todo" },
  ], o.status === "CANCELLED" || o.status === "REJECTED");
}

export default function SaleStages({ order }: { order: SaleOrder }) {
  return <StageIcons stages={stagesOf(order)} testId={`so-stages-${order.id}`} />;
}

// A row of stage icons (key = icon: quote, order, ship, receive, invoice, paid)
export function StageIcons({ stages, testId }: { stages: { key: string; label: string; state: StageState }[]; testId?: string }) {
  return (
    <span data-testid={testId} className="inline-flex items-center gap-1"
      aria-label={stages.map(s => `${s.label}: ${WORD[s.state]}`).join(", ")}>
      {stages.map(s => (
        <span key={s.key} title={`${s.label}: ${WORD[s.state]}`} data-stage={s.key} data-state={s.state}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round"
            aria-hidden="true" className={`w-4 h-4 ${COLOR[s.state]}`}>
            <path d={ICON[s.key]} />
          </svg>
        </span>
      ))}
    </span>
  );
}

// "INV-118520, CR-00231" like Cin7's Documents column; cancelled ones struck through
export function OrderDocuments({ order }: { order: SaleOrder }) {
  const docs = order.invoices ?? [];
  if (docs.length === 0) return <span className="text-gray-300">—</span>;
  return (
    <span className="text-xs font-mono">
      {docs.map((d, i) => (
        <span key={d.id}>
          {i > 0 && ", "}
          <span className={d.status === "CANCELLED" ? "line-through text-gray-400" : d.kind === "CREDIT_NOTE" ? "text-gray-600" : "text-gray-800"}>
            {d.invoiceNumber}
          </span>
        </span>
      ))}
    </span>
  );
}
