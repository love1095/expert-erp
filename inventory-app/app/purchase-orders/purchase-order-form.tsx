"use client";

import UnitSelect from "@/components/UnitSelect";
import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createPurchaseOrder, getSuppliers, getTaxRates, previewPurchaseOrder, updatePurchaseOrder, getPaymentTerms } from "@/lib/api";
import { PostalAddress, PurchaseOrder, Supplier, TaxRate, Warehouse } from "@/types";
import { AddressFields, EMPTY_ADDRESS, isBlankAddress } from "@/components/AddressFields";
import { NewSupplierDialog, PaymentTermDialog, WithAdd } from "@/components/QuickAdd";
import { formatGBP } from "@/lib/currency";
import { useAuth } from "@/contexts/AuthContext";
import ProductPicker from "../sale-orders/product-picker";
import NameCombobox from "@/components/NameCombobox";
import { Section, StickyActions, ADD_BUTTON } from "@/components/ui/Section";
import { useConfirm } from "@/components/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { todayIso } from "@/lib/dates";

// productSku / productName: shown in the search box when editing an existing line
type LineForm = { productSku?: string; productName?: string; productId: string; unitName?: string; quantity: string; unitCost: string; discountPercent: string; supplierSku: string; taxRule: string; taxReason: string };
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
  const toast = useToast();
  const ask = useConfirm();
  const { user } = useAuth();
  const mayChangeVat = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "PURCHASE"], queryFn: () => getTaxRates("PURCHASE") });
  const { data: suppliers = [] } = useQuery<Supplier[]>({ queryKey: ["suppliers"], queryFn: getSuppliers });
  // Like Cin7: terms are picked from a list, each with what it means for the due date
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });

  const str = (v: unknown) => (v == null ? "" : String(v));
  const [supplierName, setSupplierName] = useState(editing?.supplierName ?? "");
  const [warehouseId, setWarehouseId] = useState(str(editing?.warehouseId ?? (warehouses.length === 1 ? warehouses[0].id : "")));
  const [header, setHeader] = useState({
    supplierReference: str(editing?.supplierReference), paymentTerm: str(editing?.paymentTerm),
    // A new purchase order starts on today's date (the owner's rule for date boxes on creation pages)
    requiredBy: editing ? str(editing.requiredBy) : todayIso(), notes: str(editing?.notes),
  });
  const [lines, setLines] = useState<LineForm[]>(editing ? editing.lines.map(l => ({
    productSku: l.productSku, productName: l.productName, productId: String(l.productId), unitName: l.unitName ?? "", quantity: str(l.quantity),
    unitCost: str(l.unitCost), discountPercent: str(l.discountPercent), supplierSku: str(l.supplierSku),
    taxRule: l.taxOverridden ? str(l.taxName) : "", taxReason: "",
  })) : [emptyLine()]);
  const [costs, setCosts] = useState<CostForm[]>(editing ? editing.costs.map(c => ({
    description: c.description, reference: str(c.reference), quantity: str(c.quantity), unitPrice: str(c.unitPrice), taxRule: "", taxReason: "",
  })) : []);
  const [error, setError] = useState("");
  // Like Cin7's New purchase: supplier, accounting and shipping details
  const addr = (a?: PostalAddress | null): PostalAddress => ({ ...EMPTY_ADDRESS, ...(a ?? {}) });
  const warehouseAddress = (id: string) => addr(warehouses.find(w => String(w.id) === id)?.address);
  const [details, setDetails] = useState({
    contactName: str(editing?.contactName), contactPhone: str(editing?.contactPhone),
    orderDate: editing ? str(editing.orderDate) : todayIso(),
    taxRule: str(editing?.taxRule), taxReason: "", blindReceipt: editing?.blindReceipt ?? false,
    shipToDifferent: !!editing?.shipToCompany, shipToCompany: str(editing?.shipToCompany),
  });
  const [vendorAddress, setVendorAddress] = useState<PostalAddress>(addr(editing?.vendorAddress));
  const [shippingAddress, setShippingAddress] = useState<PostalAddress>(editing ? addr(editing.shippingAddress) : warehouseAddress(warehouseId));
  // The "+" pop-ups
  const [adding, setAdding] = useState<"supplier" | "term" | null>(null);
  const [added, setAdded] = useState<Supplier | null>(null);

  // Picking a supplier fills its contact, phone and address (they can still be changed on the order)
  const pickSupplier = (name: string, list: Supplier[] = suppliers) => {
    setSupplierName(name);
    const s = list.find(x => x.name.toLowerCase() === name.trim().toLowerCase());
    if (s) {
      setDetails(d => ({ ...d, contactName: s.contactName ?? "", contactPhone: s.phone ?? "" }));
      setVendorAddress(addr(s.address));
    }
  };
  const pickWarehouse = (id: string) => {
    setWarehouseId(id);
    if (!details.shipToDifferent) setShippingAddress(warehouseAddress(id));
  };

  const supplier = (added && added.name.toLowerCase() === supplierName.trim().toLowerCase() ? added : undefined) ?? suppliers.find(s => s.name.toLowerCase() === supplierName.trim().toLowerCase());
  const request = {
    supplierId: supplier?.id ?? editing?.supplierId,
    supplierName: supplier ? undefined : text(supplierName),
    warehouseId: warehouseId ? Number(warehouseId) : undefined,
    supplierReference: text(header.supplierReference), paymentTerm: text(header.paymentTerm),
    requiredBy: text(header.requiredBy), notes: text(header.notes),
    contactName: text(details.contactName), contactPhone: text(details.contactPhone),
    vendorAddress: isBlankAddress(vendorAddress) ? undefined : vendorAddress,
    orderDate: text(details.orderDate), taxRule: text(details.taxRule), taxReason: text(details.taxReason),
    blindReceipt: details.blindReceipt,
    shipToCompany: details.shipToDifferent ? text(details.shipToCompany) : undefined,
    shippingAddress: isBlankAddress(shippingAddress) ? undefined : shippingAddress,
    lines: lines.map(l => ({
      productId: l.productId ? Number(l.productId) : undefined, unitName: text(l.unitName ?? ""), quantity: num(l.quantity), unitCost: num(l.unitCost) ?? null,
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
    onSuccess: (po: PurchaseOrder) => { toast(editing ? "Purchase order saved" : "Purchase order created"); onSaved(po); },
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
    <form onSubmit={async e => {
        e.preventDefault(); setError("");
        const as = ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value;
        // Placing the order with the supplier asks first (review R7); a plain save does not
        if (as === "ordered" && !(await ask({ title: "Mark this PO as ordered?", message: "It is placed with the supplier.", action: "Mark as ordered" }))) return;
        save.mutate({ ordered: as === "ordered" });
      }}
      className="space-y-5" data-testid="purchase-order-form">
      {editing && <p className="text-sm text-gray-600">Editing draft <b>{editing.orderNumber}</b></p>}
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      {/* Like Cin7's New purchase: Supplier details · Accounting details · Shipping details, then Comments */}
      <div className="grid gap-5 lg:grid-cols-3 items-start">
        <Section title="Supplier details" testId="po-form-supplier">
          <div className="space-y-3">
            <div>
              <label htmlFor="po-supplier" className={LABEL}>Supplier *</label>
              <WithAdd label="New supplier" onAdd={() => setAdding("supplier")}>
                <NameCombobox id="po-supplier" value={supplierName} onChange={v => pickSupplier(v)} required listLabel="Suppliers"
                  placeholder="Pick a supplier" className={`${INPUT} w-full`}
                  options={suppliers.map(s => ({ key: s.id, name: s.name, sub: [s.contactName, s.email].filter(Boolean).join(" · ") }))} />
              </WithAdd>
              {supplierName.trim() && !supplier && !editing && (
                <p className="text-[11px] text-amber-700 mt-1">Not a supplier on file: use + to add it with its terms and address</p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="po-contact" className={LABEL}>Contact</label>
                <input id="po-contact" value={details.contactName} onChange={e => setDetails({ ...details, contactName: e.target.value })}
                  placeholder={supplier?.contactName ?? ""} autoComplete="off" className={`${INPUT} w-full`} />
              </div>
              <div>
                <label htmlFor="po-phone" className={LABEL}>Phone</label>
                <input id="po-phone" value={details.contactPhone} onChange={e => setDetails({ ...details, contactPhone: e.target.value })}
                  placeholder={supplier?.phone ?? ""} autoComplete="off" className={`${INPUT} w-full`} />
              </div>
            </div>
            <div>
              <p className={LABEL}>Vendor address</p>
              <AddressFields label="Vendor address" value={vendorAddress} onChange={setVendorAddress} inputClass={`${INPUT} w-full`} />
            </div>
            <div>
              <label htmlFor="po-supplier-reference" className={LABEL}>Supplier reference</label>
              <input id="po-supplier-reference" value={header.supplierReference} onChange={e => setHeader({ ...header, supplierReference: e.target.value })}
                placeholder="Their order confirmation" autoComplete="off" className={`${INPUT} w-full`} />
            </div>
          </div>
        </Section>

        <Section title="Accounting details" testId="po-form-accounting">
          <div className="space-y-3">
            <div>
              <label htmlFor="po-terms" className={LABEL}>Terms</label>
              <WithAdd label="Add a payment term" onAdd={() => setAdding("term")}>
                <NameCombobox id="po-terms" value={header.paymentTerm} onChange={v => setHeader({ ...header, paymentTerm: v })} listLabel="Payment terms"
                  placeholder={preview?.paymentTerm ? `${preview.paymentTerm} (supplier's)` : "Supplier's terms"} className={`${INPUT} w-full`}
                  options={terms.map(t => ({ key: t.name, name: t.name, sub: t.meaning }))} />
              </WithAdd>
            </div>
            <div>
              <label htmlFor="po-required-by" className={LABEL}>Required by</label>
              <input id="po-required-by" type="date" value={header.requiredBy} onChange={e => setHeader({ ...header, requiredBy: e.target.value })} className={`${INPUT} w-full`} />
            </div>
            <div>
              <label htmlFor="po-inventory-account" className={LABEL}>Inventory account</label>
              <input id="po-inventory-account" value={preview?.inventoryAccount ?? editing?.inventoryAccount ?? "631: Inventory Control"} readOnly disabled
                title="Set in Accounting → Ledger settings" className={`${INPUT} w-full bg-gray-50 text-gray-500`} />
            </div>
            <label className="flex items-center gap-2 text-sm text-gray-400" title="Comes later: prices are entered before VAT for now">
              <input type="checkbox" disabled checked={false} readOnly aria-label="Tax inclusive" /> Tax inclusive
              <span className="text-[11px]">(later)</span>
            </label>
            <div>
              <label htmlFor="po-tax-rule" className={LABEL}>Tax rule</label>
              <select id="po-tax-rule" value={details.taxRule} onChange={e => setDetails({ ...details, taxRule: e.target.value, taxReason: "" })}
                disabled={!mayChangeVat} title={mayChangeVat ? "Each line starts from it, unless its product has its own" : "Only a manager or admin can change the VAT"}
                className={`${INPUT} w-full disabled:bg-gray-50 disabled:text-gray-500`}>
                <option value="">{`Supplier's: ${preview && !details.taxRule ? preview.defaultTaxRule ?? "" : supplier?.taxRule ?? "organisation default"}`}</option>
                {rates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}
              </select>
              {details.taxRule && (
                <input aria-label="Reason for the order's tax rule" value={details.taxReason} onChange={e => setDetails({ ...details, taxReason: e.target.value })}
                  placeholder="Reason (needed for less VAT)" className={`${INPUT} w-full mt-1.5`} />
              )}
            </div>
          </div>
        </Section>

        <Section title="Shipping details" testId="po-form-shipping">
          <div className="space-y-3">
            <label className="flex items-center gap-2 text-sm text-gray-700" title="Receiving does not show what was ordered: the warehouse counts what arrived">
              <input type="checkbox" checked={details.blindReceipt} onChange={e => setDetails({ ...details, blindReceipt: e.target.checked })} /> Blind receipt
            </label>
            <div className="space-y-3">
              <div>
                <label htmlFor="po-order-date" className={LABEL}>Date *</label>
                <input id="po-order-date" type="date" value={details.orderDate} required onChange={e => setDetails({ ...details, orderDate: e.target.value })} className={`${INPUT} w-full`} />
              </div>
              <div>
                <label htmlFor="po-warehouse" className={LABEL}>Location *</label>
                <select id="po-warehouse" value={warehouseId} onChange={e => pickWarehouse(e.target.value)} required className={`${INPUT} w-full`}>
                  <option value="">Select warehouse</option>
                  {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
              </div>
            </div>
            <label className="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" checked={details.shipToDifferent}
                onChange={e => {
                  const on = e.target.checked;
                  setDetails({ ...details, shipToDifferent: on, shipToCompany: on ? details.shipToCompany : "" });
                  setShippingAddress(on ? EMPTY_ADDRESS : warehouseAddress(warehouseId));
                }} /> Ship to different company
            </label>
            {details.shipToDifferent && (
              <div>
                <label htmlFor="po-ship-to-company" className={LABEL}>Company</label>
                <input id="po-ship-to-company" value={details.shipToCompany} onChange={e => setDetails({ ...details, shipToCompany: e.target.value })}
                  placeholder="e.g. the embroiderer" autoComplete="off" className={`${INPUT} w-full`} />
              </div>
            )}
            <div>
              <p className={LABEL}>Shipping address</p>
              <AddressFields label="Shipping address" value={shippingAddress} onChange={setShippingAddress} inputClass={`${INPUT} w-full`} />
              {!details.shipToDifferent && isBlankAddress(shippingAddress) && warehouseId && (
                <p className="text-[11px] text-gray-400 mt-1">The warehouse has no address yet: add it under Warehouses, or type one here</p>
              )}
            </div>
          </div>
        </Section>
      </div>

      <Section title="Comments">
        <label htmlFor="po-notes" className="sr-only">Comments</label>
        <textarea id="po-notes" rows={2} value={header.notes} onChange={e => setHeader({ ...header, notes: e.target.value })}
          placeholder="Write your note here…" className={`${INPUT} w-full`} />
      </Section>

      <Section title={editing ? "Order lines *" : "Products *"} testId="po-form-lines"
        actions={<button type="button" onClick={() => setLines([...lines, emptyLine()])} className={ADD_BUTTON}>+ Add line</button>}>
        <div className="grid grid-cols-12 gap-2 pb-2 mb-2 border-b border-gray-100 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
          <span className="col-span-3">Product</span><span>Supplier SKU</span><span>Qty</span><span>Cost £</span>
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
                    mode="purchase" supplierId={supplier?.id ?? editing?.supplierId}
                    onSelect={pr => setLine(i, { productId: String(pr.id), unitName: "" })} />
                  <UnitSelect productId={l.productId ? Number(l.productId) : undefined} value={l.unitName ?? ""}
                    onChange={u => setLine(i, { unitName: u, unitCost: "" })} label={`Unit for line ${i + 1}`} />
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
                <button type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))} className="justify-self-end text-gray-400 hover:text-red-600 text-xs px-1">Remove</button>
              </div>
              {l.taxRule && (
                <input aria-label={`VAT reason for line ${i + 1}`} value={l.taxReason} onChange={e => setLine(i, { taxReason: e.target.value })}
                  placeholder="Reason for changing the VAT (needed when it is less VAT)" className={`${INPUT} w-full mt-1`} />
              )}
            </div>
          );
        })}
      </Section>

      <Section title="Additional costs" hint="Freight, duty… an expense, not stock cost" testId="po-form-costs"
        actions={<button type="button" onClick={() => setCosts([...costs, emptyCost()])} className={ADD_BUTTON}>+ Add cost</button>}>
        {costs.length === 0 && <p className="text-sm text-gray-400">None yet.</p>}
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
              <button type="button" onClick={() => setCosts(costs.filter((_, j) => j !== i))} className="justify-self-end text-gray-400 hover:text-red-600 text-xs px-1">Remove</button>
            </div>
          );
        })}
      </Section>

      <div className="grid gap-5 lg:grid-cols-3 items-start">
        <div className="lg:col-span-2" />
        <Section title="Totals">
          <dl data-testid="po-form-totals" className="text-sm grid grid-cols-2 gap-x-6 gap-y-1 tabular-nums">
            <dt className="text-gray-500">Before tax</dt><dd className="text-right">{formatGBP(preview?.subtotal ?? 0)}</dd>
            <dt className="text-gray-500">VAT</dt><dd className="text-right">{formatGBP(preview?.taxTotal ?? 0)}</dd>
            <dt className="font-semibold pt-1 border-t border-gray-100">Total</dt><dd className="text-right font-semibold pt-1 border-t border-gray-100">{formatGBP(preview?.total ?? 0)}</dd>
          </dl>
        </Section>
      </div>
      {previewError && <p className="text-xs text-amber-700">{previewError}</p>}

      {/* Always in view at the bottom of the screen, with the total (review R8) */}
      <StickyActions summary={<>Total <b className="text-gray-900 tabular-nums">{formatGBP(preview?.total ?? 0)}</b>
        <span className="hidden md:inline text-xs text-gray-400 ml-3">&quot;Ordered&quot; = sent to the supplier; a draft can still be changed.</span></>}>
        {onCancel && <button type="button" onClick={onCancel} className="text-sm text-gray-600 hover:text-gray-900 px-3 py-2">Cancel</button>}
        <button type="submit" value="draft" disabled={save.isPending}
          className="border border-gray-300 bg-white text-gray-700 px-5 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50">
          {editing ? "Save draft" : "Save as draft"}
        </button>
        <button type="submit" value="ordered" disabled={save.isPending} aria-busy={save.isPending}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving..." : editing ? "Save & mark ordered" : "Create & mark ordered"}
        </button>
      </StickyActions>

      {/* The "+" pop-ups: what is added is chosen in its field at once */}
      {adding === "supplier" && (
        <NewSupplierDialog initialName={supplier ? "" : supplierName.trim()} onClose={() => setAdding(null)}
          onCreated={s => { setAdded(s); pickSupplier(s.name, [s]); setAdding(null); }} />
      )}
      {adding === "term" && (
        <PaymentTermDialog onClose={() => setAdding(null)} onAdded={name => { setHeader(h => ({ ...h, paymentTerm: name })); setAdding(null); }} />
      )}
    </form>
  );
}
