"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrder, receivePurchaseLines, cancelPurchaseOrder, orderPurchaseOrder, closeShortPurchaseOrder, getWarehouses } from "@/lib/api";
import { PurchaseOrder, PurchaseOrderStatus, Warehouse } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field,
  BTN_PRIMARY, BTN_SECONDARY, BTN_DANGER, TH, TD,
} from "@/components/DetailParts";
import PurchaseOrderForm from "./purchase-order-form";
import BillForm from "./bill-form";

export const PO_STATUS_COLOR: Record<PurchaseOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  ORDERED:   "bg-sky-100 text-sky-700",
  RECEIVED:  "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const qty = (n: number) => Number(n).toLocaleString();

export const RECEIVING_LABEL: Record<PurchaseOrder["receivingStatus"], { label: string; color: string }> = {
  NOT_RECEIVED:       { label: "Not received", color: "text-gray-400" },
  PARTIALLY_RECEIVED: { label: "Partially received", color: "text-amber-600" },
  RECEIVED:           { label: "Received", color: "text-emerald-600" },
};

export const BILLING_LABEL: Record<PurchaseOrder["billingStatus"], { label: string; color: string }> = {
  NOT_BILLED:       { label: "Not billed", color: "text-gray-400" },
  PARTIALLY_BILLED: { label: "Partially billed", color: "text-amber-600" },
  BILLED:           { label: "Fully billed", color: "text-emerald-600" },
};

const BILL_STATUS: Record<string, string> = {
  DRAFT: "bg-amber-100 text-amber-700", ISSUED: "bg-sky-100 text-sky-700", PAID: "bg-emerald-100 text-emerald-700", CANCELLED: "bg-gray-100 text-gray-400",
};

// One key per receipt attempt: a retried click / network retry is applied only once
const newKey = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;

