"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createPurchaseOrder, getSuppliers, getTaxRates, previewPurchaseOrder, updatePurchaseOrder } from "@/lib/api";
import { PurchaseOrder, Supplier, TaxRate, Warehouse } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useAuth } from "@/contexts/AuthContext";
import ProductPicker from "../sale-orders/product-picker";

// productSku / productName: shown in the search box when editing an existing line
type LineForm = { productSku?: string; productName?: string; productId: string; quantity: string; unitCost: string; discountPercent: string; supplierSku: string; taxRule: string; taxReason: string };
type CostForm = { description: string; reference: string; quantity: string; unitPrice: string; taxRule: string; taxReason: string };

const emptyLine = (): LineForm => ({ productId: "", quantity: "", unitCost: "", discountPercent: "", supplierSku: "", taxRule: "", taxReason: "" });
const emptyCost = (): CostForm => ({ description: "", reference: "", quantity: "1", unitPrice: "", taxRule: "", taxReason: "" });
const num = (s: string) => (s.trim() === "" ? undefined : Number(s));
const text = (s: string) => (s.trim() === "" ? undefined : s.trim());

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

/**
 * New purchase order, or changes to a draft. Terms come from the supplier; each line's supplier code and cost come
 * from the product (its own supplier's price, else its cost price) and VAT from the product, the supplier or the
 * organisation default — a manager or admin may choose another rate. Figures are calculated by the server as the form is filled in.
 */
