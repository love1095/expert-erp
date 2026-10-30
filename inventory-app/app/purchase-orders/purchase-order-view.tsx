"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrder, receivePurchaseOrder, cancelPurchaseOrder, orderPurchaseOrder, getWarehouses } from "@/lib/api";
import { PurchaseOrder, PurchaseOrderStatus, Warehouse } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field,
  BTN_PRIMARY, BTN_SECONDARY, BTN_DANGER, TH, TD,
} from "@/components/DetailParts";
import PurchaseOrderForm from "./purchase-order-form";

export const PO_STATUS_COLOR: Record<PurchaseOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  ORDERED:   "bg-sky-100 text-sky-700",
  RECEIVED:  "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const qty = (n: number) => Number(n).toLocaleString();

// One purchase order on its own page, like Cin7's purchase screen
export default function PurchaseOrderView({ orderId }: { orderId: number }) {
  const qc = useQueryClient();
  const { data: po, isLoading } = useQuery<PurchaseOrder>({ queryKey: ["purchase-order", orderId], queryFn: () => getPurchaseOrder(orderId) });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);

  const onDone = (updated: PurchaseOrder) => {
    qc.setQueryData(["purchase-order", orderId], updated);
    qc.invalidateQueries({ queryKey: ["purchase-orders"] });
    qc.invalidateQueries({ queryKey: ["stock-overview"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["products-page"] });
    setError("");
    setEditing(false);
  };
  const onFail = (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Request failed");
  const orderMut = useMutation({ mutationFn: orderPurchaseOrder, onSuccess: onDone, onError: onFail });
  const receiveMut = useMutation({ mutationFn: receivePurchaseOrder, onSuccess: onDone, onError: onFail });
  const cancelMut = useMutation({ mutationFn: cancelPurchaseOrder, onSuccess: onDone, onError: onFail });
  const busy = orderMut.isPending || receiveMut.isPending || cancelMut.isPending;

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

  return (
    <div data-testid={`po-page-${po.id}`}>
      <BackLink href="/purchase-orders" />
      <RecordHeader
        title={`Purchase ${po.orderNumber}`}
        subtitle={`from ${po.supplierName}`}
        badges={
          <div data-testid={`po-status-${po.id}`} className="flex items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${PO_STATUS_COLOR[po.status]}`}>{po.status}</span>
            {po.status === "ORDERED" && <span className="text-xs text-gray-500">Not received</span>}
          </div>
        }
        actions={open && <>
          <button disabled={busy} onClick={() => { if (confirm(`Cancel ${po.orderNumber}?`)) cancelMut.mutate(po.id); }} className={BTN_DANGER}>Cancel PO</button>
          {po.status === "DRAFT" && <button disabled={busy} onClick={() => setEditing(true)} className={BTN_SECONDARY}>Edit</button>}
          {po.status === "DRAFT" && <button disabled={busy} onClick={() => orderMut.mutate(po.id)} className={BTN_SECONDARY}>Mark as ordered</button>}
          <button disabled={busy} onClick={() => { if (confirm("Receive everything on this order? This adds the stock.")) receiveMut.mutate(po.id); }} className={BTN_PRIMARY}>
            Receive stock
          </button>
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
        <TrailCard title="Supplier">
          <p className="text-gray-800">{po.supplierName}</p>
          <Link href={`/purchase-orders?q=${encodeURIComponent(po.supplierName)}`} className="block text-xs text-brand-700 hover:underline">
            Other orders from this supplier ›
          </Link>
        </TrailCard>
      </>}>
        {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

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

        <Card title="Products" flush>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{["Product", "Supplier SKU", "Unit", "Quantity", "Cost", "Disc", "VAT", "Net"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
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
                    <td className={`${TD} tabular-nums`}>{l.unitCost != null ? formatGBP(l.unitCost) : "—"}</td>
                    <td className={`${TD} tabular-nums text-gray-500`}>{Number(l.discountPercent) ? `${l.discountPercent}%` : "—"}</td>
                    <td className={`${TD} text-xs text-gray-600`}>{l.taxName ?? "Not recorded"}{l.taxOverridden && " ✎"}</td>
                    <td className={`${TD} tabular-nums`}>{formatGBP(l.netAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

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