// One purchase order on its own page, like Cin7's purchase screen
export default function PurchaseOrderView({ orderId }: { orderId: number }) {
  const qc = useQueryClient();
  const { data: po, isLoading } = useQuery<PurchaseOrder>({ queryKey: ["purchase-order", orderId], queryFn: () => getPurchaseOrder(orderId) });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);
  // Receiving: quantities per line, date, warehouse, notes
  const [receiving, setReceiving] = useState(false);
  const [billing, setBilling] = useState(false);
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [receipt, setReceipt] = useState({ receivedOn: "", warehouseId: "", notes: "", key: "" });

  const onDone = (updated: PurchaseOrder) => {
    qc.setQueryData(["purchase-order", orderId], updated);
    qc.invalidateQueries({ queryKey: ["purchase-orders"] });
    qc.invalidateQueries({ queryKey: ["stock-overview"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["products-page"] });
    setError("");
    setEditing(false);
    setReceiving(false);
    setBilling(false);
  };
  const onFail = (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Request failed");
  const orderMut = useMutation({ mutationFn: orderPurchaseOrder, onSuccess: onDone, onError: onFail });
  const receiveMut = useMutation({ mutationFn: receivePurchaseLines, onSuccess: onDone, onError: onFail });
  const cancelMut = useMutation({ mutationFn: cancelPurchaseOrder, onSuccess: onDone, onError: onFail });
  const shortMut = useMutation({ mutationFn: closeShortPurchaseOrder, onSuccess: onDone, onError: onFail });
  const busy = orderMut.isPending || receiveMut.isPending || cancelMut.isPending || shortMut.isPending;

  if (isLoading || !po) return <p className="text-gray-400 text-sm">Loading…</p>;

  if (editing) {
    return (
      <div data-testid={`po-page-${po.id}`}>
        <BackLink href="/purchase-orders" />
        <PurchaseOrderForm editing={po} warehouses={warehouses} onSaved={onDone} onCancel={() => setEditing(false)} />
      </div>
    );
  }

  const units = po.lines.reduce((t, l) => t + Number(l.quantity ?? 0), 0);
  const open = po.status === "DRAFT" || po.status === "ORDERED";
  const anyReceived = po.lines.some(l => Number(l.receivedQuantity) > 0);
  const receiving_ = RECEIVING_LABEL[po.receivingStatus];
  const billing_ = BILLING_LABEL[po.billingStatus];
  // Something can still be billed: received and not billed, or still expected
  const canBill = (po.status === "ORDERED" || po.status === "RECEIVED") && po.lines.some(l =>
    Math.max(Number(l.quantity) - Number(l.closedShortQuantity), Number(l.receivedQuantity)) - Number(l.billedQuantity) > 0);
  const busyMode = receiving || billing;

  const startReceiving = () => {
    setAmounts(Object.fromEntries(po.lines.map(l => [l.id, Number(l.outstandingQuantity) > 0 ? String(l.outstandingQuantity) : ""])));
    setReceipt({ receivedOn: new Date().toISOString().slice(0, 10), warehouseId: String(po.warehouseId), notes: "", key: newKey() });
    setError("");
    setReceiving(true);
  };
  const submitReceipt = (e: React.FormEvent) => {
    e.preventDefault();
    const lines = po.lines.map(l => ({ purchaseOrderLineId: l.id, quantity: Number(amounts[l.id] || 0) })).filter(x => x.quantity > 0);
    if (lines.length === 0) { setError("Enter what arrived for at least one line"); return; }
    receiveMut.mutate({ id: po.id, data: {
      idempotencyKey: receipt.key, receivedOn: receipt.receivedOn || undefined,
      warehouseId: receipt.warehouseId ? Number(receipt.warehouseId) : undefined, notes: receipt.notes || undefined, lines,
    }});
  };

  return (
    <div data-testid={`po-page-${po.id}`}>
      <BackLink href="/purchase-orders" />
      <RecordHeader
        title={`Purchase ${po.orderNumber}`}
        subtitle={`from ${po.supplierName}`}
        badges={
          <div data-testid={`po-status-${po.id}`} className="flex items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${PO_STATUS_COLOR[po.status]}`}>{po.status}</span>
            {po.status !== "DRAFT" && po.status !== "CANCELLED" && <span className={`text-xs font-medium ${receiving_.color}`}>{receiving_.label}</span>}
            {po.overDelivered && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-orange-100 text-orange-800">Over-delivered</span>}
            {po.closedShort && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-gray-100 text-gray-600">Closed short</span>}
            {po.status !== "DRAFT" && po.status !== "CANCELLED" && <span className={`text-xs font-medium ${billing_.color}`}>· {billing_.label}</span>}
            {po.paymentStatus && <span className={`text-xs font-medium ${po.paymentStatus === "PAID" ? "text-emerald-600" : "text-gray-500"}`}>
              · {po.paymentStatus === "PAID" ? "Paid" : po.paymentStatus === "PARTIALLY_PAID" ? "Partially paid" : "Unpaid"}</span>}
          </div>
        }
        actions={!busyMode && (open || canBill) && <>
          {canBill && <button disabled={busy} onClick={() => { setError(""); setBilling(true); }} className={BTN_SECONDARY}>Enter bill</button>}
          {open && <>
          {anyReceived ? (
            <button disabled={busy} onClick={() => { if (confirm("Nothing more is coming? What is still outstanding is closed short.")) shortMut.mutate(po.id); }}
              className={BTN_SECONDARY}>Close short</button>
          ) : (
            <button disabled={busy} onClick={() => { if (confirm(`Cancel ${po.orderNumber}?`)) cancelMut.mutate(po.id); }} className={BTN_DANGER}>Cancel PO</button>
          )}
          {po.status === "DRAFT" && <button disabled={busy} onClick={() => setEditing(true)} className={BTN_SECONDARY}>Edit</button>}
          {po.status === "DRAFT" && <button disabled={busy} onClick={() => orderMut.mutate(po.id)} className={BTN_SECONDARY}>Mark as ordered</button>}
          <button disabled={busy} onClick={startReceiving} className={BTN_PRIMARY}>Receive stock</button>
          </>}
        </>}
      />

      <RecordLayout trail={<>
        <TrailCard title="Progress">
          <div className="flex justify-between text-xs"><span className="text-gray-500">Created</span><ClientDate iso={po.createdAt} /></div>
          <div className="flex justify-between text-xs">
            <span className="text-gray-500">Ordered</span>
            {po.orderedAt ? <ClientDate iso={po.orderedAt} /> : <span className="text-gray-400">Draft</span>}
          </div>
          <div className="flex justify-between text-xs">
            <span className="text-gray-500">Received</span>
            {po.receivedAt ? <span className="text-emerald-700"><ClientDate iso={po.receivedAt} /></span>
              : <span className="text-gray-400">{po.status === "CANCELLED" ? "Cancelled" : "Not yet"}</span>}
          </div>
        </TrailCard>
        <TrailCard title="Receipts" testId={`po-receipts-${po.id}`}
          badge={po.status !== "DRAFT" ? <span className={`text-[11px] font-semibold ${receiving_.color}`}>{receiving_.label}</span> : undefined}>
          {(po.receipts?.length ?? 0) === 0 && (
            po.receivedAt
              // Received before receipts were recorded one by one
              ? <p className="text-xs text-gray-600">Received in full on <ClientDate iso={po.receivedAt} /></p>
              : <p className="text-xs text-gray-400">Nothing received yet</p>
          )}
          {po.receipts?.map((r, i) => (
            <div key={r.id} className="border-t border-gray-100 first:border-0 pt-2 first:pt-0">
              <p className="font-medium text-gray-800">Receipt #{i + 1} · {new Date(r.receivedOn).toLocaleDateString("en-GB")}</p>
              <p className="text-xs text-gray-600">
                {r.lines.map(l => `${qty(l.quantity)} × ${l.productSku}${Number(l.overQuantity) > 0 ? ` (${qty(l.overQuantity)} over)` : ""}`).join(", ")}
              </p>
              <p className="text-[11px] text-gray-400">{r.warehouseName} · {r.createdBy}{r.notes ? ` · ${r.notes}` : ""}</p>
            </div>
          ))}
          {open && !busyMode && (
            <button type="button" disabled={busy} onClick={startReceiving}
              className="w-full mt-1 border border-dashed border-gray-300 rounded-md py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50">
              + Receipt
            </button>
          )}
        </TrailCard>

        <TrailCard title="Bills" testId={`po-bills-${po.id}`}
          badge={po.status !== "DRAFT" && po.status !== "CANCELLED" ? <span className={`text-[11px] font-semibold ${billing_.color}`}>{billing_.label}</span> : undefined}>
          {(po.bills?.length ?? 0) === 0 && <p className="text-xs text-gray-400">No supplier bill yet</p>}
          {po.bills?.map(b => (
            <div key={b.id} className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
              <Link href={`/invoices/${b.id}`} className="font-mono text-xs font-medium text-brand-700 hover:underline">{b.invoiceNumber}</Link>
              <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${BILL_STATUS[b.status]}`}>{b.status}</span>
              <span className="ml-auto tabular-nums text-gray-800">{formatGBP(b.total)}</span>
              <span className="w-full text-[11px] text-gray-400">
                {b.dueDate && `due ${new Date(b.dueDate).toLocaleDateString("en-GB")}`}{Number(b.amountPaid) > 0 && ` · paid ${formatGBP(b.amountPaid)}`}
              </span>
              {b.matchIssueCount > 0 && b.status !== "CANCELLED" && (
                <span className="w-full text-[11px] font-medium text-amber-700">⚠ {b.matchIssueCount} line{b.matchIssueCount > 1 ? "s" : ""} don&apos;t match the PO</span>
              )}
            </div>
          ))}
          {canBill && !busyMode && (
            <button type="button" disabled={busy} onClick={() => { setError(""); setBilling(true); }}
              className="w-full mt-1 border border-dashed border-gray-300 rounded-md py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50">
              + Bill
            </button>
          )}
        </TrailCard>

        <TrailCard title="Supplier">
          <p className="text-gray-800">{po.supplierName}</p>
          <Link href={`/purchase-orders?q=${encodeURIComponent(po.supplierName)}`} className="block text-xs text-brand-700 hover:underline">
            Other orders from this supplier ›
          </Link>
        </TrailCard>
      </>}>
        {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

        {billing && (
          <BillForm po={po} onCancel={() => setBilling(false)}
            onSaved={() => {
              setBilling(false);
              qc.invalidateQueries({ queryKey: ["purchase-order", orderId] });
              qc.invalidateQueries({ queryKey: ["purchase-orders"] });
              qc.invalidateQueries({ queryKey: ["invoices"] });
            }} />
        )}

        <DetailColumns testId={`po-details-${po.id}`} columns={[
          { title: "Supplier details", fields: <>
            <Field label="Supplier" value={po.supplierName} />
            <Field label="Supplier reference" value={po.supplierReference} />
            <Field label="Order" value={po.orderNumber} />
          </> },
          { title: "Accounting details", fields: <>
            <Field label="Terms" value={po.paymentTerm} />
            <Field label="Total" value={formatGBP(po.total)} />
          </> },
          { title: "Delivery", fields: <>
            <Field label="Into warehouse" value={po.warehouseName} />
            <Field label="Required by" value={po.requiredBy} />
            <Field label="Units" value={qty(units)} />
          </> },
        ]} />

        <form onSubmit={submitReceipt}>
        <Card title="Products" flush
          right={receiving ? <span className="text-xs font-semibold text-gray-900">Receiving: enter what arrived</span> : undefined}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{["Product", "Supplier SKU", "Unit", "Ordered", "Received", "Outstanding", "Billed", "Cost", "Disc", "VAT", "Net"].map(h => <th key={h} className={TH}>{h}</th>)}
                  {receiving && <th className="text-left px-3 py-2 text-xs font-semibold text-gray-900 uppercase">Received now</th>}</tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {po.lines.map(l => (
                  <tr key={l.id}>
                    <td className={TD}>
                      <span className="font-mono text-xs text-gray-500">{l.productSku}</span>
                      <span className="block text-gray-800">{l.productName}</span>
                    </td>
                    <td className={`${TD} font-mono text-xs text-gray-600`}>{l.supplierSku ?? "—"}</td>
                    <td className={`${TD} text-gray-600`}>{l.unit ?? "—"}</td>
                    <td className={`${TD} tabular-nums`}>{qty(l.quantity)}</td>
                    <td className={`${TD} tabular-nums ${Number(l.overQuantity) > 0 ? "text-orange-700 font-semibold" : "text-emerald-700"}`}
                      title={Number(l.overQuantity) > 0 ? `${qty(l.overQuantity)} more than ordered` : undefined}>
                      {qty(l.receivedQuantity)}{Number(l.overQuantity) > 0 && " ▲"}
                    </td>
                    <td className={`${TD} tabular-nums font-medium`}>
                      {qty(l.outstandingQuantity)}
                      {Number(l.closedShortQuantity) > 0 && <span className="block text-[11px] font-normal text-gray-500">{qty(l.closedShortQuantity)} closed short</span>}
                    </td>
                    <td className={`${TD} tabular-nums text-gray-600`}>{qty(l.billedQuantity)}</td>
                    <td className={`${TD} tabular-nums`}>{l.unitCost != null ? formatGBP(l.unitCost) : "—"}</td>
                    <td className={`${TD} tabular-nums text-gray-500`}>{Number(l.discountPercent) ? `${l.discountPercent}%` : "—"}</td>
                    <td className={`${TD} text-xs text-gray-600`}>{l.taxName ?? "Not recorded"}{l.taxOverridden && " ✎"}</td>
                    <td className={`${TD} tabular-nums`}>{formatGBP(l.netAmount)}</td>
                    {receiving && (() => {
                      const typed = Number(amounts[l.id] || 0);
                      const over = typed - Number(l.outstandingQuantity);
                      return (
                        <td className={TD}>
                          <input type="number" min="0" step="any" aria-label={`Received quantity for ${l.productSku}`}
                            value={amounts[l.id] ?? ""} placeholder="0" onChange={e => setAmounts({ ...amounts, [l.id]: e.target.value })}
                            className="w-24 border border-gray-300 rounded-md px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
                          {typed > 0 && over > 0 && <span className="block text-[11px] text-orange-700">{qty(over)} more than outstanding</span>}
                        </td>
                      );
                    })()}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        {receiving && (
          <section className="mt-4 bg-white border-2 border-brand-600 rounded-lg p-4 space-y-3">
            <div className="flex flex-wrap items-end gap-3">
              <div>
                <label htmlFor="po-receipt-date" className="block text-xs font-medium text-gray-600 mb-1">Date received</label>
                <input id="po-receipt-date" type="date" value={receipt.receivedOn} onChange={e => setReceipt({ ...receipt, receivedOn: e.target.value })}
                  className="border border-gray-300 rounded-md px-3 py-1.5 text-sm" />
              </div>
              <div>
                <label htmlFor="po-receipt-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Into warehouse</label>
                <select id="po-receipt-warehouse" value={receipt.warehouseId} onChange={e => setReceipt({ ...receipt, warehouseId: e.target.value })}
                  className="border border-gray-300 rounded-md px-3 py-1.5 text-sm">
                  {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
              </div>
              <div className="flex-1 min-w-48">
                <label htmlFor="po-receipt-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
                <input id="po-receipt-notes" value={receipt.notes} onChange={e => setReceipt({ ...receipt, notes: e.target.value })} placeholder="e.g. delivery note number"
                  className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm" />
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <button type="submit" disabled={busy} className={BTN_PRIMARY}>{receiveMut.isPending ? "Saving..." : "Receive & add stock"}</button>
              <button type="button" onClick={() => { setReceiving(false); setError(""); }} className="text-sm text-gray-500 hover:text-gray-800 px-2 py-1.5">Close</button>
              <span className="text-xs text-gray-400">More than outstanding is accepted and flagged as an over-delivery.</span>
            </div>
          </section>
        )}
        </form>

        {po.costs.length > 0 && (
          <Card title="Additional costs" flush right={<span className="text-xs text-gray-400">expense, not stock cost</span>}>
            <table data-testid={`po-costs-${po.id}`} className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{["Description", "Reference", "Qty", "Price", "VAT", "Net"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {po.costs.map(c => (
                  <tr key={c.id}>
                    <td className={TD}>{c.description}</td>
                    <td className={`${TD} text-gray-600`}>{c.reference ?? "—"}</td>
                    <td className={`${TD} tabular-nums`}>{qty(c.quantity)}</td>
                    <td className={`${TD} tabular-nums`}>{formatGBP(c.unitPrice)}</td>
                    <td className={`${TD} text-xs text-gray-600`}>{c.taxName ?? "—"}</td>
                    <td className={`${TD} tabular-nums`}>{formatGBP(c.netAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )}

        <div className="flex flex-col md:flex-row gap-4 items-start">
          <div className="flex-1 w-full bg-white border border-gray-200 rounded-lg p-4 min-h-24">
            <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Purchase order memo</h3>
            <p className="text-sm text-gray-700 whitespace-pre-line">{po.notes || <span className="text-gray-400">No notes</span>}</p>
          </div>
          {/* Totals like Cin7: order lines, additional costs and total, before tax / tax / total */}
          <table data-testid={`po-totals-${po.id}`} className="bg-white border border-gray-200 rounded-lg text-sm tabular-nums">
            <thead>
              <tr className="text-xs text-gray-500 uppercase tracking-wide">
                <th className="px-4 pt-3 pb-1"></th>
                <th className="px-4 pt-3 pb-1 text-right font-semibold">Order lines</th>
                <th className="px-4 pt-3 pb-1 text-right font-semibold">Additional costs</th>
                <th className="px-4 pt-3 pb-1 text-right font-semibold">Total</th>
              </tr>
            </thead>
            <tbody>
              <tr><th className="px-4 py-1 text-left text-xs font-medium text-gray-500">Before tax</th>
                <td className="px-4 py-1 text-right">{formatGBP(po.linesNet)}</td><td className="px-4 py-1 text-right">{formatGBP(po.costsNet)}</td>
                <td className="px-4 py-1 text-right">{formatGBP(po.subtotal)}</td></tr>
              <tr><th className="px-4 py-1 text-left text-xs font-medium text-gray-500">VAT</th>
                <td className="px-4 py-1 text-right">{formatGBP(po.linesTax)}</td><td className="px-4 py-1 text-right">{formatGBP(po.costsTax)}</td>
                <td className="px-4 py-1 text-right">{formatGBP(po.taxTotal)}</td></tr>
              <tr className="border-t border-gray-200 font-semibold"><th className="px-4 pt-2 pb-3 text-left text-xs">Total</th>
                <td className="px-4 pt-2 pb-3 text-right">{formatGBP(Number(po.linesNet) + Number(po.linesTax))}</td>
                <td className="px-4 pt-2 pb-3 text-right">{formatGBP(Number(po.costsNet) + Number(po.costsTax))}</td>
                <td className="px-4 pt-2 pb-3 text-right">{formatGBP(po.total)}</td></tr>
            </tbody>
          </table>
        </div>
      </RecordLayout>
    </div>
  );
}
