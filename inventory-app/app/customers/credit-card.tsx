"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getCustomerCredit, setCustomerHold } from "@/lib/api";
import { CreditStatus, Customer } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import { Card } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { useAskReason, useConfirm } from "@/components/ConfirmDialog";

/**
 * Phase G2: what the customer owes and has on order against their credit limit, overdue invoices, and the hold.
 * On hold (by hand, over the limit, or overdue), their orders warn at entry and only a manager can confirm them.
 */
export default function CreditCard({ customer }: { customer: Customer }) {
  const ask = useConfirm();
  const askReason = useAskReason();
  const qc = useQueryClient();
  const { user } = useAuth();
  const canHold = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: st } = useQuery<CreditStatus>({ queryKey: ["customer-credit", customer.id], queryFn: () => getCustomerCredit(customer.id) });
  const [error, setError] = useState<string | null>(null);
  const hold = useMutation({
    mutationFn: ({ on, reason }: { on: boolean; reason?: string }) => setCustomerHold(customer.id, on, reason),
    onSuccess: (s: CreditStatus) => {
      setError(null);
      qc.setQueryData(["customer-credit", customer.id], s);
      qc.invalidateQueries({ queryKey: ["customer", customer.id] });
    },
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Could not change the hold"),
  });
  if (!st) return null;

  return (
    <Card title="Credit" testId="customer-credit"
      right={<span data-testid="customer-credit-status" className={`text-xs font-semibold px-2 py-0.5 rounded ${st.held ? "bg-red-100 text-red-700" : "bg-emerald-100 text-emerald-700"}`}>
        {st.held ? "On credit hold" : "In good standing"}</span>}>
      {st.held && (
        <ul className="mb-3 text-sm text-red-800 bg-red-50 rounded px-3 py-2 space-y-0.5">
          {st.reasons.map(r => <li key={r}>• {r}</li>)}
        </ul>
      )}
      <dl className="grid grid-cols-2 md:grid-cols-5 gap-3 text-xs">
        <div><dt className="text-gray-500">Credit limit</dt><dd className="text-gray-800 tabular-nums">{st.creditLimit != null ? formatGBP(st.creditLimit) : "None"}</dd></div>
        <div><dt className="text-gray-500">Owed (invoices)</dt><dd className="text-gray-800 tabular-nums">{formatGBP(st.balance)}</dd></div>
        <div><dt className="text-gray-500">On order, not invoiced</dt><dd className="text-gray-800 tabular-nums">{formatGBP(st.notInvoiced)}</dd></div>
        <div><dt className="text-gray-500">Available</dt>
          <dd className={`tabular-nums ${st.available != null && Number(st.available) < 0 ? "text-red-700 font-semibold" : "text-gray-800"}`}>
            {st.available != null ? formatGBP(st.available) : "—"}</dd></div>
        <div><dt className="text-gray-500">Overdue</dt>
          <dd className={`tabular-nums ${Number(st.overdue) > 0 ? "text-red-700 font-semibold" : "text-gray-800"}`}>
            {formatGBP(st.overdue)}{st.daysOverdue != null && <span className="block text-gray-500 font-normal">oldest {st.daysOverdue} days</span>}</dd></div>
      </dl>
      <div className="mt-3 flex items-center gap-3 text-xs">
        {st.manualHold ? <>
          <span className="text-gray-600">Put on hold by {st.heldBy}{st.heldAt && <>, <ClientDate iso={st.heldAt} /></>}: {st.holdReason}</span>
          {canHold && <button type="button" onClick={async () => { if (await ask({ title: "Release the credit hold?", action: "Release" })) hold.mutate({ on: false }); }} className="text-brand-700 hover:underline">Release the hold</button>}
        </> : canHold && (
          <button type="button" onClick={async () => { const reason = await askReason({ title: `Put ${customer.name} on credit hold?`, message: "New orders cannot be confirmed and shipping stops until the hold is lifted.", reasonLabel: "Why is the customer put on hold?", action: "Put on hold", danger: true }); if (reason) hold.mutate({ on: true, reason }); }}
            className="text-red-600 hover:underline">Put on hold</button>
        )}
        <span className="text-gray-400 ml-auto">Over the limit or an invoice overdue holds the customer by itself</span>
      </div>
      {error && <p role="alert" className="text-sm text-red-600 mt-2">{error}</p>}
    </Card>
  );
}
