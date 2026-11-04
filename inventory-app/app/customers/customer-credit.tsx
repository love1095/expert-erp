"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getCustomerCredits, getPaymentAccounts, receiveOnAccount, voidCustomerCredit } from "@/lib/api";
import { Customer, CustomerCredit, PaymentAccount } from "@/types";
import { formatGBP } from "@/lib/currency";
import { Card, BTN_SECONDARY } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { isoDay } from "./statement-card";
import { METHOD_LABEL } from "../invoices/payments-card";

const INPUT = "border border-gray-300 rounded px-2 py-1 text-sm";

/**
 * Phase H1: the customer's credit, like Cin7's Customer credit: money paid over an invoice, or paid before there is
 * one. It shows on their statement and is used on invoices ("Use customer credit" on the invoice).
 */
export default function CustomerCreditCard({ customer }: { customer: Customer }) {
  const qc = useQueryClient();
  const { user } = useAuth();
  const isManager = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: credits = [] } = useQuery<CustomerCredit[]>({ queryKey: ["customer-credits", customer.id], queryFn: () => getCustomerCredits(customer.id) });
  const { data: accounts = [] } = useQuery<PaymentAccount[]>({ queryKey: ["payment-accounts"], queryFn: getPaymentAccounts });
  const active = accounts.filter(a => a.active);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ amount: "", receivedOn: isoDay(new Date()), accountId: "", reference: "" });
  const [error, setError] = useState("");
  const available = credits.reduce((s, c) => s + Number(c.available), 0);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["customer-credits", customer.id] });
    qc.invalidateQueries({ queryKey: ["customer-statement", customer.id] });
    qc.invalidateQueries({ queryKey: ["customer-credit", customer.id] });
    setError("");
  };
  const fail = (e: unknown) => setError((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Could not save");
  const accountId = form.accountId || String(active.find(a => a.defaultAccount)?.id ?? "");
  const receive = useMutation({
    mutationFn: () => receiveOnAccount(customer.id, { amount: Number(form.amount), receivedOn: form.receivedOn,
      accountId: accountId ? Number(accountId) : undefined, reference: form.reference.trim() || undefined }),
    onSuccess: () => { refresh(); setOpen(false); setForm({ ...form, amount: "", reference: "" }); },
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => voidCustomerCredit(customer.id, id, reason),
    onSuccess: refresh, onError: fail,
  });

  return (
    <Card title="Customer credit" testId="customer-credits"
      right={<span className="text-xs text-gray-600">Available <strong className="tabular-nums" data-testid="customer-credit-available">{formatGBP(available)}</strong></span>}>
      {credits.length === 0 && !open && <p className="text-sm text-gray-400">None: an overpayment, or a payment before the invoice, shows here</p>}
      {credits.length > 0 && (
        <table className="w-full text-xs mb-2">
          <thead><tr className="text-left text-gray-500 border-b border-gray-200">
            <th className="py-1.5 pr-2 font-medium">Received</th><th className="pr-2 font-medium">From</th><th className="pr-2 font-medium">Account</th>
            <th className="pr-2 font-medium">Reference</th><th className="pr-2 font-medium text-right">Amount</th><th className="pr-2 font-medium text-right">Used</th>
            <th className="pr-2 font-medium text-right">Left</th><th></th></tr></thead>
          <tbody className="tabular-nums">
            {credits.map(c => (
              <tr key={c.id} className="border-b border-gray-100">
                <td className="py-1.5 pr-2">{new Date(c.receivedOn + "T00:00:00").toLocaleDateString("en-GB")}</td>
                <td className="pr-2">{c.sourceInvoiceId
                  ? <Link href={`/invoices/${c.sourceInvoiceId}`} className="text-brand-700 hover:underline">Overpaid {c.sourceInvoiceNumber}</Link>
                  : c.note ?? "Paid on account"}</td>
                <td className="pr-2 text-gray-600">{c.account ?? METHOD_LABEL[c.method]}</td>
                <td className="pr-2 text-gray-600">{c.reference}</td>
                <td className="pr-2 text-right">{formatGBP(c.amount)}</td>
                <td className="pr-2 text-right">{Number(c.used) ? formatGBP(c.used) : ""}</td>
                <td className="pr-2 text-right font-semibold">{formatGBP(c.available)}</td>
                <td className="text-right">{isManager && Number(c.used) === 0 && (
                  <button type="button" className="text-gray-400 hover:text-red-600" aria-label={`Remove credit ${c.id}`}
                    onClick={() => { const reason = window.prompt("Why is this credit removed?"); if (reason) remove.mutate({ id: c.id, reason }); }}>✕</button>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {open ? (
        <div className="flex flex-wrap items-end gap-2 text-xs" data-testid="receive-on-account">
          <label className="flex flex-col gap-1 text-gray-500">Amount
            <input type="number" min="0.01" step="0.01" value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })}
              aria-label="Amount received" className={`${INPUT} w-28`} /></label>
          <label className="flex flex-col gap-1 text-gray-500">Received
            <input type="date" value={form.receivedOn} max={isoDay(new Date())} onChange={e => setForm({ ...form, receivedOn: e.target.value })}
              aria-label="Date received" className={INPUT} /></label>
          {active.length > 0 && (
            <label className="flex flex-col gap-1 text-gray-500">Account
              <select value={accountId} onChange={e => setForm({ ...form, accountId: e.target.value })} aria-label="Payment account" className={INPUT}>
                {!active.some(a => a.defaultAccount) && <option value="">Choose…</option>}
                {active.map(a => <option key={a.id} value={a.id}>{a.code}: {a.name}</option>)}
              </select></label>
          )}
          <label className="flex flex-col gap-1 text-gray-500">Reference
            <input value={form.reference} maxLength={100} onChange={e => setForm({ ...form, reference: e.target.value })}
              aria-label="Reference" className={`${INPUT} w-36`} /></label>
          <button type="button" className={BTN_SECONDARY} disabled={!form.amount || receive.isPending || (active.length > 0 && !accountId)}
            onClick={() => receive.mutate()}>{receive.isPending ? "Saving…" : "Save"}</button>
          <button type="button" className="text-gray-500 hover:underline" onClick={() => setOpen(false)}>Cancel</button>
        </div>
      ) : (
        <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => setOpen(true)} data-testid="open-receive-on-account">
          + Payment on account</button>
      )}
      {error && <p role="alert" className="text-sm text-red-600 mt-2">{error}</p>}
    </Card>
  );
}
