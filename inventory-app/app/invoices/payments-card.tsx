"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  allocateCreditNote, allocateSupplierCreditNote, applyCustomerCredit, applySupplierCredit, getCustomerCredits, getOpenCreditNotes,
  getPaymentAccounts, getSupplierCredits, getSupplierOpenCreditNotes, payInvoice, voidPayment,
} from "@/lib/api";
import { CustomerCredit, Invoice, OpenCreditNote, PaymentAccount, PaymentMethod, SupplierCredit } from "@/types";
import { TrailCard, BTN_PRIMARY } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { isoDay } from "../customers/statement-card";
import { useAskReason, useConfirm } from "@/components/ConfirmDialog";

export const METHOD_LABEL: Record<PaymentMethod, string> = {
  BANK: "Bank", CASH: "Cash", STRIPE: "Card", CARD: "Card", PAYPAL: "PayPal", SHOPIFY: "Shopify",
  CREDIT_NOTE: "Credit note", CUSTOMER_CREDIT: "Customer credit", SUPPLIER_CREDIT: "Supplier deposit",
};
const INPUT = "w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const day = (iso: string) => new Date(iso).toLocaleDateString("en-GB");

/**
 * Phase H1: an invoice's payments like Cin7's Payments section. Record a payment (day, payment account, reference; an
 * overpaid sales invoice puts the rest on the customer's credit), use the customer's credit, allocate a credit note
 * kept as credit, and (a manager) void a payment entered by mistake, with a reason.
 */
