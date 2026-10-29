"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { getSaleOrder, confirmSaleOrder, fulfillSaleOrder, cancelSaleOrder, createSaleReturn } from "@/lib/api";
import { SaleOrder, SaleOrderLine, Warehouse } from "@/types";
import { ClientDate } from "@/components/ClientDate";

type Mode = "view" | "ship" | "return";

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
  });

  const [mode, setMode] = useState<Mode>("view");
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [restock, setRestock] = useState<Record<number, boolean>>({});
  const [notes, setNotes] = useState("");
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
  const busy = confirmMutation.isPending || cancelMutation.isPending || shipMutation.isPending || returnMutation.isPending;

  if (isLoading || !order) return <p className="text-gray-400 text-sm px-2 py-3">Loading order...</p>;

  const shipped    = order.lines.some(l => l.fulfilledQuantity > 0);
  const returnable = order.lines.some(l => l.fulfilledQuantity - l.returnedQuantity > 0);
  const canShip    = order.status === "CONFIRMED" && order.lines.some(l => l.remainingQuantity > 0);
  const canCancel  = order.status === "DRAFT" || order.status === "CONFIRMED";
  const canReturn  = (order.status === "CONFIRMED" || order.status === "COMPLETED") && returnable;

  const maxFor = (l: SaleOrderLine) =>
    mode === "ship" ? l.remainingQuantity : l.fulfilledQuantity - l.returnedQuantity;

  const startMode = (m: Mode) => {
    const prefill: Record<number, string> = {};
    // Shipping defaults to everything remaining; returns start empty
    order.lines.forEach(l => { prefill[l.id] = m === "ship" && l.remainingQuantity > 0 ? String(l.remainingQuantity) : ""; });
    setAmounts(prefill);
    setRestock(Object.fromEntries(order.lines.map(l => [l.id, true])));
    setNotes("");
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

      {/* Actions */}
      {mode === "view" && (
        <div className="flex flex-wrap gap-2">
          {order.status === "DRAFT" && (
            <button disabled={busy} onClick={() => confirmMutation.mutate(order.id)}
              className="bg-sky-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-sky-700 disabled:opacity-50">
              Confirm order
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
        </div>
      )}

      {/* Lines (with ship/return inputs when a form is open) */}
      <form onSubmit={submit} className="space-y-3">
        <table className="w-full text-sm bg-white border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {["Product", "Ordered", "Shipped", "Cancelled", "Returned", "Remaining"].map(h => (
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

        {mode !== "view" && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex-1 min-w-48">
              <label htmlFor="so-panel-notes" className="block text-xs font-medium text-gray-600 mb-1">{mode === "ship" ? "Shipment notes" : "Reason"}</label>
              <input id="so-panel-notes" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
                className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
            </div>
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
                </span>
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
