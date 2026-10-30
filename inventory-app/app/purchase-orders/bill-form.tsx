"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { billPurchaseOrder } from "@/lib/api";
import { Invoice, PurchaseOrder } from "@/types";
import { formatGBP } from "@/lib/currency";
import { BTN_PRIMARY, BTN_SECONDARY, TH, TD } from "@/components/DetailParts";

type ChargeForm = { description: string; quantity: string; unitPrice: string };

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const qty = (n: number) => Number(n).toLocaleString();

/**
 * The supplier's bill for a purchase order, as in Cin7: their invoice number, the quantities billed (what was received
 * and not billed yet to start with) at the PO price unless the bill says otherwise, and their extra charges such as
 * shipping, which are an expense. Prices and quantities that differ from the PO and the receipts are flagged.
 */
export default function BillForm({ po, onSaved, onCancel }: { po: PurchaseOrder; onSaved: (bill: Invoice) => void; onCancel: () => void }) {
  const [number, setNumber] = useState("");
  const [issueDate, setIssueDate] = useState(new Date().toISOString().slice(0, 10));
  const [dueDate, setDueDate] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<Record<number, { quantity: string; unitPrice: string }>>(Object.fromEntries(po.lines.map(l => {
    const received = Math.max(Number(l.receivedQuantity) - Number(l.billedQuantity), 0);
    return [l.id, { quantity: received > 0 ? String(received) : "", unitPrice: l.unitCost != null ? String(l.unitCost) : "" }];
  })));
  const [charges, setCharges] = useState<ChargeForm[]>([]);
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: (draft: boolean) => billPurchaseOrder({ id: po.id, data: {
      supplierInvoiceNumber: number.trim(), issueDate: issueDate || undefined, dueDate: dueDate || undefined, notes: notes.trim() || undefined, draft,
      lines: po.lines.map(l => ({ purchaseOrderLineId: l.id, quantity: Number(lines[l.id]?.quantity || 0), unitPrice: lines[l.id]?.unitPrice === "" ? undefined : Number(lines[l.id]?.unitPrice) }))
        .filter(x => x.quantity > 0),
      charges: charges.filter(c => c.description.trim() && c.unitPrice !== "")
        .map(c => ({ description: c.description.trim(), quantity: Number(c.quantity || 1), unitPrice: Number(c.unitPrice) })),
    }}),
    onSuccess: onSaved,
    onError: (e: any) => setError(e.response?.data?.error || "Could not save the bill"),
  });

  // Figures as the server will work them out (charges' VAT comes from the supplier's rule)
  let net = 0, vat = 0;
  po.lines.forEach(l => {
    const q = Number(lines[l.id]?.quantity || 0), p = Number(lines[l.id]?.unitPrice || 0);
    const n = Math.round(q * p * (100 - Number(l.discountPercent || 0))) / 100;
    net += n; vat += Math.round(n * Number(l.taxRate || 0)) / 100;
  });
  const chargesNet = charges.reduce((t, c) => t + Number(c.quantity || 1) * Number(c.unitPrice || 0), 0);

  return (
    <form data-testid="bill-form" onSubmit={e => {
        e.preventDefault(); setError("");
        save.mutate(((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value === "draft");
      }}
      className="bg-white border-2 border-brand-600 rounded-lg p-4 space-y-4">
      <h3 className="text-sm font-semibold text-gray-900">Enter the supplier&apos;s bill for {po.orderNumber}</h3>
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="flex flex-wrap gap-3">
        <div>
          <label htmlFor="bill-number" className="block text-xs font-medium text-gray-600 mb-1">Supplier&apos;s invoice number *</label>
          <input id="bill-number" value={number} onChange={e => setNumber(e.target.value)} required placeholder="e.g. 006387333" className={`${INPUT} w-48`} />
        </div>
        <div>
          <label htmlFor="bill-date" className="block text-xs font-medium text-gray-600 mb-1">Invoice date</label>
          <input id="bill-date" type="date" value={issueDate} onChange={e => setIssueDate(e.target.value)} className={INPUT} />
        </div>
        <div>
          <label htmlFor="bill-due" className="block text-xs font-medium text-gray-600 mb-1">Due date</label>
          <input id="bill-due" type="date" value={dueDate} onChange={e => setDueDate(e.target.value)} className={INPUT} />
          <p className="text-[11px] text-gray-400 mt-1">Empty = from the terms{po.paymentTerm ? ` (${po.paymentTerm})` : ""}</p>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>{["Product", "Ordered", "Received", "Billed", "Quantity billed", "Price on the bill", ""].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {po.lines.map(l => {
              const v = lines[l.id] ?? { quantity: "", unitPrice: "" };
              const q = Number(v.quantity || 0);
              const priceDiffers = v.unitPrice !== "" && l.unitCost != null && Number(v.unitPrice) !== Number(l.unitCost);
              const aheadOfGoods = q > 0 && Number(l.billedQuantity) + q > Number(l.receivedQuantity);
              return (
                <tr key={l.id}>
                  <td className={TD}><span className="font-mono text-xs text-gray-500">{l.productSku}</span><span className="block text-gray-800">{l.productName}</span></td>
                  <td className={`${TD} tabular-nums`}>{qty(l.quantity)}</td>
                  <td className={`${TD} tabular-nums`}>{qty(l.receivedQuantity)}</td>
                  <td className={`${TD} tabular-nums`}>{qty(l.billedQuantity)}</td>
                  <td className={TD}>
                    <input type="number" min="0" step="any" aria-label={`Billed quantity for ${l.productSku}`} value={v.quantity} placeholder="0"
                      onChange={e => setLines({ ...lines, [l.id]: { ...v, quantity: e.target.value } })} className={`${INPUT} w-24`} />
                  </td>
                  <td className={TD}>
                    <input type="number" min="0" step="any" aria-label={`Bill price for ${l.productSku}`} value={v.unitPrice}
                      onChange={e => setLines({ ...lines, [l.id]: { ...v, unitPrice: e.target.value } })} className={`${INPUT} w-28`} />
                  </td>
                  <td className={`${TD} text-[11px] text-amber-700`}>
                    {priceDiffers && <span className="block">PO price {formatGBP(l.unitCost!)}</span>}
                    {aheadOfGoods && <span className="block">More than received</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Supplier&apos;s charges <span className="font-normal text-gray-400">(shipping, duty… an expense)</span></span>
          <button type="button" onClick={() => setCharges([...charges, { description: "", quantity: "1", unitPrice: "" }])} className="text-xs text-brand-700 hover:underline">+ Add charge</button>
        </div>
        {charges.map((c, i) => (
          <div key={i} className="flex gap-2 mb-2">
            <input aria-label={`Charge ${i + 1} description`} value={c.description} placeholder="e.g. Shipping - Standard"
              onChange={e => setCharges(charges.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} className={`${INPUT} flex-1`} />
            <input aria-label={`Charge ${i + 1} quantity`} type="number" min="0.0001" step="any" value={c.quantity}
              onChange={e => setCharges(charges.map((x, j) => j === i ? { ...x, quantity: e.target.value } : x))} className={`${INPUT} w-20`} />
            <input aria-label={`Charge ${i + 1} price`} type="number" min="0" step="any" value={c.unitPrice} placeholder="£"
              onChange={e => setCharges(charges.map((x, j) => j === i ? { ...x, unitPrice: e.target.value } : x))} className={`${INPUT} w-28`} />
            <button type="button" onClick={() => setCharges(charges.filter((_, j) => j !== i))} className="text-red-400 hover:text-red-600 text-xs">Remove</button>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex-1 min-w-64">
          <label htmlFor="bill-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
          <input id="bill-notes" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional" className={`${INPUT} w-full`} />
        </div>
        <dl data-testid="bill-form-totals" className="text-sm grid grid-cols-2 gap-x-6 gap-y-0.5 tabular-nums">
          <dt className="text-gray-500">Lines before tax</dt><dd className="text-right">{formatGBP(net)}</dd>
          <dt className="text-gray-500">Lines VAT</dt><dd className="text-right">{formatGBP(vat)}</dd>
          {chargesNet > 0 && <><dt className="text-gray-500">Charges before tax</dt><dd className="text-right">{formatGBP(chargesNet)} + VAT</dd></>}
        </dl>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <button type="submit" value="enter" disabled={save.isPending} className={BTN_PRIMARY}>{save.isPending ? "Saving..." : "Enter bill"}</button>
        <button type="submit" value="draft" disabled={save.isPending} className={BTN_SECONDARY}>Save as draft</button>
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-800 px-2 py-1.5">Close</button>
      </div>
    </form>
  );
}
