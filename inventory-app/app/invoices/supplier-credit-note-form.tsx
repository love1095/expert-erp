"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createSupplierCreditNote, getPaymentAccounts } from "@/lib/api";
import { Invoice, PaymentAccount } from "@/types";
import { isoDay } from "../customers/statement-card";
import { useConfirm } from "@/components/ConfirmDialog";

type Settle = "APPLY_TO_BILL" | "REFUND" | "KEEP_AS_CREDIT";
const INPUT = "w-full border border-gray-300 rounded-md px-2 py-1 text-sm";

/**
 * Phase H2: a supplier credit note against a bill (Cin7's purchase Credit note): the supplier's number, quantities per
 * bill line (at most what is not credited yet), goods sent back to the supplier (out of stock at the cost they came
 * in at), an extra amount such as a rebate, and how it is settled.
 */
export default function SupplierCreditNoteForm({ bill, onDone, onCancel }: {
  bill: Invoice; onDone: (creditNote: Invoice) => void; onCancel: () => void;
}) {
  const ask = useConfirm();
  const { data: accounts = [] } = useQuery<PaymentAccount[]>({ queryKey: ["payment-accounts"], queryFn: getPaymentAccounts });
  const active = accounts.filter(a => a.active);
  const [number, setNumber] = useState("");
  const [date, setDate] = useState(() => isoDay(new Date()));
  const [qty, setQty] = useState<Record<number, string>>({});
  const [back, setBack] = useState<Record<number, boolean>>({});
  const [extra, setExtra] = useState({ description: "", price: "" });
  const [reason, setReason] = useState("");
  const due = bill.totalAmount - bill.amountPaid;
  const [settle, setSettle] = useState<Settle>(due > 0 ? "APPLY_TO_BILL" : "KEEP_AS_CREDIT");
  const [refund, setRefund] = useState({ accountId: "", reference: "" });
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: createSupplierCreditNote,
    onSuccess: (cn: Invoice) => onDone(cn),
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } })?.response?.data?.error || "Could not create the credit note"),
  });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    const lines = bill.items.filter(i => Number(qty[i.id] || 0) > 0)
      .map(i => ({ invoiceItemId: i.id, quantity: Number(qty[i.id]), returnGoods: !!back[i.id] }));
    const charges = extra.description.trim() && extra.price ? [{ description: extra.description.trim(), unitPrice: Number(extra.price) }] : [];
    if (lines.length === 0 && charges.length === 0) { setError("Credit at least one line or amount"); return; }
    const returned = lines.filter(l => l.returnGoods).length;
    if (!(await ask({ title: `Record a supplier credit for ${bill.invoiceNumber}?`,
      message: returned ? `${returned} line${returned !== 1 ? "s go" : " goes"} back to the supplier and leave stock.` : "No goods go back.",
      details: `${lines.length} line${lines.length !== 1 ? "s" : ""}${charges.length ? " + an amount" : ""}`, action: "Record credit" }))) return;
    const refundAccount = refund.accountId || String(active.find(a => a.defaultAccount)?.id ?? "");
    save.mutate({ id: bill.id, data: {
      number: number.trim() || undefined, date, lines, charges, reason: reason.trim() || undefined, settle,
      refundAccountId: settle === "REFUND" && refundAccount ? Number(refundAccount) : undefined,
      refundReference: refund.reference.trim() || undefined,
    }});
  };

  return (
    <form onSubmit={submit} data-testid="supplier-credit-note-form" className="space-y-3">
      {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
      <div className="grid grid-cols-2 gap-1.5">
        <input aria-label="Supplier credit note number" value={number} onChange={e => setNumber(e.target.value)} maxLength={50}
          placeholder={`Their number (else ${bill.invoiceNumber}-CR…)`} className={INPUT} />
        <input aria-label="Credit note date" type="date" value={date} max={isoDay(new Date())} onChange={e => setDate(e.target.value)} className={INPUT} />
      </div>
      <div className="space-y-2">
        {bill.items.map(i => {
          const left = i.quantity - (i.creditedQuantity ?? 0);
          const label = i.productSku ?? i.description ?? "Line";
          return (
            <div key={i.id} className="text-xs">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-gray-700" title={i.description}>{label} <span className="text-gray-400">@ {Number(i.unitPrice).toFixed(2)}</span></span>
                <input type="number" min="0" max={left} step="any" disabled={left <= 0} placeholder={`max ${left}`}
                  aria-label={`Credit quantity for ${label}`} value={qty[i.id] ?? ""}
                  onChange={e => setQty({ ...qty, [i.id]: e.target.value })}
                  className="w-20 border border-gray-300 rounded-md px-2 py-0.5 text-sm disabled:bg-gray-100" />
              </div>
              {i.productId && i.purchaseOrderLineId && (
                <label className="flex items-center gap-1 text-gray-500 mt-0.5">
                  <input type="checkbox" checked={!!back[i.id]} aria-label={`Send back ${label}`}
                    onChange={e => setBack({ ...back, [i.id]: e.target.checked })} />
                  Send back to the supplier (out of stock)
                </label>
              )}
            </div>
          );
        })}
      </div>
      <div className="grid grid-cols-3 gap-1.5">
        <input aria-label="Extra amount description" value={extra.description} onChange={e => setExtra({ ...extra, description: e.target.value })}
          placeholder="e.g. Volume rebate" className={`${INPUT} col-span-2`} />
        <input aria-label="Extra amount price" type="number" min="0" step="any" value={extra.price}
          onChange={e => setExtra({ ...extra, price: e.target.value })} placeholder="£ ex VAT" className={INPUT} />
      </div>
      <input aria-label="Credit reason" value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason (optional)" className={INPUT} />
      <select aria-label="Settle the credit" value={settle} onChange={e => setSettle(e.target.value as Settle)} className={INPUT}>
        {due > 0 && <option value="APPLY_TO_BILL">Pay off the bill (£{due.toFixed(2)} due)</option>}
        <option value="REFUND">The supplier refunds it</option>
        <option value="KEEP_AS_CREDIT">Keep as credit with the supplier</option>
      </select>
      {settle === "REFUND" && (
        <div className="grid grid-cols-2 gap-1.5">
          {active.length > 0 && (
            <select aria-label="Refund into account" value={refund.accountId || String(active.find(a => a.defaultAccount)?.id ?? "")}
              onChange={e => setRefund({ ...refund, accountId: e.target.value })} className={INPUT}>
              {active.map(a => <option key={a.id} value={a.id}>{a.code}: {a.name}</option>)}
            </select>
          )}
          <input aria-label="Refund reference" value={refund.reference} onChange={e => setRefund({ ...refund, reference: e.target.value })}
            placeholder="Reference" className={INPUT} />
        </div>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={save.isPending}
          className="flex-1 bg-brand-600 text-white py-1.5 rounded-lg text-xs font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving…" : "Create supplier credit note"}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-gray-500 hover:text-gray-700 px-2">Cancel</button>
      </div>
    </form>
  );
}
