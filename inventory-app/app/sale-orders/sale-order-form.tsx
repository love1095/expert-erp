"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createSaleOrder, getTaxRates, previewSaleOrder, updateSaleOrder } from "@/lib/api";
import { Customer, SaleOrder, TaxRate, Warehouse } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useAuth } from "@/contexts/AuthContext";
import ProductPicker from "./product-picker";

// productSku / productName: shown in the search box when editing an existing line
type LineForm = { productSku?: string; productName?: string; productId: string; quantity: string; unitPrice: string; discountPercent: string; taxRule: string; taxReason: string };
type ChargeForm = { description: string; quantity: string; unitPrice: string; discountPercent: string; taxRule: string; taxReason: string };

const emptyLine = (): LineForm => ({ productId: "", quantity: "", unitPrice: "", discountPercent: "", taxRule: "", taxReason: "" });
const emptyCharge = (): ChargeForm => ({ description: "", quantity: "1", unitPrice: "", discountPercent: "", taxRule: "", taxReason: "" });
const num = (s: string) => (s.trim() === "" ? undefined : Number(s));
const text = (s: string) => (s.trim() === "" ? undefined : s.trim());

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

/**
 * New sale order. Sales rep, terms, carrier and discount default to the customer's; VAT comes from the product,
 * the customer or the organisation default (docs/business-rules.md §3) — a manager or admin may choose another
 * rate per line, with a reason when it is less VAT. Figures are calculated by the server as the form is filled in.
 */
