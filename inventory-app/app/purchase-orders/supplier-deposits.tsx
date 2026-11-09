"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getPaymentAccounts, getSupplierCredits, paySupplierDeposit } from "@/lib/api";
import { PaymentAccount, SupplierCredit } from "@/types";
import { formatGBP } from "@/lib/currency";
import { TrailCard } from "@/components/DetailParts";
import { isoDay } from "../customers/statement-card";
import { useConfirm } from "@/components/ConfirmDialog";

const INPUT = "w-full border border-gray-300 rounded-md px-2 py-1 text-sm";

/**
 * Phase H2: money paid to the supplier before the bill (a deposit, e.g. a proforma), or left over from an overpaid
 * bill. Used on a bill with "Use supplier deposit" (Cin7's button on the bill's payments).
 */
export default function SupplierDeposits({ supplierId, purchaseOrderId }: { supplierId: number; purchaseOrderId: number }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data: credits = [] } = useQuery<SupplierCredit[]>({ queryKey: ["supplier-credits", supplierId], queryFn: () => getSupplierCredits(supplierId) });
  const { data: accounts = [] } = useQuery<PaymentAccount[]>({ queryKey: ["payment-accounts"], queryFn: getPaymentAccounts });
  const active = accounts.filter(a => a.active);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ amount: "", paidOn: isoDay(new Date()), accountId: "", reference: "" });
  const [error, setError] = useState("");
  const left = credits.filter(c => Number(c.available) > 0);
  const total = left.reduce((s, c) => s + Number(c.available), 0);
  const accountId = form.accountId || String(active.find(a => a.defaultAccount)?.id ?? "");
  const pay = useMutation({
    mutationFn: () => paySupplierDeposit(supplierId, { amount: Number(form.amount), paidOn: form.paidOn,
      accountId: accountId ? Number(accountId) : undefined, reference: form.reference.trim() || undefined, purchaseOrderId }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["supplier-credits", supplierId] }); setOpen(false); setForm({ ...form, amount: "", reference: "" }); setError(""); },
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Could not save"),
  });

  return (
    <TrailCard title="Supplier deposits" testId="supplier-deposits"
      badge={<span className="text-[11px] font-semibold text-gray-600 tabular-nums" data-testid="supplier-deposit-available">{formatGBP(total)}</span>}>
      {left.length === 0 && !open && <p className="text-xs text-gray-400">None with this supplier</p>}
      {left.map(c => (
        <div key={c.id} className="flex justify-between gap-2 text-xs">
          <span className="text-gray-600">{new Date(c.paidOn + "T00:00:00").toLocaleDateString("en-GB")} · {c.sourceInvoiceNumber ? `overpaid ${c.sourceInvoiceNumber}` : c.note}
            {c.reference && ` · ${c.reference}`}</span>
          <span className="tabular-nums font-semibold">{formatGBP(c.available)}</span>
        </div>
      ))}
      {open ? (
        <div className="space-y-1.5 border-t border-gray-100 pt-2">
          <input aria-label="Deposit amount" type="number" min="0.01" step="0.01" placeholder="Amount paid" value={form.amount}
            onChange={e => setForm({ ...form, amount: e.target.value })} className={INPUT} />
          <input aria-label="Deposit date" type="date" value={form.paidOn} max={isoDay(new Date())} onChange={e => setForm({ ...form, paidOn: e.target.value })} className={INPUT} />
          {active.length > 0 && (
            <select aria-label="Paid from account" value={accountId} onChange={e => setForm({ ...form, accountId: e.target.value })} className={INPUT}>
              {active.map(a => <option key={a.id} value={a.id}>{a.code}: {a.name}</option>)}
            </select>
          )}
          <input aria-label="Deposit reference" placeholder="Reference (e.g. proforma no.)" value={form.reference} maxLength={100}
            onChange={e => setForm({ ...form, reference: e.target.value })} className={INPUT} />
          <div className="flex gap-2">
            <button type="button" disabled={!form.amount || pay.isPending} onClick={async () => { if (await ask({ title: "Record this supplier deposit?", action: "Record" })) pay.mutate(); }}
              className="flex-1 bg-brand-600 text-white py-1.5 rounded-md text-xs font-medium hover:bg-brand-700 disabled:opacity-50">
              {pay.isPending ? "Saving…" : "Record deposit"}</button>
            <button type="button" onClick={() => setOpen(false)} className="text-xs text-gray-500 px-2">Cancel</button>
          </div>
        </div>
      ) : (
        <button type="button" onClick={() => setOpen(true)} data-testid="open-supplier-deposit"
          className="w-full mt-1 border border-dashed border-gray-300 rounded-md py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50">+ Deposit</button>
      )}
      {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
    </TrailCard>
  );
}
