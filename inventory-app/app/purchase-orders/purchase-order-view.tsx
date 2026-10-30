"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrder, receivePurchaseOrder, cancelPurchaseOrder } from "@/lib/api";
import { PurchaseOrder, PurchaseOrderStatus } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field,
  BTN_PRIMARY, BTN_DANGER, TH, TD,
} from "@/components/DetailParts";

export const PO_STATUS_COLOR: Record<PurchaseOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  RECEIVED:  "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

export const poTotal = (o: PurchaseOrder) => o.lines.reduce((sum, l) => sum + (l.quantity ?? 0) * (l.unitCost ?? 0), 0);

// One purchase order on its own page, like Cin7's purchase screen
export default function PurchaseOrderView({ orderId }: { orderId: number }) {
  const qc = useQueryClient();
  const { data: po, isLoading } = useQuery<PurchaseOrder>({ queryKey: ["purchase-order", orderId], queryFn: () => getPurchaseOrder(orderId) });
  const [error, setError] = useState("");

  const onDone = (updated: PurchaseOrder) => {
    qc.setQueryData(["purchase-order", orderId], updated);
    qc.invalidateQueries({ queryKey: ["purchase-orders"] });
    qc.invalidateQueries({ queryKey: ["stock-overview"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["products-page"] });
    setError("");
  };
  const onFail = (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Request failed");
  const receiveMut = useMutation({ mutationFn: receivePurchaseOrder, onSuccess: onDone, onError: onFail });
  const cancelMut = useMutation({ mutationFn: cancelPurchaseOrder, onSuccess: onDone, onError: onFail });
  const busy = receiveMut.isPending || cancelMut.isPending;

  if (isLoading || !po) return <p className="text-gray-400 text-sm">Loading…</p>;
  const units = po.lines.reduce((t, l) => t + Number(l.quantity ?? 0), 0);

  return (
    <div data-testid={`po-page-${po.id}`}>
      <BackLink href="/purchase-orders" />
      <RecordHeader
        title={`Purchase PO-${po.id}`}
        subtitle={`from ${po.supplierName}`}
        badges={
          <div data-testid={`po-status-${po.id}`} className="flex items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${PO_STATUS_COLOR[po.status]}`}>{po.status}</span>
          </div>
        }
        actions={po.status === "DRAFT" && <>
          <button disabled={busy} onClick={() => { if (confirm("Cancel this PO?")) cancelMut.mutate(po.id); }} className={BTN_DANGER}>Cancel PO</button>
          <button disabled={busy} onClick={() => { if (confirm("Mark as received? This will add stock.")) receiveMut.mutate(po.id); }} className={BTN_PRIMARY}>
            Receive stock
          </button>
        </>}
      />

      <RecordLayout trail={<>
        <TrailCard title="Progress">
          <div className="flex justify-between text-xs"><span className="text-gray-500">Ordered</span><ClientDate iso={po.createdAt} /></div>
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

        <DetailColumns columns={[
          { title: "Supplier details", fields: <><Field label="Supplier" value={po.supplierName} /><Field label="Order" value={`PO-${po.id}`} /></> },
          { title: "Delivery", fields: <><Field label="Into warehouse" value={po.warehouseName} /></> },
          { title: "Amounts", fields: <><Field label="Units" value={units.toLocaleString()} /><Field label="Total cost" value={formatGBP(poTotal(po))} /></> },
        ]} />

        <Card title="Products" flush>
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>{["Product", "Quantity", "Unit cost", "Total"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {po.lines.map(l => (
                <tr key={l.id}>
                  <td className={TD}>
                    <span className="font-mono text-xs text-gray-500">{l.productSku}</span>
                    <span className="block text-gray-800">{l.productName}</span>
                  </td>
                  <td className={`${TD} tabular-nums`}>{Number(l.quantity).toLocaleString()}</td>
                  <td className={`${TD} tabular-nums`}>{l.unitCost != null ? formatGBP(l.unitCost) : "—"}</td>
                  <td className={`${TD} tabular-nums`}>{formatGBP((l.quantity ?? 0) * (l.unitCost ?? 0))}</td>
                </tr>
              ))}
            </tbody>
            <tfoot className="border-t border-gray-200 font-semibold">
              <tr><td className={TD}>Total</td><td className={`${TD} tabular-nums`}>{units.toLocaleString()}</td><td /><td className={`${TD} tabular-nums`}>{formatGBP(poTotal(po))}</td></tr>
            </tfoot>
          </table>
        </Card>

        <div className="bg-white border border-gray-200 rounded-lg p-4">
          <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Notes</h3>
          <p className="text-sm text-gray-700 whitespace-pre-line">{po.notes || <span className="text-gray-400">No notes</span>}</p>
        </div>
      </RecordLayout>
    </div>
  );
}
