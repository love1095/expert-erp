"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import Link from "next/link";
import { getSaleOrder, confirmSaleOrder, fulfillSaleOrder, cancelSaleOrder, createSaleReturn, retryShopifyFulfilment,
  convertQuote, rejectQuote, getCustomers, invoiceSaleOrder } from "@/lib/api";
import { Customer, FulfilmentStatus, SaleOrder, SaleOrderLine, SaleOrderStatus, Warehouse } from "@/types";
import SaleOrderForm from "./sale-order-form";
import SaleStages from "./sale-stages";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import { Clip, ListHead } from "@/components/ListToolbar";
import { downloadCsv } from "@/components/BulkActions";
import { BTN_PRIMARY, BTN_SECONDARY, TrailCard, Field } from "@/components/DetailParts";

type Mode = "view" | "ship" | "return" | "edit" | "invoice";

// One key per submission attempt: a retried click/network retry is applied only once by the backend
const newKey = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;

const qty = (n: number) => Number(n).toLocaleString();

export const STATUS_COLOR: Record<SaleOrderStatus, string> = {
  QUOTE:     "bg-violet-100 text-violet-700",
  REJECTED:  "bg-gray-100 text-gray-400",
  DRAFT:     "bg-amber-100 text-amber-700",
  CONFIRMED: "bg-sky-100 text-sky-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

export const FULFILMENT_LABEL: Record<FulfilmentStatus, { label: string; color: string }> = {
  UNFULFILLED:         { label: "Not shipped", color: "text-gray-400" },
  PARTIALLY_FULFILLED: { label: "Partly shipped", color: "text-amber-600" },
  FULFILLED:           { label: "Shipped", color: "text-emerald-600" },
};

const INVOICE_COLOR: Record<string, string> = {
  CANCELLED: "bg-gray-100 text-gray-400", PAID: "bg-emerald-100 text-emerald-700",
  ISSUED: "bg-sky-100 text-sky-700", DRAFT: "bg-amber-100 text-amber-700",
};

export default function SaleOrderView({ orderId, warehouses }: { orderId: number; warehouses: Warehouse[] }) {
  const qc = useQueryClient();
  const { data: order, isLoading } = useQuery<SaleOrder>({
    queryKey: ["sale-order", orderId],
    queryFn: () => getSaleOrder(orderId),
    // A shipment is sent to Shopify just after it is saved: check back until the outcome is known
    refetchInterval: q => (q.state.data?.fulfilments?.some(f => f.syncStatus === "PENDING") ? 2000 : false),
  });

  const [mode, setMode] = useState<Mode>("view");
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [restock, setRestock] = useState<Record<number, boolean>>({});
  const [notes, setNotes] = useState("");
  const [tracking, setTracking] = useState({ company: "", number: "" });
  const [returnWarehouseId, setReturnWarehouseId] = useState("");
  const [submitKey, setSubmitKey] = useState(newKey);
  const [error, setError] = useState("");

  const onDone = (updated: SaleOrder) => {
    qc.setQueryData(["sale-order", orderId], updated);
    qc.invalidateQueries({ queryKey: ["sale-orders"] });
    qc.invalidateQueries({ queryKey: ["stock-overview"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["products-page"] });
    setMode("view");
    setError("");
  };
  const onFail = (e: unknown) =>
    setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Request failed");

  const confirmMutation = useMutation({ mutationFn: confirmSaleOrder, onSuccess: onDone, onError: onFail });
  const cancelMutation  = useMutation({ mutationFn: cancelSaleOrder,  onSuccess: onDone, onError: onFail });
  const shipMutation    = useMutation({ mutationFn: fulfillSaleOrder, onSuccess: onDone, onError: onFail });
  const returnMutation  = useMutation({ mutationFn: createSaleReturn, onSuccess: onDone, onError: onFail });
  const retryMutation   = useMutation({ mutationFn: retryShopifyFulfilment, onSuccess: onDone, onError: onFail });
  const convertMutation = useMutation({ mutationFn: convertQuote, onSuccess: onDone, onError: onFail });
  const rejectMutation  = useMutation({ mutationFn: rejectQuote, onSuccess: onDone, onError: onFail });
  // An invoice doesn't change the order itself: reload it to show the new invoice and amounts
  const invoiceMutation = useMutation({
    mutationFn: invoiceSaleOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sale-order", orderId] });
      qc.invalidateQueries({ queryKey: ["invoices"] });
      setMode("view");
      setError("");
    },
    onError: onFail,
  });
  const [includeCharges, setIncludeCharges] = useState(true);
  const busy = confirmMutation.isPending || cancelMutation.isPending || shipMutation.isPending || returnMutation.isPending
    || retryMutation.isPending || convertMutation.isPending || rejectMutation.isPending || invoiceMutation.isPending;
  // Only needed for editing
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers, enabled: mode === "edit" });

  if (isLoading || !order) return <p className="text-gray-400 text-sm px-2 py-3">Loading order...</p>;
  const backLink = (
    <Link href="/sale-orders" className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-800 mb-3">‹ Back to list</Link>
  );

  if (mode === "edit") {
    return (
      <div data-testid={`so-panel-${orderId}`}>
        {backLink}
        <SaleOrderForm editing={order} customers={customers} warehouses={warehouses}
          onCreated={onDone} onCancel={() => setMode("view")} />
      </div>
    );
  }
  const editable = (order.status === "QUOTE" || order.status === "DRAFT") && order.source === "MANUAL";

  const shipped    = order.lines.some(l => l.fulfilledQuantity > 0);
  const returnable = order.lines.some(l => l.fulfilledQuantity - l.returnedQuantity > 0);
  // Like Cin7, a line can only ship what is allocated to it (first confirmed, first served)
  const allocatedOf = (l: SaleOrderLine) => Math.min(l.allocatedQuantity ?? 0, l.remainingQuantity);
  const canShip    = order.status === "CONFIRMED" && order.lines.some(l => allocatedOf(l) > 0);
  const waitingForStock = order.status === "CONFIRMED" && !canShip && order.lines.some(l => l.remainingQuantity > 0);
  const canCancel  = order.status === "DRAFT" || order.status === "CONFIRMED";
  const canReturn  = (order.status === "CONFIRMED" || order.status === "COMPLETED") && returnable;
  const canInvoice = (order.status === "CONFIRMED" || order.status === "COMPLETED") && (order.toInvoiceTotal ?? 0) > 0;

  const maxFor = (l: SaleOrderLine) =>
    mode === "ship" ? allocatedOf(l) : mode === "invoice" ? l.toInvoiceQuantity : l.fulfilledQuantity - l.returnedQuantity;

  const startMode = (m: Mode) => {
    const prefill: Record<number, string> = {};
    // Shipping defaults to everything allocated, invoicing to everything not invoiced yet; returns start empty
    order.lines.forEach(l => {
      const q = m === "ship" ? allocatedOf(l) : m === "invoice" ? l.toInvoiceQuantity : 0;
      prefill[l.id] = q > 0 ? String(q) : "";
    });
    setIncludeCharges(true);
    setAmounts(prefill);
    setRestock(Object.fromEntries(order.lines.map(l => [l.id, true])));
    setNotes("");
    setTracking({ company: "", number: "" });
    setReturnWarehouseId(String(order.warehouseId));
    setSubmitKey(newKey());
    setError("");
    setMode(m);
  };

  const selectedLines = () =>
    order.lines
      .map(l => ({ line: l, quantity: Number(amounts[l.id] || 0) }))
      .filter(x => x.quantity > 0);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const chosen = selectedLines();
    const chargesOnly = mode === "invoice" && includeCharges && order.charges.some(c => !c.invoiced);
    if (chosen.length === 0 && !chargesOnly) { setError("Enter a quantity for at least one line"); return; }
    const over = chosen.find(x => x.quantity > maxFor(x.line));
    if (over) { setError(`${over.line.productSku}: at most ${qty(maxFor(over.line))}`); return; }

    if (mode === "invoice") {
      const issue = ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value === "issue";
      invoiceMutation.mutate({ id: order.id, data: {
        issue, includeCharges, notes: notes || undefined,
        lines: chosen.map(x => ({ saleOrderLineId: x.line.id, quantity: x.quantity })),
      }});
    } else if (mode === "ship") {
      shipMutation.mutate({ id: order.id, data: {
        idempotencyKey: submitKey,
        notes: notes || undefined,
        trackingCompany: tracking.company || undefined,
        trackingNumber: tracking.number || undefined,
        lines: chosen.map(x => ({ saleOrderLineId: x.line.id, quantity: x.quantity })),
      }});
    } else {
      returnMutation.mutate({ id: order.id, data: {
        idempotencyKey: submitKey,
        reason: notes || undefined,
        warehouseId: Number(returnWarehouseId),
        lines: chosen.map(x => ({ saleOrderLineId: x.line.id, quantity: x.quantity, restock: restock[x.line.id] ?? true })),
      }});
    }
  };

  const goods = order.lines.reduce((t, l) => ({ net: t.net + Number(l.netAmount), tax: t.tax + Number(l.taxAmount ?? 0) }), { net: 0, tax: 0 });
  const extras = order.charges.reduce((t, c) => ({ net: t.net + Number(c.netAmount), tax: t.tax + Number(c.taxAmount ?? 0) }), { net: 0, tax: 0 });
  const showCancelled = order.lines.some(l => Number(l.cancelledQuantity) > 0);
  const showReturned = order.lines.some(l => Number(l.returnedQuantity) > 0);
  // [heading, right-aligned]
  const lineCols: [string, boolean][] = [["Product", false], ["Quantity", true], ["Shipped", true],
    ...(showCancelled ? [["Cancelled", true] as [string, boolean]] : []), ...(showReturned ? [["Returned", true] as [string, boolean]] : []),
    ["To ship", true], ["Allocated", true], ["Price", true], ["Disc.", true], ["Tax rule", false], ["Margin", true], ["Total", true]];
  const exportLines = () => downloadCsv(`${order.orderNumber}-lines.csv`,
    ["SKU", "Product", "Quantity", "Shipped", "Cancelled", "Returned", "Price", "Discount %", "Tax rule", "Margin %", "Total"],
    order.lines.map(l => [l.productSku, l.productName, l.quantity, l.fulfilledQuantity, l.cancelledQuantity, l.returnedQuantity,
      l.unitPrice, l.discountPercent, l.taxName, l.marginPercent, l.netAmount]));
  const invoices = (order.invoices ?? []).filter(i => i.kind !== "CREDIT_NOTE");
  const creditNotes = (order.invoices ?? []).filter(i => i.kind === "CREDIT_NOTE");
  const paidTotal = invoices.filter(i => i.status !== "CANCELLED").reduce((t, i) => t + Number(i.amountPaid), 0);
  const showInvoices = invoices.length > 0 || creditNotes.length > 0 || canInvoice;

  return (
    <div data-testid={`so-panel-${orderId}`}>
      {backLink}

      {/* Title and the actions for the order's stage */}
      <div className="flex flex-wrap items-start justify-between gap-3 mb-5">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            {order.status === "QUOTE" || order.status === "REJECTED" ? "Quote" : "Sale"} {order.orderNumber}
            <span className="font-normal text-gray-500"> for {order.customerName}</span>
          </h1>
          <div data-testid={`so-status-${orderId}`} className="flex flex-wrap items-center gap-1.5 mt-1.5">
            <SaleStages order={order} />
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[order.status]}`}>{order.status}</span>
            {(order.status === "CONFIRMED" || order.status === "COMPLETED") && (
              <span className={`text-xs font-medium ${FULFILMENT_LABEL[order.fulfilmentStatus].color}`}>
                {FULFILMENT_LABEL[order.fulfilmentStatus].label}
              </span>
            )}
            {order.quoteExpired && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-red-50 text-red-700">Expired</span>}
            {order.backordered && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-orange-100 text-orange-800">Backordered</span>}
            {order.needsReview && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-amber-100 text-amber-800">Needs review</span>}
            {order.source === "SHOPIFY" && (
              <span className="text-xs font-semibold px-2 py-0.5 rounded bg-emerald-100 text-emerald-700">Shopify {order.externalReference}</span>
            )}
          </div>
        </div>

        {mode === "view" && (
          <div className="flex flex-wrap items-center gap-2">
            {order.status === "QUOTE" && (
              <>
                <button disabled={busy} onClick={() => { if (confirm("Reject this quote?")) rejectMutation.mutate(order.id); }} className={BTN_SECONDARY}>
                  Reject quote
                </button>
                <button disabled={busy || order.quoteExpired} onClick={() => convertMutation.mutate(order.id)}
                  title={order.quoteExpired ? "Expired: edit the quote and give it a new valid-until date first" : undefined}
                  className={BTN_PRIMARY}>
                  Convert to order
                </button>
              </>
            )}
            {canCancel && (
              <button disabled={busy}
                onClick={() => {
                  const msg = shipped
                    ? "Cancel the quantities not yet shipped? Shipped items are kept; the order will be completed."
                    : "Cancel this order?";
                  if (confirm(msg)) cancelMutation.mutate(order.id);
                }}
                className="border border-red-200 bg-white text-red-600 px-3 py-2 rounded-md text-sm font-medium hover:bg-red-50 disabled:opacity-50">
                {shipped ? "Cancel remaining" : "Cancel order"}
              </button>
            )}
            {editable && (
              <button disabled={busy} onClick={() => { setError(""); setMode("edit"); }} className={BTN_SECONDARY}>Edit</button>
            )}
            {canReturn && <button disabled={busy} onClick={() => startMode("return")} className={BTN_SECONDARY}>Record return</button>}
            {canInvoice && <button disabled={busy} onClick={() => startMode("invoice")} className={BTN_SECONDARY}>Create invoice</button>}
            {canShip && <button disabled={busy} onClick={() => startMode("ship")} className={BTN_PRIMARY}>Ship items</button>}
            {order.status === "DRAFT" && (
              <button disabled={busy} onClick={() => confirmMutation.mutate(order.id)} className={BTN_PRIMARY}>
                {order.needsReview ? "Confirm as it is" : "Confirm order"}
              </button>
            )}
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[17rem_minmax(0,1fr)] gap-5 items-start">
        {/* Left: the order's documents, in the order they happen */}
        <aside className="space-y-4">
          <TrailCard title="Shipments"
            badge={order.status === "CONFIRMED" || order.status === "COMPLETED"
              ? <span className={`text-[11px] font-semibold ${FULFILMENT_LABEL[order.fulfilmentStatus].color}`}>{FULFILMENT_LABEL[order.fulfilmentStatus].label}</span>
              : undefined}>
            {(order.fulfilments?.length ?? 0) === 0 && <p className="text-gray-400 text-xs">Nothing shipped yet</p>}
            {order.fulfilments?.map(f => (
              <div key={f.id} className="border-t border-gray-100 first:border-0 pt-2 first:pt-0">
                <p className="font-medium text-gray-800">Shipment #{f.id}</p>
                <p className="text-xs text-gray-600">{f.lines.map(l => `${qty(l.quantity)} × ${l.productSku}`).join(", ")}</p>
                <p className="text-[11px] text-gray-400">
                  <ClientDate iso={f.createdAt} showTime /> · {f.createdBy}{f.notes ? ` · ${f.notes}` : ""}
                  {f.trackingNumber && ` · ${f.trackingCompany ? `${f.trackingCompany} ` : ""}${f.trackingNumber}`}
                </p>
                {f.syncStatus && (
                  <p data-testid={`shipment-sync-${f.id}`} className="text-xs mt-0.5">
                    {f.syncStatus === "SYNCED" && <span className="text-emerald-700">✓ Sent to Shopify{f.syncMessage ? ` (${f.syncMessage})` : ""}</span>}
                    {f.syncStatus === "PENDING" && <span className="text-gray-500">Sending to Shopify…</span>}
                    {f.syncStatus === "FAILED" && (
                      <>
                        <span className="text-red-600">✕ Not sent to Shopify: {f.syncMessage}</span>
                        <button type="button" disabled={busy} onClick={() => retryMutation.mutate({ id: order.id, fulfilmentId: f.id })}
                          className="ml-2 text-xs font-medium text-brand-700 hover:underline disabled:opacity-50">
                          {retryMutation.isPending ? "Retrying..." : "Retry"}
                        </button>
                      </>
                    )}
                  </p>
                )}
              </div>
            ))}
            {canShip && mode === "view" && (
              <button type="button" disabled={busy} onClick={() => startMode("ship")}
                className="w-full mt-1 border border-dashed border-gray-300 rounded-md py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50">
                + Shipment
              </button>
            )}
          </TrailCard>

          {showInvoices && (
            <TrailCard title="Invoices" testId={`so-invoices-${orderId}`}>
              <p className="text-xs text-gray-500">
                to invoice {formatGBP(order.toInvoiceTotal ?? 0)} · invoiced {formatGBP(order.invoicedTotal ?? 0)}
                {(order.creditedTotal ?? 0) > 0 && ` · credited ${formatGBP(order.creditedTotal!)}`}
              </p>
              {[...invoices, ...creditNotes].map(inv => (
                <div key={inv.id} className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  <Link href={`/invoices?q=${encodeURIComponent(inv.invoiceNumber)}`} className="font-mono text-xs font-medium text-brand-700 hover:underline">{inv.invoiceNumber}</Link>
                  {inv.kind === "CREDIT_NOTE" && <span className="text-[10px] font-semibold text-gray-600">Credit note</span>}
                  <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${INVOICE_COLOR[inv.status]}`}>{inv.status}</span>
                  <span className="ml-auto tabular-nums text-gray-800">{formatGBP(inv.total)}</span>
                  <span className="w-full text-[11px] text-gray-400">
                    {inv.dueDate && `due ${inv.dueDate}`}{inv.fulfilmentId && ` · for shipment #${inv.fulfilmentId}`}
                  </span>
                </div>
              ))}
              {canInvoice && mode === "view" && (
                <button type="button" disabled={busy} onClick={() => startMode("invoice")}
                  className="w-full mt-1 border border-dashed border-gray-300 rounded-md py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50">
                  + Invoice
                </button>
              )}
            </TrailCard>
          )}

          {invoices.some(i => i.status !== "CANCELLED") && (
            <TrailCard title="Payments"
              badge={<span className="text-[11px] font-semibold text-gray-500">{formatGBP(paidTotal)} paid</span>}>
              {invoices.filter(i => i.status !== "CANCELLED").map(i => (
                <div key={i.id} className="flex items-center justify-between text-xs">
                  <span className="font-mono text-gray-600">{i.invoiceNumber}</span>
                  <span className={`tabular-nums ${Number(i.amountPaid) >= Number(i.total) ? "text-emerald-700" : "text-gray-700"}`}>
                    {formatGBP(i.amountPaid)} of {formatGBP(i.total)}
                  </span>
                </div>
              ))}
            </TrailCard>
          )}

          {((order.returns?.length ?? 0) > 0 || canReturn) && (
            <TrailCard title="Returns">
              {(order.returns?.length ?? 0) === 0 && <p className="text-gray-400 text-xs">No returns</p>}
              {order.returns?.map(r => (
                <div key={r.id} className="border-t border-gray-100 first:border-0 pt-2 first:pt-0">
                  <p className="font-medium text-gray-800">Return #{r.id}</p>
                  <p className="text-xs text-gray-600">
                    {r.lines.map(l => `${qty(l.quantity)} × ${l.productSku}${l.restock ? "" : " (not restocked)"}`).join(", ")} → {r.warehouseName}
                  </p>
                  <p className="text-[11px] text-gray-400"><ClientDate iso={r.createdAt} showTime /> · {r.createdBy}{r.reason ? ` · ${r.reason}` : ""}</p>
                </div>
              ))}
            </TrailCard>
          )}
        </aside>

        {/* Main: the order itself */}
        <div className="space-y-4 min-w-0">
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          {order.needsReview && (
            <div data-testid={`so-review-${orderId}`} className="text-sm text-amber-900 bg-amber-50 border border-amber-200 px-3 py-2 rounded">
              <p className="font-semibold">Needs review before shipping</p>
              <p className="mt-0.5">{order.reviewReason}</p>
              <p className="mt-1 text-xs text-amber-700">
                Items not found in the ERP are not on this order. Fix them in Shopify or add the products here, or confirm to accept the order as it is.
              </p>
            </div>
          )}

          {order.backordered && (
            <p data-testid={`so-backordered-${orderId}`} className="text-sm text-orange-900 bg-orange-50 border border-orange-200 px-3 py-2 rounded">
              <b>Backordered:</b>{" "}
              {order.lines.filter(l => (l.backorderedQuantity ?? 0) > 0)
                .map(l => `${qty(l.backorderedQuantity!)} × ${l.productSku}`).join(", ")} short. Stock goes to orders in the order they were confirmed.
            </p>
          )}
          {order.status === "DRAFT" && mode === "view" && (
            <p className="text-xs text-gray-500">Confirm the order before shipping.</p>
          )}
          {waitingForStock && mode === "view" && (
            <p className="text-xs text-orange-700">Nothing to ship yet: the stock on hand is promised to orders confirmed earlier.</p>
          )}

          {/* Header, in Cin7's three columns */}
          <section data-testid={`so-details-${orderId}`} className="bg-white border border-gray-200 rounded-lg p-5 grid grid-cols-1 md:grid-cols-3 gap-6">
            <div>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Customer details</h3>
              <dl className="space-y-2.5">
                <Field label="Customer" value={order.customerName} />
                <Field label="Reference" value={order.customerReference} />
                <Field label="Order" value={order.orderNumber} />
              </dl>
            </div>
            <div>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Accounting details</h3>
              <dl className="space-y-2.5">
                <Field label="Terms" value={order.paymentTerm} />
                <Field label="Sales rep" value={order.salesRep} />
                {order.status === "QUOTE" ? (
                  <div data-testid={`so-valid-until-${orderId}`}>
                    <Field label="Quote valid until" warn={order.quoteExpired}
                      value={<>{order.quoteValidUntil ?? "—"}{order.quoteExpired && " · Expired"}</>} />
                  </div>
                ) : (
                  <Field label="Ordered" value={order.orderedAt ? <ClientDate iso={order.orderedAt} /> : undefined} />
                )}
              </dl>
            </div>
            <div>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Shipping details</h3>
              <dl className="space-y-2.5">
                <Field label="Location" value={order.warehouseName} />
                <Field label="Ship by" value={order.shipBy} />
                <Field label="Carrier / service" value={order.carrier} />
                {order.shippingNotes && <Field label="Shipping notes" value={order.shippingNotes} />}
              </dl>
            </div>
          </section>

          {/* Lines (with ship / invoice / return inputs when one of those is open) */}
          <form onSubmit={submit} className="space-y-4">
            <section className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
                <h3 className="text-sm font-semibold text-gray-800">Products</h3>
                {mode !== "view" && (
                  <span className="text-xs font-semibold text-brand-700">
                    {mode === "ship" ? "Shipping: enter what goes out now" : mode === "invoice" ? "Invoicing: enter what to bill" : "Return: enter what came back"}
                  </span>
                )}
              </div>
              <div className="overflow-x-auto">
                {/* Like Cin7: "SKU: name" on one line, numbers right-aligned, a total row and Export; cancelled / returned
                    columns only when something was cancelled or returned */}
                <table className="list-table">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      {lineCols.map(([h, right]) => (
                        <th key={h} className={`${right ? "text-right" : "text-left"} text-xs font-semibold text-gray-500 uppercase tracking-wide`}>{h}</th>
                      ))}
                      {mode === "ship" && <th className="text-left text-xs font-semibold text-gray-900 uppercase">Ship now</th>}
                      {mode === "invoice" && <th className="text-left text-xs font-semibold text-gray-900 uppercase">Invoice now</th>}
                      {mode === "return" && (
                        <>
                          <th className="text-left text-xs font-semibold text-gray-900 uppercase">Return qty</th>
                          <th className="text-left text-xs font-semibold text-gray-900 uppercase">Back to stock</th>
                        </>
                      )}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {order.lines.map(l => {
                      const max = maxFor(l);
                      return (
                        <tr key={l.id}>
                          <td className="text-gray-800"><Clip text={`${l.productSku}: ${l.productName}`} width="12rem" /></td>
                          <td className="num">{qty(l.quantity)}</td>
                          <td className="num text-emerald-700">{qty(l.fulfilledQuantity)}</td>
                          {showCancelled && <td className="num text-gray-500">{qty(l.cancelledQuantity)}</td>}
                          {showReturned && <td className="num text-gray-600">{qty(l.returnedQuantity)}</td>}
                          <td className="num font-medium">{qty(l.remainingQuantity)}</td>
                          <td className={`num ${(l.backorderedQuantity ?? 0) > 0 ? "text-orange-700 font-semibold" : "text-gray-500"}`}
                            title={(l.backorderedQuantity ?? 0) > 0 ? `${qty(l.backorderedQuantity!)} short` : undefined}>
                            {l.allocatedQuantity != null ? qty(l.allocatedQuantity) : "—"}
                          </td>
                          <td className="num">{l.unitPrice != null ? formatGBP(l.unitPrice) : "—"}</td>
                          <td className="num text-gray-500">{l.discountPercent ? `${l.discountPercent}%` : "—"}</td>
                          <td className="text-gray-600" title={l.taxOverridden ? "Changed by hand" : l.taxName}>
                            <span className="block truncate max-w-24">{l.taxName ?? "Not recorded"}{l.taxOverridden && " ✎"}</span>
                          </td>
                          <td className="num text-gray-500">{l.marginPercent != null ? `${l.marginPercent}%` : "—"}</td>
                          <td className="num">{formatGBP(l.netAmount)}</td>
                          {mode !== "view" && (
                            <td>
                              <input type="number" min="0" max={max} step="any" disabled={max <= 0}
                                aria-label={`${mode === "ship" ? "Ship" : mode === "invoice" ? "Invoice" : "Return"} quantity for ${l.productSku}`}
                                value={amounts[l.id] ?? ""} placeholder={max > 0 ? `max ${qty(max)}` : "—"}
                                onChange={e => setAmounts({ ...amounts, [l.id]: e.target.value })}
                                className="w-24 border border-gray-300 rounded-md px-2 py-1 text-sm disabled:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-brand-400" />
                            </td>
                          )}
                          {mode === "return" && (
                            <td>
                              <input type="checkbox" disabled={max <= 0} checked={restock[l.id] ?? true}
                                aria-label={`Back to stock for ${l.productSku}`}
                                onChange={e => setRestock({ ...restock, [l.id]: e.target.checked })} />
                            </td>
                          )}
                        </tr>
                      );
                    })}
                  </tbody>
                  <tfoot className="border-t border-gray-200 font-semibold text-gray-800">
                    <tr>
                      <td className="text-right">Total:</td>
                      <td className="num">{qty(order.lines.reduce((t, l) => t + Number(l.quantity), 0))}</td>
                      <td colSpan={lineCols.length - 3}></td>
                      <td className="num">{formatGBP(goods.net)}</td>
                      {mode !== "view" && <td colSpan={mode === "return" ? 2 : 1}></td>}
                    </tr>
                  </tfoot>
                </table>
              </div>
              <div className="px-4 py-2 border-t border-gray-100">
                <button type="button" onClick={exportLines} className="text-xs font-medium text-brand-700 hover:underline">Export</button>
              </div>
            </section>

            {mode !== "view" && (
              <section className="bg-white border-2 border-brand-600 rounded-lg p-4 space-y-3">
                <div className="flex flex-wrap items-end gap-3">
                  <div className="flex-1 min-w-48">
                    <label htmlFor="so-panel-notes" className="block text-xs font-medium text-gray-600 mb-1">
                      {mode === "ship" ? "Shipment notes" : mode === "invoice" ? "Invoice notes" : "Reason"}
                    </label>
                    <input id="so-panel-notes" autoComplete="off" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
                      className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
                  </div>
                  {mode === "ship" && (
                    <>
                      <div>
                        <label htmlFor="so-panel-carrier" className="block text-xs font-medium text-gray-600 mb-1">Carrier</label>
                        <input id="so-panel-carrier" autoComplete="off" value={tracking.company} onChange={e => setTracking({ ...tracking, company: e.target.value })}
                          placeholder="e.g. Royal Mail" className="w-36 border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
                      </div>
                      <div>
                        <label htmlFor="so-panel-tracking" className="block text-xs font-medium text-gray-600 mb-1">Tracking number</label>
                        <input id="so-panel-tracking" autoComplete="off" value={tracking.number} onChange={e => setTracking({ ...tracking, number: e.target.value })}
                          placeholder="Optional" className="w-44 border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
                      </div>
                    </>
                  )}
                  {mode === "return" && (
                    <div>
                      <label htmlFor="sale-orders-return-to-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Return to warehouse</label>
                      <select id="sale-orders-return-to-warehouse" value={returnWarehouseId} onChange={e => setReturnWarehouseId(e.target.value)}
                        className="border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
                        {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
                      </select>
                    </div>
                  )}
                  {mode === "invoice" && order.charges.some(c => !c.invoiced) && (
                    <label className="flex items-center gap-1.5 text-sm text-gray-700 self-center">
                      <input type="checkbox" checked={includeCharges} onChange={e => setIncludeCharges(e.target.checked)} />
                      Include charges ({order.charges.filter(c => !c.invoiced).map(c => c.description).join(", ")})
                    </label>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {mode === "invoice" ? (
                    <>
                      <button type="submit" value="issue" disabled={busy} className={BTN_PRIMARY}>
                        {busy ? "Saving..." : "Create & issue"}
                      </button>
                      <button type="submit" value="draft" disabled={busy} className={BTN_SECONDARY}>Create draft invoice</button>
                    </>
                  ) : (
                    <button type="submit" disabled={busy} className={BTN_PRIMARY}>
                      {busy ? "Saving..." : mode === "ship" ? "Ship & deduct stock" : "Record return"}
                    </button>
                  )}
                  <button type="button" onClick={() => { setMode("view"); setError(""); }}
                    className="text-sm text-gray-500 hover:text-gray-800 px-2 py-1.5">Close</button>
                </div>
                {mode === "ship" && order.source === "SHOPIFY" && (
                  <p className="text-xs text-gray-400">
                    This shipment is also sent to Shopify, which marks the items fulfilled and emails the customer (with the tracking number, if given).
                  </p>
                )}
                {mode === "return" && (
                  <p className="text-xs text-gray-400">
                    Untick &quot;Back to stock&quot; for damaged goods: the return is recorded but stock is not increased. Refunds are handled on the invoice.
                  </p>
                )}
              </section>
            )}
          </form>

          {order.charges.length > 0 && (
            <section className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <h3 className="px-4 py-3 border-b border-gray-200 text-sm font-semibold text-gray-800">Additional charges and services</h3>
              <table data-testid={`so-charges-${orderId}`} className="list-table">
                <ListHead cols={["Additional charges / services", "Quantity", "Price", "Discount", "Tax rule", "Total"]} right={["Quantity", "Price", "Discount", "Total"]} />
                <tbody className="divide-y divide-gray-100">
                  {order.charges.map(c => (
                    <tr key={c.id}>
                      <td className="text-gray-800"><Clip text={c.description} width="22rem" /></td>
                      <td className="num">{qty(c.quantity)}</td>
                      <td className="num">{formatGBP(c.unitPrice)}</td>
                      <td className="num text-gray-500">{c.discountPercent ? `${c.discountPercent}%` : "—"}</td>
                      <td className="text-gray-600">{c.taxName ?? "Not recorded"}{c.taxOverridden && " ✎"}</td>
                      <td className="num">{formatGBP(c.netAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <div className="flex flex-col md:flex-row gap-4 items-start">
            <div className="flex-1 w-full bg-white border border-gray-200 rounded-lg p-4 min-h-24">
              <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Order memo</h3>
              <p className="text-sm text-gray-700 whitespace-pre-line">{order.notes || <span className="text-gray-400">No notes</span>}</p>
            </div>
            {/* Totals like Cin7: order lines, additional charges and total, before tax / tax / total */}
            <table data-testid={`so-totals-${orderId}`} className="bg-white border border-gray-200 rounded-lg text-sm tabular-nums">
              <thead>
                <tr className="text-xs text-gray-500 uppercase tracking-wide">
                  <th className="px-4 pt-3 pb-1"></th>
                  <th className="px-4 pt-3 pb-1 text-right font-semibold">Order lines</th>
                  <th className="px-4 pt-3 pb-1 text-right font-semibold">Charges</th>
                  <th className="px-4 pt-3 pb-1 text-right font-semibold">Total</th>
                </tr>
              </thead>
              <tbody>
                <tr><th className="px-4 py-1 text-left text-xs font-medium text-gray-500">Before tax</th>
                  <td className="px-4 py-1 text-right">{formatGBP(goods.net)}</td><td className="px-4 py-1 text-right">{formatGBP(extras.net)}</td>
                  <td className="px-4 py-1 text-right">{formatGBP(order.subtotal)}</td></tr>
                <tr><th className="px-4 py-1 text-left text-xs font-medium text-gray-500">VAT</th>
                  <td className="px-4 py-1 text-right">{formatGBP(goods.tax)}</td><td className="px-4 py-1 text-right">{formatGBP(extras.tax)}</td>
                  <td className="px-4 py-1 text-right">{formatGBP(order.taxTotal)}</td></tr>
                <tr className="border-t border-gray-200 font-semibold"><th className="px-4 pt-2 pb-3 text-left text-xs">Total</th>
                  <td className="px-4 pt-2 pb-3 text-right">{formatGBP(goods.net + goods.tax)}</td><td className="px-4 pt-2 pb-3 text-right">{formatGBP(extras.net + extras.tax)}</td>
                  <td className="px-4 pt-2 pb-3 text-right">{formatGBP(order.total)}</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}
