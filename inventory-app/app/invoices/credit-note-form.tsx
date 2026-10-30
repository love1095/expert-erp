"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createCreditNote, getWarehouses } from "@/lib/api";
import { Invoice, PaymentMethod, Warehouse } from "@/types";

type Settle = "APPLY_TO_INVOICE" | "REFUND" | "KEEP_AS_CREDIT";
const INPUT = "w-full border border-gray-300 rounded-md px-2 py-1 text-sm";

/**
 * Credit note & restock for a sales invoice (as Cin7's C&R): quantities per invoice item (at most what is not
 * credited yet), goods back to stock or not, an extra amount such as a shipping refund, and how it is settled.
 */
export default function CreditNoteForm({ invoice, onDone, onCancel }: {
  invoice: Invoice; onDone: (creditNote: Invoice) => void; onCancel: () => void;
}) {
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses-all"], queryFn: getWarehouses });
  const [qty, setQty] = useState<Record<number, string>>({});
  const [restock, setRestock] = useState<Record<number, boolean>>({});
  const [extra, setExtra] = useState({ description: "", price: "" });
  const [reason, setReason] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const due = invoice.totalAmount - invoice.amountPaid;
  const [settle, setSettle] = useState<Settle>(due > 0 ? "APPLY_TO_INVOICE" : "REFUND");
  const [refund, setRefund] = useState<{ method: PaymentMethod; reference: string }>({ method: "BANK", reference: "" });
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: createCreditNote,
    onSuccess: (cn: Invoice) => onDone(cn),
    onError: (e: any) => setError(e.response?.data?.error || "Could not create the credit note"),
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    const lines = invoice.items
      .filter(i => Number(qty[i.id] || 0) > 0)
      .map(i => ({ invoiceItemId: i.id, quantity: Number(qty[i.id]), restock: !!restock[i.id] }));
    const charges = extra.description.trim() && extra.price ? [{ description: extra.description.trim(), unitPrice: Number(extra.price) }] : [];
    if (lines.length === 0 && charges.length === 0) { setError("Credit at least one item or amount"); return; }
    save.mutate({ id: invoice.id, data: {
      lines, charges, reason: reason || undefined, warehouseId: warehouseId ? Number(warehouseId) : undefined,
      settle, refundMethod: refund.method, refundReference: refund.reference || undefined,
    }});
  };

  return (
    <form onSubmit={submit} data-testid="credit-note-form" className="border-t border-gray-100 pt-3 space-y-3">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Credit note & restock</p>
      {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
      <div className="space-y-2">
        {invoice.items.map(i => {
          const left = i.quantity - (i.creditedQuantity ?? 0);
          const label = i.productSku ?? i.description ?? "Item";
          return (
            <div key={i.id} className="text-xs">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-gray-700" title={i.description}>{label}</span>
                <input type="number" min="0" max={left} step="any" disabled={left <= 0} placeholder={`max ${left}`}
                  aria-label={`Credit quantity for ${label}`} value={qty[i.id] ?? ""}
                  onChange={e => setQty({ ...qty, [i.id]: e.target.value })}
                  className="w-20 border border-gray-300 rounded-md px-2 py-0.5 text-sm disabled:bg-gray-100" />
              </div>
              {i.productId && i.saleOrderLineId && (
                <label className="flex items-center gap-1 text-gray-500 mt-0.5">
                  <input type="checkbox" checked={!!restock[i.id]} aria-label={`Restock ${label}`}
                    onChange={e => setRestock({ ...restock, [i.id]: e.target.checked })} />
                  Back to stock
                </label>
              )}
            </div>
          );
        })}
      </div>
      <div className="grid grid-cols-3 gap-1.5">
        <input aria-label="Extra amount description" value={extra.description} onChange={e => setExtra({ ...extra, description: e.target.value })}
          placeholder="e.g. Shipping refund" className={`${INPUT} col-span-2`} />
        <input aria-label="Extra amount price" type="number" min="0" step="any" value={extra.price}
          onChange={e => setExtra({ ...extra, price: e.target.value })} placeholder="£ ex VAT" className={INPUT} />
      </div>
      {Object.values(restock).some(Boolean) && (
        <select aria-label="Restock to warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} className={INPUT}>
          <option value="">The order&apos;s warehouse</option>
          {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
      )}
      <input aria-label="Credit reason" value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason (optional)" className={INPUT} />
      <select aria-label="Settle the credit" value={settle} onChange={e => setSettle(e.target.value as Settle)} className={INPUT}>
        {due > 0 && <option value="APPLY_TO_INVOICE">Pay off the invoice (£{due.toFixed(2)} due)</option>}
        <option value="REFUND">Refund the customer</option>
        <option value="KEEP_AS_CREDIT">Keep as customer credit</option>
      </select>
      {settle === "REFUND" && (
        <div className="grid grid-cols-2 gap-1.5">
          <select aria-label="Refund method" value={refund.method} onChange={e => setRefund({ ...refund, method: e.target.value as PaymentMethod })} className={INPUT}>
            {(["BANK", "CASH", "STRIPE"] as PaymentMethod[]).map(m => <option key={m}>{m}</option>)}
          </select>
          <input aria-label="Refund reference" value={refund.reference} onChange={e => setRefund({ ...refund, reference: e.target.value })}
            placeholder="Reference" className={INPUT} />
        </div>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={save.isPending}
          className="flex-1 bg-brand-600 text-white py-1.5 rounded-lg text-xs font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving…" : "Create credit note"}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-gray-500 hover:text-gray-700 px-2">Cancel</button>
      </div>
    </form>
  );
}
