"use client";

import { Clip } from "@/components/ListToolbar";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getInvoice, issueInvoice, cancelInvoice, payInvoice } from "@/lib/api";
import { Invoice, InvoiceStatus, PaymentMethod } from "@/types";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field,
  BTN_PRIMARY, BTN_SECONDARY, BTN_DANGER,
} from "@/components/DetailParts";
import CreditNoteForm from "./credit-note-form";

export const INVOICE_STATUS_COLOR: Record<InvoiceStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  ISSUED:    "bg-sky-100 text-sky-700",
  PAID:      "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const METHODS: PaymentMethod[] = ["CASH", "BANK", "STRIPE"];
const money = (inv: Invoice, n: number | undefined) => `${inv.currency} ${Number(n ?? 0).toFixed(2)}`;

// One invoice or credit note on its own page, like Cin7's invoice tab
export default function InvoiceView({ invoiceId }: { invoiceId: number }) {
  const qc = useQueryClient();
  const router = useRouter();
  const { data: inv, isLoading } = useQuery<Invoice>({ queryKey: ["invoice", invoiceId], queryFn: () => getInvoice(invoiceId) });

  const [error, setError] = useState("");
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState<PaymentMethod>("BANK");
  const [crediting, setCrediting] = useState(false);

  const onDone = (updated: Invoice) => {
    qc.setQueryData(["invoice", invoiceId], updated);
    qc.invalidateQueries({ queryKey: ["invoices"] });
    qc.invalidateQueries({ queryKey: ["sale-order"] });
    setError("");
  };
  const onFail = (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Request failed");

  const issueMut = useMutation({ mutationFn: issueInvoice, onSuccess: onDone, onError: onFail });
  const cancelMut = useMutation({ mutationFn: cancelInvoice, onSuccess: onDone, onError: onFail });
  const payMut = useMutation({
    mutationFn: ({ amount, method }: { amount: string; method: PaymentMethod }) => payInvoice(invoiceId, { amount: parseFloat(amount), method }),
    onSuccess: (u: Invoice) => { onDone(u); setPayAmount(""); },
    onError: onFail,
  });
  const busy = issueMut.isPending || cancelMut.isPending || payMut.isPending;

  if (isLoading || !inv) return <p className="text-gray-400 text-sm">Loading…</p>;

  const isCreditNote = inv.kind === "CREDIT_NOTE";
  const balance = Number(inv.totalAmount) - Number(inv.amountPaid);
  const canCredit = !isCreditNote && inv.type === "SALES" && (inv.status === "ISSUED" || inv.status === "PAID");

  return (
    <div data-testid={`invoice-page-${inv.id}`}>
      <BackLink href="/invoices" />
      <RecordHeader
        title={<>{isCreditNote ? "Credit note" : inv.type === "SALES" ? "Invoice" : "Supplier invoice"} {inv.invoiceNumber}</>}
        subtitle={`for ${inv.partyName}`}
        badges={
          <div className="flex flex-wrap items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${INVOICE_STATUS_COLOR[inv.status]}`}>{inv.status}</span>
            <span className="text-xs font-medium text-gray-500">{inv.type === "SALES" ? "Sales" : "Purchase"}</span>
            {isCreditNote && <span className="text-xs font-semibold text-gray-700">Credit note for {inv.creditedInvoiceNumber}</span>}
            {inv.type === "PURCHASE" && inv.purchaseOrderId && inv.status !== "CANCELLED" && (inv.matchIssueCount > 0
              ? <span className="text-xs font-semibold px-2 py-0.5 rounded bg-amber-100 text-amber-800">⚠ {inv.matchIssueCount} line{inv.matchIssueCount > 1 ? "s" : ""} don&apos;t match the PO</span>
              : <span className="text-xs font-semibold text-emerald-700">✓ Matches the PO and receipts</span>)}
          </div>
        }
        actions={!crediting && <>
          {(inv.status === "DRAFT" || inv.status === "ISSUED") && !isCreditNote && (
            <button disabled={busy} onClick={() => { if (confirm(`Cancel ${inv.invoiceNumber}?`)) cancelMut.mutate(inv.id); }} className={BTN_DANGER}>
              Cancel invoice
            </button>
          )}
          {canCredit && <button disabled={busy} onClick={() => setCrediting(true)} className={BTN_SECONDARY}>Credit note & restock</button>}
          {inv.status === "DRAFT" && <button disabled={busy} onClick={() => issueMut.mutate(inv.id)} className={BTN_PRIMARY}>Issue invoice</button>}
        </>}
      />

      <RecordLayout trail={<>
        {(inv.saleOrderId || inv.purchaseOrderId || inv.creditedInvoiceId) && (
          <TrailCard title="Made from">
            {inv.saleOrderId && <Link href={`/sale-orders/${inv.saleOrderId}`} className="block text-brand-700 hover:underline">Sale order ›</Link>}
            {inv.purchaseOrderId && <Link href={`/purchase-orders/${inv.purchaseOrderId}`} className="block text-brand-700 hover:underline">{inv.purchaseOrderNumber ?? `PO-${inv.purchaseOrderId}`} ›</Link>}
            {inv.fulfilmentId && <p className="text-xs text-gray-500">For shipment #{inv.fulfilmentId}</p>}
            {inv.creditedInvoiceId && (
              <Link href={`/invoices/${inv.creditedInvoiceId}`} className="block text-brand-700 hover:underline">Invoice {inv.creditedInvoiceNumber} ›</Link>
            )}
          </TrailCard>
        )}

        <TrailCard title={isCreditNote ? "Refunds and credit used" : "Payments"}
          badge={inv.status === "CANCELLED" ? <span className="text-[11px] font-semibold text-gray-400">Cancelled</span>
            : inv.status === "DRAFT" ? <span className="text-[11px] font-semibold text-gray-400">Not issued</span>
            : <span className={`text-[11px] font-semibold ${balance > 0.004 ? "text-red-600" : "text-emerald-700"}`}>
                {balance > 0.004 ? `${money(inv, balance)} due` : "Settled"}
              </span>}>
          {inv.payments.length === 0 && <p className="text-xs text-gray-400">None yet</p>}
          {inv.payments.map(p => (
            <div key={p.id} className="flex justify-between gap-2 text-xs">
              <span className="text-gray-600">
                {p.method === "CREDIT_NOTE" ? `Credit note ${p.reference ?? ""}` : p.method} · {new Date(p.paidAt).toLocaleDateString("en-GB")}
                {p.reference && p.method !== "CREDIT_NOTE" && ` · ${p.reference}`}
              </span>
              <span className="text-emerald-700 font-semibold tabular-nums">{Number(p.amount).toFixed(2)}</span>
            </div>
          ))}
          {inv.status === "ISSUED" && !crediting && (
            <div className="border-t border-gray-100 pt-3 space-y-2">
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{isCreditNote ? "Record refund" : "Record payment"}</p>
              <label htmlFor="invoice-pay-amount" className="sr-only">Amount</label>
              <input id="invoice-pay-amount" type="number" step="0.01" min="0.01" value={payAmount}
                onChange={e => setPayAmount(e.target.value)} placeholder={`Amount (due ${balance.toFixed(2)})`}
                className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
              <label htmlFor="invoice-pay-method" className="sr-only">Method</label>
              <select id="invoice-pay-method" value={payMethod} onChange={e => setPayMethod(e.target.value as PaymentMethod)}
                className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm">
                {METHODS.map(m => <option key={m}>{m}</option>)}
              </select>
              <button disabled={!payAmount || busy} onClick={() => payMut.mutate({ amount: payAmount, method: payMethod })}
                className={`w-full ${BTN_PRIMARY}`}>
                {payMut.isPending ? "Saving…" : isCreditNote ? "Record refund" : "Record payment"}
              </button>
            </div>
          )}
        </TrailCard>
      </>}>
        {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

        {crediting && (
          <Card title="Credit note & restock">
            <CreditNoteForm invoice={inv} onCancel={() => setCrediting(false)}
              onDone={cn => {
                setCrediting(false);
                qc.invalidateQueries({ queryKey: ["invoices"] });
                qc.invalidateQueries({ queryKey: ["invoice"] });
                qc.invalidateQueries({ queryKey: ["sale-order"] });
                qc.invalidateQueries({ queryKey: ["stock-overview"] });
                router.push(`/invoices/${cn.id}`);
              }} />
          </Card>
        )}

        <DetailColumns testId={`invoice-details-${inv.id}`} columns={[
          { title: inv.type === "SALES" ? "Customer" : "Supplier", fields: <>
            <Field label="Name" value={inv.partyName} />
            {inv.customerId && <Field label="Account" value={<Link href={`/customers/${inv.customerId}`} className="text-brand-700 hover:underline">Open customer ›</Link>} />}
          </> },
          { title: "Dates", fields: <>
            <Field label="Issued" value={inv.issueDate} />
            <Field label="Due" value={inv.dueDate} warn={inv.status === "ISSUED" && !!inv.dueDate && inv.dueDate < new Date().toISOString().slice(0, 10)} />
          </> },
          { title: "Amounts", fields: <>
            <Field label="Total" value={money(inv, inv.totalAmount)} />
            <Field label={isCreditNote ? "Refunded / used" : "Paid"} value={money(inv, inv.amountPaid)} />
            <Field label="Balance" value={inv.status === "CANCELLED" ? "Cancelled" : money(inv, balance)} warn={balance > 0.004 && inv.status === "ISSUED"} />
          </> },
        ]} />

        <Card title="Items" flush>
          <div className="overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{[["Product", false], ["Quantity", true], ["Price", true], ["Discount", true], ["Tax rule", false], ["Total", true], ["VAT", true],
                  ...(!isCreditNote && inv.type === "SALES" ? [["Credited", true]] : []),
                  ...(inv.type === "PURCHASE" && inv.purchaseOrderId ? [["Match", false]] : [])].map(([h, right]) => (
                  <th key={String(h)} className={`${right ? "text-right" : "text-left"} text-xs font-semibold text-gray-500 uppercase tracking-wide`}>{h}</th>))}</tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {inv.items.map(item => (
                  <tr key={item.id}>
                    <td className="text-gray-800">
                      <Clip text={item.productSku ? `${item.productSku}: ${item.productName ?? item.description ?? ""}` : item.productName ?? item.description} width="22rem" />
                    </td>
                    <td className="num">{Number(item.quantity).toLocaleString()}</td>
                    <td className="num">{Number(item.unitPrice).toFixed(2)}</td>
                    <td className="num text-gray-500">{item.discountPercent ? `${item.discountPercent}%` : "—"}</td>
                    <td className="text-gray-600">{item.taxName ?? "—"}</td>
                    <td className="num">{Number(item.totalPrice).toFixed(2)}</td>
                    <td className="num text-gray-600">{Number(item.taxAmount).toFixed(2)}</td>
                    {!isCreditNote && inv.type === "SALES" && (
                      <td className="num text-gray-500">{Number(item.creditedQuantity) > 0 ? Number(item.creditedQuantity).toLocaleString() : "—"}</td>
                    )}
                    {inv.type === "PURCHASE" && inv.purchaseOrderId && (
                      <td className="text-xs">
                        {!item.purchaseOrderLineId ? <span className="text-gray-400">Charge (expense)</span>
                          : item.matchIssues && item.matchIssues.length > 0
                            ? item.matchIssues.map(m => <span key={m} className="block text-amber-700">⚠ {m}</span>)
                            : <span className="text-emerald-700">✓</span>}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <div className="flex flex-col md:flex-row gap-4 items-start">
          <div className="flex-1 w-full bg-white border border-gray-200 rounded-lg p-4 min-h-24">
            <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Notes</h3>
            <p className="text-sm text-gray-700 whitespace-pre-line">{inv.notes || <span className="text-gray-400">No notes</span>}</p>
          </div>
          <dl data-testid={`invoice-totals-${inv.id}`} className="bg-white border border-gray-200 rounded-lg px-5 py-3 text-sm grid grid-cols-2 gap-x-8 gap-y-1 tabular-nums">
            <dt className="text-gray-500">Before tax</dt><dd className="text-right">{money(inv, inv.subtotal)}</dd>
            <dt className="text-gray-500">VAT</dt><dd className="text-right">{money(inv, inv.taxTotal)}</dd>
            <dt className="font-semibold border-t border-gray-200 pt-1">Total</dt><dd className="text-right font-semibold border-t border-gray-200 pt-1">{money(inv, inv.totalAmount)}</dd>
          </dl>
        </div>
      </RecordLayout>
    </div>
  );
}
