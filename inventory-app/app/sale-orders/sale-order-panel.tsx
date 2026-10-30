"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { getSaleOrder, confirmSaleOrder, fulfillSaleOrder, cancelSaleOrder, createSaleReturn, retryShopifyFulfilment,
  convertQuote, rejectQuote, getCustomers } from "@/lib/api";
import { Customer, SaleOrder, SaleOrderLine, Warehouse } from "@/types";
import SaleOrderForm from "./sale-order-form";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";

type Mode = "view" | "ship" | "return" | "edit";

// One key per submission attempt: a retried click/network retry is applied only once by the backend
const newKey = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;

const qty = (n: number) => Number(n).toLocaleString();

export default function SaleOrderPanel({ orderId, warehouses }: { orderId: number; warehouses: Warehouse[] }) {
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
  const busy = confirmMutation.isPending || cancelMutation.isPending || shipMutation.isPending || returnMutation.isPending
    || retryMutation.isPending || convertMutation.isPending || rejectMutation.isPending;
  // Only needed for editing
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers, enabled: mode === "edit" });

  if (isLoading || !order) return <p className="text-gray-400 text-sm px-2 py-3">Loading order...</p>;

  if (mode === "edit") {
    return (
      <div data-testid={`so-panel-${orderId}`} className="bg-gray-50 border-t border-gray-200 px-6 py-5">
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

  const maxFor = (l: SaleOrderLine) =>
    mode === "ship" ? allocatedOf(l) : l.fulfilledQuantity - l.returnedQuantity;

  const startMode = (m: Mode) => {
    const prefill: Record<number, string> = {};
    // Shipping defaults to everything allocated; returns start empty
    order.lines.forEach(l => { prefill[l.id] = m === "ship" && allocatedOf(l) > 0 ? String(allocatedOf(l)) : ""; });
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
    if (chosen.length === 0) { setError("Enter a quantity for at least one line"); return; }
    const over = chosen.find(x => x.quantity > maxFor(x.line));
    if (over) { setError(`${over.line.productSku}: at most ${qty(maxFor(over.line))}`); return; }

    if (mode === "ship") {
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

  return (
    <div data-testid={`so-panel-${orderId}`} className="bg-gray-50 border-t border-gray-200 px-6 py-5 space-y-5">
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

      <dl data-testid={`so-details-${orderId}`} className="grid grid-cols-2 md:grid-cols-6 gap-x-4 gap-y-1 text-xs">
        {order.status === "QUOTE" && (
          <div data-testid={`so-valid-until-${orderId}`}>
            <dt className="text-gray-400">Quote valid until</dt>
            <dd className={order.quoteExpired ? "text-red-600 font-semibold" : "text-gray-800"}>
              {order.quoteValidUntil ?? "—"}{order.quoteExpired && " · Expired"}
            </dd>
          </div>
        )}
        {([["Order", order.orderNumber], ["Reference", order.customerReference], ["Sales rep", order.salesRep], ["Terms", order.paymentTerm],
           ["Ship by", order.shipBy], ["Carrier", order.carrier]] as [string, string | undefined][]).map(([k, v]) => (
          <div key={k}><dt className="text-gray-400">{k}</dt><dd className="text-gray-800">{v || "—"}</dd></div>
        ))}
        {order.shippingNotes && <div className="col-span-2 md:col-span-6"><dt className="text-gray-400">Shipping notes</dt><dd className="text-gray-800">{order.shippingNotes}</dd></div>}
      </dl>

      {/* Actions */}
      {mode === "view" && (
        <div className="flex flex-wrap gap-2">
          {order.status === "QUOTE" && (
            <>
              <button disabled={busy || order.quoteExpired} onClick={() => convertMutation.mutate(order.id)}
                title={order.quoteExpired ? "Expired: edit the quote and give it a new valid-until date first" : undefined}
                className="bg-violet-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-violet-700 disabled:opacity-50">
                Convert to order
              </button>
              <button disabled={busy} onClick={() => { if (confirm("Reject this quote?")) rejectMutation.mutate(order.id); }}
                className="border border-gray-300 text-gray-600 px-3 py-1.5 rounded-md text-xs font-medium hover:bg-gray-100 disabled:opacity-50">
                Reject quote
              </button>
            </>
          )}
          {editable && (
            <button disabled={busy} onClick={() => { setError(""); setMode("edit"); }}
              className="border border-gray-300 text-gray-700 px-3 py-1.5 rounded-md text-xs font-medium hover:bg-gray-100 disabled:opacity-50">
              Edit
            </button>
          )}
          {order.status === "DRAFT" && (
            <button disabled={busy} onClick={() => confirmMutation.mutate(order.id)}
              className="bg-sky-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-sky-700 disabled:opacity-50">
              {order.needsReview ? "Confirm as it is" : "Confirm order"}
            </button>
          )}
          {canShip && (
            <button disabled={busy} onClick={() => startMode("ship")}
              className="bg-emerald-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-emerald-700 disabled:opacity-50">
              Ship items
            </button>
          )}
          {canReturn && (
            <button disabled={busy} onClick={() => startMode("return")}
              className="bg-teal-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-teal-700 disabled:opacity-50">
              Record return
            </button>
          )}
          {canCancel && (
            <button disabled={busy}
              onClick={() => {
                const msg = shipped
                  ? "Cancel the quantities not yet shipped? Shipped items are kept; the order will be completed."
                  : "Cancel this order?";
                if (confirm(msg)) cancelMutation.mutate(order.id);
              }}
              className="border border-red-200 text-red-600 px-3 py-1.5 rounded-md text-xs font-medium hover:bg-red-50 disabled:opacity-50">
              {shipped ? "Cancel remaining" : "Cancel order"}
            </button>
          )}
          {order.status === "DRAFT" && (
            <span className="text-xs text-gray-400 self-center">Confirm the order before shipping.</span>
          )}
          {waitingForStock && (
            <span className="text-xs text-orange-700 self-center">Nothing to ship yet: the stock on hand is promised to orders confirmed earlier.</span>
          )}
        </div>
      )}

      {/* Lines (with ship/return inputs when a form is open) */}
      <form onSubmit={submit} className="space-y-3">
        <table className="w-full text-sm bg-white border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {["Product", "Ordered", "Shipped", "Cancelled", "Returned", "Remaining", "Allocated", "Price", "Disc", "VAT", "Net", "Margin"].map(h => (
                <th key={h} className="text-left px-3 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
              ))}
              {mode === "ship" && <th className="text-left px-3 py-2 text-xs font-semibold text-emerald-700 uppercase">Ship now</th>}
              {mode === "return" && (
                <>
                  <th className="text-left px-3 py-2 text-xs font-semibold text-teal-700 uppercase">Return qty</th>
                  <th className="text-left px-3 py-2 text-xs font-semibold text-teal-700 uppercase">Back to stock</th>
                </>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {order.lines.map(l => {
              const max = maxFor(l);
              return (
                <tr key={l.id}>
                  <td className="px-3 py-2">
                    <span className="font-mono text-xs text-gray-500">{l.productSku}</span>
                    <span className="ml-2 text-gray-700">{l.productName}</span>
                  </td>
                  <td className="px-3 py-2 tabular-nums">{qty(l.quantity)}</td>
                  <td className="px-3 py-2 tabular-nums text-emerald-700">{qty(l.fulfilledQuantity)}</td>
                  <td className="px-3 py-2 tabular-nums text-gray-500">{qty(l.cancelledQuantity)}</td>
                  <td className="px-3 py-2 tabular-nums text-teal-700">{qty(l.returnedQuantity)}</td>
                  <td className="px-3 py-2 tabular-nums font-medium">{qty(l.remainingQuantity)}</td>
                  <td className={`px-3 py-2 tabular-nums ${(l.backorderedQuantity ?? 0) > 0 ? "text-orange-700 font-semibold" : "text-gray-500"}`}
                    title={(l.backorderedQuantity ?? 0) > 0 ? `${qty(l.backorderedQuantity!)} short` : undefined}>
                    {l.allocatedQuantity != null ? qty(l.allocatedQuantity) : "—"}
                  </td>
                  <td className="px-3 py-2 tabular-nums">{l.unitPrice != null ? formatGBP(l.unitPrice) : "—"}</td>
                  <td className="px-3 py-2 tabular-nums text-gray-500">{l.discountPercent ? `${l.discountPercent}%` : "—"}</td>
                  <td className="px-3 py-2 text-xs text-gray-600" title={l.taxOverridden ? "Changed by hand" : undefined}>
                    {l.taxName ?? "Not recorded"}{l.taxOverridden && " ✎"}
                  </td>
                  <td className="px-3 py-2 tabular-nums">{formatGBP(l.netAmount)}</td>
                  <td className="px-3 py-2 tabular-nums text-gray-500">{l.marginPercent != null ? `${l.marginPercent}%` : "—"}</td>
                  {mode !== "view" && (
                    <td className="px-3 py-2">
                      <input type="number" min="0" max={max} step="any" disabled={max <= 0}
                        aria-label={`${mode === "ship" ? "Ship" : "Return"} quantity for ${l.productSku}`}
                        value={amounts[l.id] ?? ""} placeholder={max > 0 ? `max ${qty(max)}` : "—"}
                        onChange={e => setAmounts({ ...amounts, [l.id]: e.target.value })}
                        className="w-24 border border-gray-300 rounded-md px-2 py-1 text-sm disabled:bg-gray-100 focus:outline-none focus:ring-1 focus:ring-emerald-400" />
                    </td>
                  )}
                  {mode === "return" && (
                    <td className="px-3 py-2">
                      <input type="checkbox" disabled={max <= 0} checked={restock[l.id] ?? true}
                        aria-label={`Back to stock for ${l.productSku}`}
                        onChange={e => setRestock({ ...restock, [l.id]: e.target.checked })} />
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>

        {order.charges.length > 0 && (
          <table data-testid={`so-charges-${orderId}`} className="w-full text-sm bg-white border border-gray-200 rounded-lg overflow-hidden">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>{["Additional charge", "Qty", "Price", "Disc", "VAT", "Net"].map(h => (
                <th key={h} className="text-left px-3 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>))}</tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {order.charges.map(c => (
                <tr key={c.id}>
                  <td className="px-3 py-2 text-gray-700">{c.description}</td>
                  <td className="px-3 py-2 tabular-nums">{qty(c.quantity)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatGBP(c.unitPrice)}</td>
                  <td className="px-3 py-2 tabular-nums text-gray-500">{c.discountPercent ? `${c.discountPercent}%` : "—"}</td>
                  <td className="px-3 py-2 text-xs text-gray-600">{c.taxName ?? "Not recorded"}{c.taxOverridden && " ✎"}</td>
                  <td className="px-3 py-2 tabular-nums">{formatGBP(c.netAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <dl data-testid={`so-totals-${orderId}`} className="ml-auto w-fit text-sm grid grid-cols-2 gap-x-6 tabular-nums">
          <dt className="text-gray-500">Before tax</dt><dd className="text-right">{formatGBP(order.subtotal)}</dd>
          <dt className="text-gray-500">VAT</dt><dd className="text-right">{formatGBP(order.taxTotal)}</dd>
          <dt className="font-semibold">Total</dt><dd className="text-right font-semibold">{formatGBP(order.total)}</dd>
        </dl>

        {mode !== "view" && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex-1 min-w-48">
              <label htmlFor="so-panel-notes" className="block text-xs font-medium text-gray-600 mb-1">{mode === "ship" ? "Shipment notes" : "Reason"}</label>
              <input id="so-panel-notes" autoComplete="off" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
                className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
            </div>
            {mode === "ship" && (
              <>
                <div>
                  <label htmlFor="so-panel-carrier" className="block text-xs font-medium text-gray-600 mb-1">Carrier</label>
                  <input id="so-panel-carrier" autoComplete="off" value={tracking.company} onChange={e => setTracking({ ...tracking, company: e.target.value })}
                    placeholder="e.g. Royal Mail" className="w-36 border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
                </div>
                <div>
                  <label htmlFor="so-panel-tracking" className="block text-xs font-medium text-gray-600 mb-1">Tracking number</label>
                  <input id="so-panel-tracking" autoComplete="off" value={tracking.number} onChange={e => setTracking({ ...tracking, number: e.target.value })}
                    placeholder="Optional" className="w-44 border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
                </div>
              </>
            )}
            {mode === "return" && (
              <div>
                <label htmlFor="sale-orders-return-to-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Return to warehouse</label>
                <select id="sale-orders-return-to-warehouse" value={returnWarehouseId} onChange={e => setReturnWarehouseId(e.target.value)}
                  className="border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400">
                  {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
              </div>
            )}
            <button type="submit" disabled={busy}
              className={`text-white px-4 py-1.5 rounded-md text-sm font-medium disabled:opacity-50 ${mode === "ship" ? "bg-emerald-600 hover:bg-emerald-700" : "bg-teal-600 hover:bg-teal-700"}`}>
              {busy ? "Saving..." : mode === "ship" ? "Ship & deduct stock" : "Record return"}
            </button>
            <button type="button" onClick={() => { setMode("view"); setError(""); }}
              className="text-sm text-gray-500 hover:text-gray-700 px-2 py-1.5">Close</button>
          </div>
        )}
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
      </form>

      {/* History */}
      {((order.fulfilments?.length ?? 0) > 0 || (order.returns?.length ?? 0) > 0) && (
        <div>
          <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">History</h3>
          <ul className="space-y-1.5 text-sm">
            {order.fulfilments?.map(f => (
              <li key={`f-${f.id}`} className="flex flex-wrap gap-x-2">
                <span className="text-xs font-semibold px-2 py-0.5 rounded bg-emerald-100 text-emerald-700">Shipment #{f.id}</span>
                <span className="text-gray-700">{f.lines.map(l => `${qty(l.quantity)} × ${l.productSku}`).join(", ")}</span>
                <span className="text-gray-400 text-xs self-center">
                  <ClientDate iso={f.createdAt} showTime /> · {f.createdBy}{f.notes ? ` · ${f.notes}` : ""}
                  {f.trackingNumber && ` · ${f.trackingCompany ? `${f.trackingCompany} ` : ""}${f.trackingNumber}`}
                </span>
                {f.syncStatus && (
                  <span data-testid={`shipment-sync-${f.id}`} className="text-xs self-center">
                    {f.syncStatus === "SYNCED" && <span className="text-emerald-700">✓ Sent to Shopify{f.syncMessage ? ` (${f.syncMessage})` : ""}</span>}
                    {f.syncStatus === "PENDING" && <span className="text-gray-500">Sending to Shopify…</span>}
                    {f.syncStatus === "FAILED" && (
                      <>
                        <span className="text-red-600">✕ Not sent to Shopify: {f.syncMessage}</span>
                        <button type="button" disabled={busy} onClick={() => retryMutation.mutate({ id: order.id, fulfilmentId: f.id })}
                          className="ml-2 text-xs font-medium text-rose-600 hover:text-rose-800 disabled:opacity-50">
                          {retryMutation.isPending ? "Retrying..." : "Retry"}
                        </button>
                      </>
                    )}
                  </span>
                )}
              </li>
            ))}
            {order.returns?.map(r => (
              <li key={`r-${r.id}`} className="flex flex-wrap gap-x-2">
                <span className="text-xs font-semibold px-2 py-0.5 rounded bg-teal-100 text-teal-700">Return #{r.id}</span>
                <span className="text-gray-700">
                  {r.lines.map(l => `${qty(l.quantity)} × ${l.productSku}${l.restock ? "" : " (not restocked)"}`).join(", ")}
                  {" → "}{r.warehouseName}
                </span>
                <span className="text-gray-400 text-xs self-center">
                  <ClientDate iso={r.createdAt} showTime /> · {r.createdBy}{r.reason ? ` · ${r.reason}` : ""}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