// With `editing`: changes that quote or draft order (same number); without: a new quote or order.
export default function SaleOrderForm({ customers, warehouses, onCreated, editing, onCancel }: {
  customers: Customer[]; warehouses: Warehouse[];
  onCreated: (order: SaleOrder) => void;
  editing?: SaleOrder;
  onCancel?: () => void;
}) {
  const { user } = useAuth();
  const mayChangeVat = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "SALE"], queryFn: () => getTaxRates("SALE") });

  const str = (v: unknown) => (v == null ? "" : String(v));
  const [customerName, setCustomerName] = useState(editing?.customerName ?? "");
  const [warehouseId, setWarehouseId] = useState(str(editing?.warehouseId));
  const [header, setHeader] = useState({
    customerReference: str(editing?.customerReference), salesRep: str(editing?.salesRep), paymentTerm: str(editing?.paymentTerm),
    shipBy: str(editing?.shipBy), carrier: str(editing?.carrier), shippingNotes: str(editing?.shippingNotes), notes: str(editing?.notes),
  });
  const [quoteValidUntil, setQuoteValidUntil] = useState(str(editing?.quoteValidUntil));
  const [lines, setLines] = useState<LineForm[]>(editing ? editing.lines.map(l => ({
    productSku: l.productSku, productName: l.productName, productId: String(l.productId), quantity: str(l.quantity),
    unitPrice: str(l.unitPrice), discountPercent: str(l.discountPercent), taxRule: l.taxOverridden ? str(l.taxName) : "", taxReason: "",
  })) : [emptyLine()]);
  const [charges, setCharges] = useState<ChargeForm[]>(editing ? editing.charges.map(c => ({
    description: c.description, quantity: str(c.quantity), unitPrice: str(c.unitPrice), discountPercent: str(c.discountPercent),
    taxRule: c.taxOverridden ? str(c.taxName) : "", taxReason: "",
  })) : []);
  const [error, setError] = useState("");

  const existing = customers.find(c => c.name.toLowerCase() === customerName.trim().toLowerCase());
  const request = {
    customerId: existing?.id, customerName: existing ? undefined : customerName.trim(),
    warehouseId: warehouseId ? Number(warehouseId) : undefined,
    customerReference: text(header.customerReference), salesRep: text(header.salesRep), paymentTerm: text(header.paymentTerm),
    shipBy: text(header.shipBy), carrier: text(header.carrier), shippingNotes: text(header.shippingNotes), notes: text(header.notes),
    lines: lines.map(l => ({
      productId: l.productId ? Number(l.productId) : undefined, quantity: num(l.quantity), unitPrice: num(l.unitPrice) ?? null,
      discountPercent: num(l.discountPercent), taxRule: text(l.taxRule), taxReason: text(l.taxReason),
    })),
    charges: charges.map(c => ({
      description: c.description.trim(), quantity: num(c.quantity) ?? 1, unitPrice: num(c.unitPrice),
      discountPercent: num(c.discountPercent), taxRule: text(c.taxRule), taxReason: text(c.taxReason),
    })),
  };

  // Server-side figures for what is filled in so far, recalculated shortly after each change
  const requestKey = JSON.stringify(request);
  const [preview, setPreview] = useState<SaleOrder | null>(null);
  const [previewError, setPreviewError] = useState("");
  useEffect(() => {
    const t = setTimeout(() => {
      previewSaleOrder(JSON.parse(requestKey))
        .then((p: SaleOrder) => { setPreview(p); setPreviewError(""); })
        .catch((e: any) => setPreviewError(e.response?.data?.error || ""));
    }, 350);
    return () => clearTimeout(t);
  }, [requestKey]);

  // Preview lines only hold the complete form lines, in order
  const previewLineFor = (i: number) => {
    const complete = lines.slice(0, i + 1).filter(l => l.productId && Number(l.quantity) > 0).length;
    const isComplete = lines[i].productId && Number(lines[i].quantity) > 0;
    return isComplete ? preview?.lines[complete - 1] : undefined;
  };
  const previewChargeFor = (i: number) => {
    const isComplete = charges[i].description.trim() && charges[i].unitPrice.trim() !== "";
    const complete = charges.slice(0, i + 1).filter(c => c.description.trim() && c.unitPrice.trim() !== "").length;
    return isComplete ? preview?.charges[complete - 1] : undefined;
  };

  const save = useMutation({
    mutationFn: ({ asQuote }: { asQuote: boolean }) => {
      const data = { ...request, quote: asQuote, quoteValidUntil: asQuote ? text(quoteValidUntil) : undefined };
      return editing ? updateSaleOrder({ id: editing.id, data }) : createSaleOrder(data);
    },
    onSuccess: (so: SaleOrder) => onCreated(so),
    onError: (e: any) => setError(e.response?.data?.error || "Could not save"),
  });
  const isQuote = editing?.status === "QUOTE";

  const setLine = (i: number, patch: Partial<LineForm>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const setCharge = (i: number, patch: Partial<ChargeForm>) => setCharges(charges.map((c, j) => (j === i ? { ...c, ...patch } : c)));

  const vatSelect = (value: string, auto: string | undefined, onChange: (v: string) => void, label: string) => (
    <select aria-label={label} value={value} onChange={e => onChange(e.target.value)} disabled={!mayChangeVat}
      title={mayChangeVat ? undefined : "Only a manager or admin can change the VAT"}
      className={`${INPUT} w-full disabled:bg-gray-50 disabled:text-gray-500`}>
      <option value="">{auto ? `Auto: ${auto}` : "Auto"}</option>
      {rates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}
    </select>
  );

  return (
    <form onSubmit={e => {
        e.preventDefault(); setError("");
        // Which button was pressed: "Create quote" or "Create sale order" (editing keeps the stage)
        const as = ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value;
        save.mutate({ asQuote: editing ? isQuote : as === "quote" });
      }}
      className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4" data-testid="sale-order-form">
      <h2 className="font-semibold text-gray-800">
        {editing ? `Edit ${isQuote ? "quote" : "order"} ${editing.orderNumber}` : "New quote or sale order"}
      </h2>
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="col-span-2">
          <label htmlFor="sale-orders-customer" className={LABEL}>Customer *</label>
          <input id="sale-orders-customer" list="sale-orders-customer-list" value={customerName} onChange={e => setCustomerName(e.target.value)} required
            placeholder="Pick a customer or type a new name" autoComplete="off" className={`${INPUT} w-full`} />
          <datalist id="sale-orders-customer-list">
            {customers.filter(c => c.status === "ACTIVE").map(c => <option key={c.id} value={c.name}>{c.contacts[0]?.email ?? ""}</option>)}
          </datalist>
          {customerName.trim() && !existing && <p className="text-[11px] text-gray-400 mt-1">New customer — will be added to Customers</p>}
        </div>
        <div>
          <label htmlFor="sale-orders-ship-from-warehouse" className={LABEL}>Ship from Warehouse *</label>
          <select id="sale-orders-ship-from-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required className={`${INPUT} w-full`}>
            <option value="">Select warehouse</option>
            {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="sale-orders-reference" className={LABEL}>Customer reference</label>
          <input id="sale-orders-reference" value={header.customerReference} onChange={e => setHeader({ ...header, customerReference: e.target.value })}
            placeholder="Their PO number" autoComplete="off" className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="sale-orders-rep" className={LABEL}>Sales rep</label>
          <input id="sale-orders-rep" value={header.salesRep} onChange={e => setHeader({ ...header, salesRep: e.target.value })}
            placeholder={preview?.salesRep ? `${preview.salesRep} (customer's)` : "None"} autoComplete="off" className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="sale-orders-terms" className={LABEL}>Terms</label>
          <input id="sale-orders-terms" value={header.paymentTerm} onChange={e => setHeader({ ...header, paymentTerm: e.target.value })}
            placeholder={preview?.paymentTerm ? `${preview.paymentTerm} (customer's)` : "None"} autoComplete="off" className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="sale-orders-ship-by" className={LABEL}>Ship by</label>
          <input id="sale-orders-ship-by" type="date" value={header.shipBy} onChange={e => setHeader({ ...header, shipBy: e.target.value })} className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="sale-orders-carrier" className={LABEL}>Carrier</label>
          <input id="sale-orders-carrier" value={header.carrier} onChange={e => setHeader({ ...header, carrier: e.target.value })}
            placeholder={preview?.carrier ? `${preview.carrier} (customer's)` : "None"} autoComplete="off" className={`${INPUT} w-full`} />
        </div>
        {(!editing || isQuote) && (
          <div>
            <label htmlFor="sale-orders-valid-until" className={LABEL}>Quote valid until</label>
            <input id="sale-orders-valid-until" type="date" value={quoteValidUntil} onChange={e => setQuoteValidUntil(e.target.value)} className={`${INPUT} w-full`} />
            {!editing && <p className="text-[11px] text-gray-400 mt-1">Quotes only; empty = the organisation&apos;s default</p>}
          </div>
        )}
        <div className="col-span-2 lg:col-span-4">
          <label htmlFor="sale-orders-shipping-notes" className={LABEL}>Shipping notes</label>
          <input id="sale-orders-shipping-notes" value={header.shippingNotes} onChange={e => setHeader({ ...header, shippingNotes: e.target.value })}
            placeholder="Optional" autoComplete="off" className={`${INPUT} w-full`} />
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Lines *</span>
          <button type="button" onClick={() => setLines([...lines, emptyLine()])} className="text-xs text-brand-600 hover:text-brand-800">+ Add line</button>
        </div>
        <div className="grid grid-cols-12 gap-2 mb-1 text-[11px] text-gray-400">
          <span className="col-span-4">Product</span><span>Qty</span><span>£ price</span>
          <span>Disc %</span><span className="col-span-2">VAT</span><span className="text-right">Net</span><span className="text-right">VAT £</span><span />
        </div>
        {lines.map((l, i) => {
          const p = previewLineFor(i);
          return (
            <div key={i} className="mb-2" data-testid={`so-form-line-${i}`}>
              <div className="grid grid-cols-12 gap-2 items-center">
                <div className="col-span-4">
                  {/* Picking a product fills in its shop price when no price is typed yet */}
                  <ProductPicker label={`Product for line ${i + 1}`}
                    selected={l.productSku ? { id: Number(l.productId), sku: l.productSku, name: l.productName ?? "" } : undefined}
                    onSelect={pr => setLine(i, { productId: String(pr.id), unitPrice: l.unitPrice || (pr.sellPrice != null ? String(pr.sellPrice) : "") })} />
                </div>
                <input aria-label={`Quantity for line ${i + 1}`} type="number" min="0.0001" step="any" value={l.quantity} placeholder="Qty" required
                  onChange={e => setLine(i, { quantity: e.target.value })} className={INPUT} />
                <input aria-label={`Unit price for line ${i + 1}`} type="number" min="0" step="any" value={l.unitPrice} placeholder="£"
                  onChange={e => setLine(i, { unitPrice: e.target.value })} className={INPUT} />
                <input aria-label={`Discount for line ${i + 1}`} type="number" min="0" max="100" step="any" value={l.discountPercent}
                  placeholder={p ? String(p.discountPercent) : "%"} onChange={e => setLine(i, { discountPercent: e.target.value })} className={INPUT} />
                <div className="col-span-2">{vatSelect(l.taxRule, p && !p.taxOverridden ? p.taxName : undefined, v => setLine(i, { taxRule: v }), `VAT for line ${i + 1}`)}</div>
                <span className="text-right text-sm tabular-nums text-gray-700">{p ? formatGBP(p.netAmount) : "—"}</span>
                <span className="text-right text-sm tabular-nums text-gray-500">{p ? formatGBP(p.taxAmount) : "—"}</span>
                <button type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))} className="text-red-400 hover:text-red-600 text-xs">Remove</button>
              </div>
              {l.taxRule && (
                <input aria-label={`VAT reason for line ${i + 1}`} value={l.taxReason} onChange={e => setLine(i, { taxReason: e.target.value })}
                  placeholder="Reason for changing the VAT (needed when it is less VAT)" className={`${INPUT} w-full mt-1`} />
              )}
            </div>
          );
        })}
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Additional charges</span>
          <button type="button" onClick={() => setCharges([...charges, emptyCharge()])} className="text-xs text-brand-600 hover:text-brand-800">+ Add charge</button>
        </div>
        {charges.length === 0 && <p className="text-xs text-gray-400">Carriage, delivery… none yet.</p>}
        {charges.map((c, i) => {
          const p = previewChargeFor(i);
          return (
            <div key={i} className="mb-2" data-testid={`so-form-charge-${i}`}>
              <div className="grid grid-cols-12 gap-2 items-center">
                <input aria-label={`Charge ${i + 1} description`} value={c.description} onChange={e => setCharge(i, { description: e.target.value })} required
                  placeholder="e.g. Carriage" className={`${INPUT} col-span-4`} />
                <input aria-label={`Charge ${i + 1} quantity`} type="number" min="0.0001" step="any" value={c.quantity} onChange={e => setCharge(i, { quantity: e.target.value })} className={INPUT} />
                <input aria-label={`Charge ${i + 1} price`} type="number" min="0" step="any" value={c.unitPrice} placeholder="£" required onChange={e => setCharge(i, { unitPrice: e.target.value })} className={INPUT} />
                <input aria-label={`Charge ${i + 1} discount`} type="number" min="0" max="100" step="any" value={c.discountPercent} placeholder="%" onChange={e => setCharge(i, { discountPercent: e.target.value })} className={INPUT} />
                <div className="col-span-2">{vatSelect(c.taxRule, p && !p.taxOverridden ? p.taxName : undefined, v => setCharge(i, { taxRule: v }), `VAT for charge ${i + 1}`)}</div>
                <span className="text-right text-sm tabular-nums text-gray-700">{p ? formatGBP(p.netAmount) : "—"}</span>
                <span className="text-right text-sm tabular-nums text-gray-500">{p ? formatGBP(p.taxAmount) : "—"}</span>
                <button type="button" onClick={() => setCharges(charges.filter((_, j) => j !== i))} className="text-red-400 hover:text-red-600 text-xs">Remove</button>
              </div>
              {c.taxRule && (
                <input aria-label={`VAT reason for charge ${i + 1}`} value={c.taxReason} onChange={e => setCharge(i, { taxReason: e.target.value })}
                  placeholder="Reason for changing the VAT (needed when it is less VAT)" className={`${INPUT} w-full mt-1`} />
              )}
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex-1 min-w-64">
          <label htmlFor="sale-orders-notes" className={LABEL}>Notes</label>
          <input id="sale-orders-notes" value={header.notes} onChange={e => setHeader({ ...header, notes: e.target.value })} placeholder="Optional" className={`${INPUT} w-full`} />
        </div>
        <dl data-testid="so-form-totals" className="text-sm grid grid-cols-2 gap-x-6 gap-y-0.5 tabular-nums">
          <dt className="text-gray-500">Before tax</dt><dd className="text-right">{formatGBP(preview?.subtotal ?? 0)}</dd>
          <dt className="text-gray-500">VAT</dt><dd className="text-right">{formatGBP(preview?.taxTotal ?? 0)}</dd>
          <dt className="font-semibold">Total</dt><dd className="text-right font-semibold">{formatGBP(preview?.total ?? 0)}</dd>
        </dl>
      </div>
      {previewError && <p className="text-xs text-amber-700">{previewError}</p>}

      <div className="flex flex-wrap items-center gap-2">
        {editing ? (
          <>
            <button type="submit" disabled={save.isPending}
              className="bg-brand-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              {save.isPending ? "Saving..." : "Save changes"}
            </button>
            {onCancel && <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>}
          </>
        ) : (
          <>
            <button type="submit" value="order" disabled={save.isPending}
              className="bg-brand-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              {save.isPending ? "Saving..." : "Create Sale Order"}
            </button>
            <button type="submit" value="quote" disabled={save.isPending}
              className="border border-violet-300 text-violet-700 px-5 py-2 rounded-lg text-sm font-medium hover:bg-violet-50 disabled:opacity-50">
              Create quote
            </button>
            <span className="text-xs text-gray-400">A quote reserves no stock and becomes the order when the customer accepts it.</span>
          </>
        )}
      </div>
    </form>
  );
}