export default function PaymentsCard({ inv, busy, hidden, onDone, onFail }: {
  inv: Invoice; busy: boolean; hidden: boolean;
  onDone: (u: Invoice) => void; onFail: (e: unknown) => void;
}) {
  const ask = useConfirm();
  const askReason = useAskReason();
  const { user } = useAuth();
  const isManager = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const isCreditNote = inv.kind === "CREDIT_NOTE";
  const salesInvoice = inv.type === "SALES" && !isCreditNote;
  // H2: a supplier's bill uses the supplier's deposits and kept credit notes
  const bill = inv.type === "PURCHASE" && !isCreditNote;
  const open = inv.status === "ISSUED";
  const balance = Number(inv.totalAmount) - Number(inv.amountPaid);
  const money = (n: number | undefined) => `${inv.currency} ${Number(n ?? 0).toFixed(2)}`;

  const { data: accounts = [] } = useQuery<PaymentAccount[]>({ queryKey: ["payment-accounts"], queryFn: getPaymentAccounts });
  const active = accounts.filter(a => a.active);
  const customerId = inv.customerId;
  const { data: credits = [] } = useQuery<CustomerCredit[]>({
    queryKey: ["customer-credits", customerId], queryFn: () => getCustomerCredits(customerId!), enabled: salesInvoice && open && customerId != null,
  });
  const { data: creditNotes = [] } = useQuery<OpenCreditNote[]>({
    queryKey: ["open-credit-notes", customerId], queryFn: () => getOpenCreditNotes(customerId!), enabled: salesInvoice && open && customerId != null,
  });
  const supplierId = inv.supplierId;
  const { data: deposits = [] } = useQuery<SupplierCredit[]>({
    queryKey: ["supplier-credits", supplierId], queryFn: () => getSupplierCredits(supplierId!), enabled: bill && open && supplierId != null,
  });
  const { data: supplierNotes = [] } = useQuery<OpenCreditNote[]>({
    queryKey: ["supplier-open-credit-notes", supplierId], queryFn: () => getSupplierOpenCreditNotes(supplierId!), enabled: bill && open && supplierId != null,
  });
  const creditLeft = bill ? deposits.reduce((s, c) => s + Number(c.available), 0) : credits.reduce((s, c) => s + Number(c.available), 0);
  const notesToAllocate = bill ? supplierNotes : creditNotes;

  const [amount, setAmount] = useState("");
  const [paidOn, setPaidOn] = useState(() => isoDay(new Date()));
  const [accountId, setAccountId] = useState("");
  const [method, setMethod] = useState<PaymentMethod>("BANK");
  const [reference, setReference] = useState("");
  const chosenAccount = accountId || String(active.find(a => a.defaultAccount)?.id ?? "");

  const done = (u: Invoice) => { onDone(u); setAmount(""); setReference(""); };
  const pay = useMutation({
    mutationFn: () => payInvoice(inv.id, {
      amount: parseFloat(amount), paidOn, reference: reference.trim() || undefined,
      ...(active.length > 0 ? { accountId: Number(chosenAccount) } : { method }),
    }),
    onSuccess: done, onError: onFail,
  });
  const useCredit = useMutation({ mutationFn: () => (bill ? applySupplierCredit(inv.id) : applyCustomerCredit(inv.id)), onSuccess: done, onError: onFail });
  const allocate = useMutation({
    mutationFn: (cnId: number) => (bill ? allocateSupplierCreditNote(inv.id, cnId) : allocateCreditNote(inv.id, cnId)),
    onSuccess: done, onError: onFail,
  });
  const voidIt = useMutation({
    mutationFn: ({ paymentId, reason }: { paymentId: number; reason: string }) => voidPayment(inv.id, paymentId, reason),
    onSuccess: done, onError: onFail,
  });
  const working = busy || pay.isPending || useCredit.isPending || allocate.isPending || voidIt.isPending;
  const overpay = (salesInvoice || bill) && Number(amount) - balance > 0.004;

  return (
    <TrailCard title={isCreditNote ? "Refunds and credit used" : "Payments"}
      badge={inv.status === "CANCELLED" ? <span className="text-[11px] font-semibold text-gray-400">Cancelled</span>
        : inv.status === "DRAFT" ? <span className="text-[11px] font-semibold text-gray-400">Not issued</span>
        : <span className={`text-[11px] font-semibold ${balance > 0.004 ? "text-red-600" : "text-emerald-700"}`}>
            {balance > 0.004 ? `${money(balance)} due` : "Settled"}
          </span>}>
      {inv.payments.length === 0 && <p className="text-xs text-gray-400">None yet</p>}
      {inv.payments.map(p => (
        <div key={p.id} className="flex justify-between gap-2 text-xs" data-testid={`payment-${p.id}`}>
          <span className="text-gray-600 min-w-0">
            {p.method === "CREDIT_NOTE" ? `Credit note ${p.reference ?? ""}` : p.account ?? METHOD_LABEL[p.method]} · {day(p.paidAt)}
            {p.reference && p.method !== "CREDIT_NOTE" && ` · ${p.reference}`}
          </span>
          <span className="flex items-center gap-2 shrink-0">
            <span className="text-emerald-700 font-semibold tabular-nums">{Number(p.amount).toFixed(2)}</span>
            {isManager && p.method !== "SHOPIFY" && inv.status !== "CANCELLED" && (
              <button type="button" title="Void this payment" aria-label={`Void payment ${p.id}`} disabled={working}
                onClick={async () => { const reason = await askReason({ title: "Void this payment?", message: "The invoice is owed again by the amount of the payment.", reasonLabel: "Why is this payment voided?", action: "Void payment", danger: true }); if (reason) voidIt.mutate({ paymentId: p.id, reason }); }}
                className="text-gray-400 hover:text-red-600">✕</button>
            )}
          </span>
        </div>
      ))}
      {(inv.voidedPayments ?? []).length > 0 && (
        <details className="text-xs text-gray-500">
          <summary className="cursor-pointer">{inv.voidedPayments!.length} voided</summary>
          {inv.voidedPayments!.map(v => (
            <p key={v.paymentId} className="mt-1">
              <span className="line-through">{METHOD_LABEL[v.method]} · {day(v.paidAt)} · {Number(v.amount).toFixed(2)}</span>
              <span className="block text-gray-400">Voided by {v.voidedBy}: {v.reason}</span>
            </p>
          ))}
        </details>
      )}

      {open && !hidden && (
        <div className="border-t border-gray-100 pt-3 space-y-2" data-testid="record-payment">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{isCreditNote ? "Record refund" : "Record payment"}</p>
          <label htmlFor="invoice-pay-amount" className="sr-only">Amount</label>
          <input id="invoice-pay-amount" type="number" step="0.01" min="0.01" value={amount}
            onChange={e => setAmount(e.target.value)} placeholder={`Amount (due ${balance.toFixed(2)})`} className={INPUT} />
          {overpay && <p className="text-[11px] text-amber-700">£{(Number(amount) - balance).toFixed(2)} over: kept as {bill ? "a deposit with the supplier" : "the customer's credit"}</p>}
          <label htmlFor="invoice-pay-date" className="sr-only">Date paid</label>
          <input id="invoice-pay-date" type="date" value={paidOn} max={isoDay(new Date())} onChange={e => setPaidOn(e.target.value)} className={INPUT} />
          {active.length > 0 ? (
            <>
              <label htmlFor="invoice-pay-account" className="sr-only">Payment account</label>
              <select id="invoice-pay-account" value={chosenAccount} onChange={e => setAccountId(e.target.value)} className={INPUT}>
                {!active.some(a => a.defaultAccount) && <option value="">Payment account…</option>}
                {active.map(a => <option key={a.id} value={a.id}>{a.code}: {a.name}</option>)}
              </select>
            </>
          ) : (
            <>
              <label htmlFor="invoice-pay-method" className="sr-only">Method</label>
              <select id="invoice-pay-method" value={method} onChange={e => setMethod(e.target.value as PaymentMethod)} className={INPUT}>
                {(["BANK", "CARD", "CASH", "PAYPAL"] as PaymentMethod[]).map(m => <option key={m} value={m}>{METHOD_LABEL[m]}</option>)}
              </select>
              <p className="text-[11px] text-gray-400">Payment accounts (e.g. 650 Current Account) are set in Settings → Organisation</p>
            </>
          )}
          <label htmlFor="invoice-pay-reference" className="sr-only">Reference</label>
          <input id="invoice-pay-reference" value={reference} onChange={e => setReference(e.target.value)} placeholder="Reference (optional)"
            maxLength={100} autoComplete="off" className={INPUT} />
          <button disabled={!amount || (active.length > 0 && !chosenAccount) || working} onClick={async () => { if (await ask({ title: `Record a payment of £${amount}?`, action: "Record payment" })) pay.mutate(); }} className={`w-full ${BTN_PRIMARY}`}>
            {pay.isPending ? "Saving…" : isCreditNote ? "Record refund" : "Record payment"}
          </button>

          {(salesInvoice || bill) && creditLeft > 0.004 && (
            <button type="button" disabled={working} onClick={async () => { if (await ask({ title: "Use the credit on this document?", action: "Use credit" })) useCredit.mutate(); }} data-testid={bill ? "use-supplier-credit" : "use-customer-credit"}
              className="w-full text-xs text-brand-700 hover:underline text-left">
              {bill ? "Use supplier deposit" : "Use customer credit"} ({money(creditLeft)} available)
            </button>
          )}
          {(salesInvoice || bill) && notesToAllocate.length > 0 && (
            <div className="text-xs">
              <label htmlFor="invoice-allocate-cn" className="text-gray-500">Allocate credit note</label>
              <select id="invoice-allocate-cn" value="" disabled={working} className={`${INPUT} mt-1`}
                onChange={e => { if (e.target.value) allocate.mutate(Number(e.target.value)); }}>
                <option value="">Choose…</option>
                {notesToAllocate.map(c => <option key={c.id} value={c.id}>{c.number}: {money(c.available)} left</option>)}
              </select>
            </div>
          )}
        </div>
      )}
    </TrailCard>
  );
}
