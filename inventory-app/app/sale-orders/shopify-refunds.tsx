"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getShopifyRefunds, applyShopifyRefund, dismissShopifyRefund } from "@/lib/api";
import { ShopifyRefund } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import { useConfirm } from "@/components/ConfirmDialog";

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.error ?? "Something went wrong";
}

const RESTOCK_LABEL: Record<string, string> = {
  return: "sent back", cancel: "not sent", no_restock: "kept by the customer", legacy_restock: "restocked in Shopify",
};

/**
 * Refunds made in Shopify for this order (7.4). They wait here: "Apply in the ERP" makes the credit note (ticked
 * items back in stock) or cancels what has not shipped; "Dismiss" when nothing is needed (business-rules.md §5).
 */
function Refund({ orderId, refund }: { orderId: number; refund: ShopifyRefund }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [restock, setRestock] = useState<number[]>(
    refund.lines.filter(l => l.restockType === "return" && l.saleOrderLineId != null).map(l => l.saleOrderLineId as number));
  const [error, setError] = useState<string | null>(null);
  const done = () => {
    setError(null);
    qc.invalidateQueries({ queryKey: ["shopify-refunds", orderId] });
    qc.invalidateQueries({ queryKey: ["sale-order", orderId] });
    qc.invalidateQueries({ queryKey: ["shopify-health"] });
  };
  const apply = useMutation({ mutationFn: () => applyShopifyRefund(orderId, refund.id, restock), onSuccess: done, onError: e => setError(errorText(e)) });
  const dismiss = useMutation({ mutationFn: (reason: string) => dismissShopifyRefund(orderId, refund.id, reason), onSuccess: done, onError: e => setError(errorText(e)) });
  const open = refund.status === "TO_REVIEW";

  return (
    <div data-testid={`shopify-refund-${refund.refundId}`}
      className={`text-sm rounded border px-3 py-2 ${open ? "bg-amber-50 border-amber-200 text-amber-900" : "bg-gray-50 border-gray-200 text-gray-700"}`}>
      <div className="flex items-center gap-2">
        <p className="font-semibold">{open ? "Refund in Shopify to review" : "Refund in Shopify"}: {formatGBP(refund.amount)}</p>
        {refund.refundedAt && <span className="text-xs text-gray-500"><ClientDate iso={refund.refundedAt} showTime /></span>}
        {!open && <span className="ml-auto text-xs font-semibold px-2 py-0.5 rounded bg-gray-200 text-gray-700">{refund.status === "DONE" ? "Applied" : "Dismissed"}</span>}
      </div>
      {refund.note && <p className="text-xs mt-0.5">“{refund.note}”</p>}
      {refund.lines.length > 0 && (
        <table className="w-full text-xs mt-2">
          <tbody>
            {refund.lines.map((l, i) => (
              <tr key={i}>
                <td className="py-0.5 pr-2">{Number(l.quantity)} × {l.sku ?? l.title ?? "item"}</td>
                <td className="py-0.5 pr-2 text-right">{formatGBP(l.amount)}</td>
                <td className="py-0.5 pr-2 text-gray-500">{l.restockType ? RESTOCK_LABEL[l.restockType] ?? l.restockType : ""}</td>
                <td className="py-0.5 text-right">
                  {open && l.saleOrderLineId != null && (
                    <label className="inline-flex items-center gap-1">
                      <input type="checkbox" checked={restock.includes(l.saleOrderLineId)}
                        onChange={e => setRestock(e.target.checked ? [...restock, l.saleOrderLineId as number] : restock.filter(x => x !== l.saleOrderLineId))} />
                      Back in stock
                    </label>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {Number(refund.otherAmount) > 0 && <p className="text-xs mt-1">Beyond the items (shipping or other): {formatGBP(refund.otherAmount)}</p>}
      {open ? (
        <div className="mt-2 flex items-center gap-3">
          <button type="button" disabled={apply.isPending} onClick={async () => { if (await ask({ title: "Apply this Shopify refund here?", message: "The credit note and any restock are recorded in the ERP.", action: "Apply refund" })) apply.mutate(); }}
            className="bg-brand-600 text-white px-3 py-1.5 rounded-md text-xs font-medium hover:bg-brand-700 disabled:opacity-50">
            {apply.isPending ? "Applying..." : "Apply in the ERP"}
          </button>
          <button type="button" disabled={dismiss.isPending}
            onClick={() => { const reason = window.prompt("Why does this refund need nothing in the ERP?"); if (reason) dismiss.mutate(reason); }}
            className="text-xs text-gray-600 hover:underline">Dismiss</button>
          <span className="text-[11px] text-amber-700">Invoiced items get a credit note; items not shipped are cancelled on the order.</span>
        </div>
      ) : (
        <p className="text-xs mt-1" data-testid="shopify-refund-outcome">
          {refund.outcome}{refund.reviewedBy ? ` — ${refund.reviewedBy}` : ""}{refund.reviewedAt ? <>, <ClientDate iso={refund.reviewedAt} showTime /></> : null}
        </p>
      )}
      {error && <p role="alert" className="text-xs text-red-600 mt-1">{error}</p>}
    </div>
  );
}

export default function ShopifyRefunds({ orderId }: { orderId: number }) {
  const { data: refunds = [] } = useQuery<ShopifyRefund[]>({ queryKey: ["shopify-refunds", orderId], queryFn: () => getShopifyRefunds(orderId) });
  if (refunds.length === 0) return null;
  return <div className="space-y-2">{refunds.map(r => <Refund key={r.id} orderId={orderId} refund={r} />)}</div>;
}