export default function PurchaseOrderForm({ warehouses, onSaved, editing, onCancel }: {
  warehouses: Warehouse[];
  onSaved: (po: PurchaseOrder) => void;
  editing?: PurchaseOrder;
  onCancel?: () => void;
}) {
  const { user } = useAuth();
  const mayChangeVat = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "PURCHASE"], queryFn: () => getTaxRates("PURCHASE") });
  const { data: suppliers = [] } = useQuery<Supplier[]>({ queryKey: ["suppliers"], queryFn: getSuppliers });

  const str = (v: unknown) => (v == null ? "" : String(v));
  const [supplierName, setSupplierName] = useState(editing?.supplierName ?? "");
  const [warehouseId, setWarehouseId] = useState(str(editing?.warehouseId ?? (warehouses.length === 1 ? warehouses[0].id : "")));
  const [header, setHeader] = useState({
    supplierReference: str(editing?.supplierReference), paymentTerm: str(editing?.paymentTerm),
    requiredBy: str(editing?.requiredBy), notes: str(editing?.notes),
  });
  const [lines, setLines] = useState<LineForm[]>(editing ? editing.lines.map(l => ({
    productSku: l.productSku, productName: l.productName, productId: String(l.productId), quantity: str(l.quantity),
    unitCost: str(l.unitCost), discountPercent: str(l.discountPercent), supplierSku: str(l.supplierSku),
    taxRule: l.taxOverridden ? str(l.taxName) : "", taxReason: "",
  })) : [emptyLine()]);
  const [costs, setCosts] = useState<CostForm[]>(editing ? editing.costs.map(c => ({
    description: c.description, reference: str(c.reference), quantity: str(c.quantity), unitPrice: str(c.unitPrice), taxRule: "", taxReason: "",
  })) : []);
  const [error, setError] = useState("");

  const supplier = suppliers.find(s => s.name.toLowerCase() === supplierName.trim().toLowerCase());
  const request = {
    supplierId: supplier?.id ?? editing?.supplierId,
    supplierName: supplier ? undefined : text(supplierName),
    warehouseId: warehouseId ? Number(warehouseId) : undefined,
    supplierReference: text(header.supplierReference), paymentTerm: text(header.paymentTerm),
    requiredBy: text(header.requiredBy), notes: text(header.notes),
    lines: lines.map(l => ({
      productId: l.productId ? Number(l.productId) : undefined, quantity: num(l.quantity), unitCost: num(l.unitCost) ?? null,
      discountPercent: num(l.discountPercent), supplierSku: text(l.supplierSku), taxRule: text(l.taxRule), taxReason: text(l.taxReason),
    })),
    costs: costs.map(c => ({
      description: c.description.trim(), reference: text(c.reference), quantity: num(c.quantity) ?? 1, unitPrice: num(c.unitPrice),
      taxRule: text(c.taxRule), taxReason: text(c.taxReason),
    })),
  };

  // Server-side figures for what is filled in so far, recalculated shortly after each change
  const requestKey = JSON.stringify(request);
  const [preview, setPreview] = useState<PurchaseOrder | null>(null);
  const [previewError, setPreviewError] = useState("");
  useEffect(() => {
    const t = setTimeout(() => {
      previewPurchaseOrder(JSON.parse(requestKey))
        .then((p: PurchaseOrder) => { setPreview(p); setPreviewError(""); })
        .catch((e: any) => setPreviewError(e.response?.data?.error || ""));
    }, 350);
    return () => clearTimeout(t);
  }, [requestKey]);

  // Preview lines only hold the complete form lines, in order
  const previewLineFor = (i: number) => {
    const complete = lines.slice(0, i + 1).filter(l => l.productId && Number(l.quantity) > 0).length;
    return lines[i].productId && Number(lines[i].quantity) > 0 ? preview?.lines[complete - 1] : undefined;
  };
  const previewCostFor = (i: number) => {
    const done = (c: CostForm) => c.description.trim() && c.unitPrice.trim() !== "";
    return done(costs[i]) ? preview?.costs[costs.slice(0, i + 1).filter(done).length - 1] : undefined;
  };

  const save = useMutation({
    mutationFn: ({ ordered }: { ordered: boolean }) => {
      const data = { ...request, ordered };
      return editing ? updatePurchaseOrder({ id: editing.id, data }) : createPurchaseOrder(data);
    },
    onSuccess: (po: PurchaseOrder) => onSaved(po),
    onError: (e: any) => setError(e.response?.data?.error || "Could not save"),
  });

  const setLine = (i: number, patch: Partial<LineForm>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const setCost = (i: number, patch: Partial<CostForm>) => setCosts(costs.map((c, j) => (j === i ? { ...c, ...patch } : c)));

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
        const as = ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value;
        save.mutate({ ordered: as === "ordered" });
      }}
      className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4" data-testid="purchase-order-form">
      <h2 className="font-semibold text-gray-800">{editing ? `Edit draft ${editing.orderNumber}` : "New purchase order"}</h2>
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="col-span-2">
          <label htmlFor="po-supplier" className={LABEL}>Supplier *</label>
          <input id="po-supplier" list="po-supplier-list" value={supplierName} onChange={e => setSupplierName(e.target.value)} required
            placeholder="Pick a supplier" autoComplete="off" className={`${INPUT} w-full`} />
          <datalist id="po-supplier-list">
            {suppliers.map(s => <option key={s.id} value={s.name} />)}
          </datalist>
          {supplierName.trim() && !supplier && !editing && (
            <p className="text-[11px] text-amber-700 mt-1">Not a supplier on file: terms, codes and prices won&apos;t be filled in</p>
          )}
        </div>
        <div>
          <label htmlFor="po-warehouse" className={LABEL}>Deliver to *</label>
          <select id="po-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required className={`${INPUT} w-full`}>
            <option value="">Select warehouse</option>
            {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="po-required-by" className={LABEL}>Required by</label>
          <input id="po-required-by" type="date" value={header.requiredBy} onChange={e => setHeader({ ...header, requiredBy: e.target.value })} className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="po-terms" className={LABEL}>Terms</label>
          <input id="po-terms" value={header.paymentTerm} onChange={e => setHeader({ ...header, paymentTerm: e.target.value })}
            placeholder={preview?.paymentTerm ? `${preview.paymentTerm} (supplier's)` : "None"} autoComplete="off" className={`${INPUT} w-full`} />
        </div>
        <div>
          <label htmlFor="po-supplier-reference" className={LABEL}>Supplier reference</label>
          <input id="po-supplier-reference" value={header.supplierReference} onChange={e => setHeader({ ...header, supplierReference: e.target.value })}
            placeholder="Their order confirmation" autoComplete="off" className={`${INPUT} w-full`} />
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Lines *</span>
          <button type="button" onClick={() => setLines([...lines, emptyLine()])} className="text-xs text-brand-700 hover:underline">+ Add line</button>
        </div>
        <div className="grid grid-cols-12 gap-2 mb-1 text-[11px] text-gray-400">
          <span className="col-span-3">Product</span><span>Supplier SKU</span><span>Qty</span><span>£ cost</span>
          <span>Disc %</span><span className="col-span-2">VAT</span><span className="text-right">Net</span><span className="text-right">VAT £</span><span />
        </div>
        {lines.map((l, i) => {
          const p = previewLineFor(i);
          return (
            <div key={i} className="mb-2" data-testid={`po-form-line-${i}`}>
              <div className="grid grid-cols-12 gap-2 items-center">
                <div className="col-span-3">
                  <ProductPicker label={`Product for line ${i + 1}`}
                    selected={l.productSku ? { id: Number(l.productId), sku: l.productSku, name: l.productName ?? "" } : undefined}
                    onSelect={pr => setLine(i, { productId: String(pr.id) })} />
                </div>
                <input aria-label={`Supplier SKU for line ${i + 1}`} value={l.supplierSku} placeholder={p?.supplierSku ?? "—"}
                  onChange={e => setLine(i, { supplierSku: e.target.value })} className={INPUT} />
                <input aria-label={`Quantity for line ${i + 1}`} type="number" min="0.0001" step="any" value={l.quantity} placeholder="Qty" required
                  onChange={e => setLine(i, { quantity: e.target.value })} className={INPUT} />
                <input aria-label={`Unit cost for line ${i + 1}`} type="number" min="0" step="any" value={l.unitCost}
                  placeholder={p?.unitCost != null ? String(p.unitCost) : "£"} onChange={e => setLine(i, { unitCost: e.target.value })} className={INPUT} />
                <input aria-label={`Discount for line ${i + 1}`} type="number" min="0" max="100" step="any" value={l.discountPercent}
                  placeholder="%" onChange={e => setLine(i, { discountPercent: e.target.value })} className={INPUT} />
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
          <span className="text-xs font-medium text-gray-600">Additional costs <span className="font-normal text-gray-400">(an expense, not stock cost)</span></span>
          <button type="button" onClick={() => setCosts([...costs, emptyCost()])} className="text-xs text-brand-700 hover:underline">+ Add cost</button>
        </div>
        {costs.length === 0 && <p className="text-xs text-gray-400">Freight, duty… none yet.</p>}
        {costs.map((c, i) => {
          const p = previewCostFor(i);
          return (
            <div key={i} className="grid grid-cols-12 gap-2 items-center mb-2" data-testid={`po-form-cost-${i}`}>
              <input aria-label={`Cost ${i + 1} description`} value={c.description} onChange={e => setCost(i, { description: e.target.value })} required
                placeholder="e.g. Freight" className={`${INPUT} col-span-3`} />
              <input aria-label={`Cost ${i + 1} reference`} value={c.reference} onChange={e => setCost(i, { reference: e.target.value })}
                placeholder="Reference" className={INPUT} />
              <input aria-label={`Cost ${i + 1} quantity`} type="number" min="0.0001" step="any" value={c.quantity} onChange={e => setCost(i, { quantity: e.target.value })} className={INPUT} />
              <input aria-label={`Cost ${i + 1} price`} type="number" min="0" step="any" value={c.unitPrice} placeholder="£" required onChange={e => setCost(i, { unitPrice: e.target.value })} className={INPUT} />
              <span />
              <div className="col-span-2">{vatSelect(c.taxRule, p?.taxName, v => setCost(i, { taxRule: v }), `VAT for cost ${i + 1}`)}</div>
              <span className="text-right text-sm tabular-nums text-gray-700">{p ? formatGBP(p.netAmount) : "—"}</span>
              <span className="text-right text-sm tabular-nums text-gray-500">{p ? formatGBP(p.taxAmount) : "—"}</span>
              <button type="button" onClick={() => setCosts(costs.filter((_, j) => j !== i))} className="text-red-400 hover:text-red-600 text-xs">Remove</button>
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex-1 min-w-64">
          <label htmlFor="po-notes" className={LABEL}>Memo</label>
          <input id="po-notes" value={header.notes} onChange={e => setHeader({ ...header, notes: e.target.value })} placeholder="Optional" className={`${INPUT} w-full`} />
        </div>
        <dl data-testid="po-form-totals" className="text-sm grid grid-cols-2 gap-x-6 gap-y-0.5 tabular-nums">
          <dt className="text-gray-500">Before tax</dt><dd className="text-right">{formatGBP(preview?.subtotal ?? 0)}</dd>
          <dt className="text-gray-500">VAT</dt><dd className="text-right">{formatGBP(preview?.taxTotal ?? 0)}</dd>
          <dt className="font-semibold">Total</dt><dd className="text-right font-semibold">{formatGBP(preview?.total ?? 0)}</dd>
        </dl>
      </div>
      {previewError && <p className="text-xs text-amber-700">{previewError}</p>}

      <div className="flex flex-wrap items-center gap-2">
        <button type="submit" value="ordered" disabled={save.isPending}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving..." : editing ? "Save & mark ordered" : "Create & mark ordered"}
        </button>
        <button type="submit" value="draft" disabled={save.isPending}
          className="border border-gray-300 bg-white text-gray-700 px-5 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50">
          {editing ? "Save draft" : "Save as draft"}
        </button>
        {onCancel && <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>}
        <span className="text-xs text-gray-400">&quot;Ordered&quot; = sent to the supplier; a draft can still be changed.</span>
      </div>
    </form>
  );
}
