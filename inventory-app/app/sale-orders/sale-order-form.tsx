"use client";

import UnitSelect from "@/components/UnitSelect";
import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createSaleOrder, getTaxRates, previewSaleOrder, updateSaleOrder, getPriceTiers, getSalesReps, getPaymentTerms } from "@/lib/api";
import { Customer, CustomerAddress, PostalAddress, PriceTier, SaleOrder, TaxRate, Warehouse } from "@/types";
import { AddressFields, EMPTY_ADDRESS, oneLine } from "@/components/AddressFields";
import { formatGBP } from "@/lib/currency";
import { useAuth } from "@/contexts/AuthContext";
import ProductPicker from "./product-picker";
import NameCombobox from "@/components/NameCombobox";
import { Section, StickyActions, ADD_BUTTON } from "@/components/ui/Section";

// productSku / productName: shown in the search box when editing an existing line
type LineForm = { productSku?: string; productName?: string; productId: string; unitName?: string; quantity: string; unitPrice: string; discountPercent: string; taxRule: string; taxReason: string };
type ChargeForm = { description: string; quantity: string; unitPrice: string; discountPercent: string; taxRule: string; taxReason: string };

const emptyLine = (): LineForm => ({ productId: "", quantity: "", unitPrice: "", discountPercent: "", taxRule: "", taxReason: "" });
const emptyCharge = (): ChargeForm => ({ description: "", quantity: "1", unitPrice: "", discountPercent: "", taxRule: "", taxReason: "" });
const num = (s: string) => (s.trim() === "" ? undefined : Number(s));
const text = (s: string) => (s.trim() === "" ? undefined : s.trim());

// The customer's default of a type, like the server picks them (G4)
const pick = (list: CustomerAddress[], type: CustomerAddress["type"]) =>
  list.filter(a => a.type === type).sort((a, b) => Number(b.defaultForType) - Number(a.defaultForType))[0];
const plain = (a?: PostalAddress | null): PostalAddress => ({
  line1: a?.line1 ?? "", line2: a?.line2 ?? "", city: a?.city ?? "", state: a?.state ?? "", postcode: a?.postcode ?? "", country: a?.country ?? "",
});
export function defaultBilling(c?: Customer): PostalAddress {
  const list = c?.addresses ?? [];
  return plain(pick(list, "BILLING") ?? pick(list, "BUSINESS") ?? list[0]);
}
export function defaultShipping(c?: Customer): PostalAddress {
  return pick(c?.addresses ?? [], "SHIPPING") ? plain(pick(c!.addresses!, "SHIPPING")) : defaultBilling(c);
}
const sendAddress = (a: PostalAddress) => (oneLine(a) ? a : undefined);

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
  // Like Cin7: terms are picked from a list, each with what it means for the due date
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });
  // Like Cin7: the sales rep is picked from a list (names used on customers and orders)
  const { data: salesReps = [] } = useQuery<string[]>({ queryKey: ["sales-reps"], queryFn: getSalesReps });

  const str = (v: unknown) => (v == null ? "" : String(v));
  const [customerName, setCustomerName] = useState(editing?.customerName ?? "");
  const [warehouseId, setWarehouseId] = useState(str(editing?.warehouseId));
  const [header, setHeader] = useState({
    customerReference: str(editing?.customerReference), salesRep: str(editing?.salesRep), paymentTerm: str(editing?.paymentTerm),
    priceTier: str(editing?.priceTier),
    shipBy: str(editing?.shipBy), carrier: str(editing?.carrier), shippingNotes: str(editing?.shippingNotes), notes: str(editing?.notes),
  });
  // G4: billing and shipping addresses on the order, from the customer's defaults unless changed
  const [billing, setBilling] = useState<PostalAddress>(plain(editing?.billingAddress ?? EMPTY_ADDRESS));
  const [shipping, setShipping] = useState<PostalAddress>(plain(editing?.shippingAddress ?? EMPTY_ADDRESS));
  const [shipTo, setShipTo] = useState({ company: str(editing?.shipToCompany), contact: str(editing?.shipToContact) });
  const [quoteValidUntil, setQuoteValidUntil] = useState(str(editing?.quoteValidUntil));
  const [lines, setLines] = useState<LineForm[]>(editing ? editing.lines.map(l => ({
    productSku: l.productSku, productName: l.productName, productId: String(l.productId), unitName: l.unitName ?? "", quantity: str(l.quantity),
    unitPrice: str(l.unitPrice), discountPercent: str(l.discountPercent), taxRule: l.taxOverridden ? str(l.taxName) : "", taxReason: "",
  })) : [emptyLine()]);
  const [charges, setCharges] = useState<ChargeForm[]>(editing ? editing.charges.map(c => ({
    description: c.description, quantity: str(c.quantity), unitPrice: str(c.unitPrice), discountPercent: str(c.discountPercent),
    taxRule: c.taxOverridden ? str(c.taxName) : "", taxReason: "",
  })) : []);
  const [error, setError] = useState("");

  const existing = customers.find(c => c.name.toLowerCase() === customerName.trim().toLowerCase());
  // Another customer chosen: their addresses (an order being changed keeps its own until then)
  const [addressesOf, setAddressesOf] = useState<number | undefined>(editing?.customerId ?? undefined);
  if (existing && existing.id !== addressesOf) {
    setAddressesOf(existing.id);
    setBilling(defaultBilling(existing));
    setShipping(defaultShipping(existing));
  }
  const saved = existing?.addresses ?? [];
  const { data: tiers = [] } = useQuery<PriceTier[]>({ queryKey: ["price-tiers"], queryFn: getPriceTiers });
  // The tier the customer is on (a Cin7 name that is not a tier here prices at tier 1)
  const customerTier = tiers.find(t => t.name.toLowerCase() === (existing?.priceTier ?? "").toLowerCase())?.name ?? tiers[0]?.name ?? "Retail";
  const request = {
    customerId: existing?.id, customerName: existing ? undefined : customerName.trim(),
    warehouseId: warehouseId ? Number(warehouseId) : undefined,
    customerReference: text(header.customerReference), salesRep: text(header.salesRep), paymentTerm: text(header.paymentTerm),
    priceTier: text(header.priceTier),
    shipBy: text(header.shipBy), carrier: text(header.carrier), shippingNotes: text(header.shippingNotes), notes: text(header.notes),
    billingAddress: sendAddress(billing), shippingAddress: sendAddress(shipping),
    shipToCompany: text(shipTo.company), shipToContact: text(shipTo.contact),
    lines: lines.map(l => ({
      productId: l.productId ? Number(l.productId) : undefined, unitName: text(l.unitName ?? ""), quantity: num(l.quantity), unitPrice: num(l.unitPrice) ?? null,
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

  const field = (id: string, label: string, control: React.ReactNode, help?: React.ReactNode) => (
    <div>
      <label htmlFor={id} className={LABEL}>{label}</label>
      {control}
      {help && <p className="text-[11px] text-gray-400 mt-1">{help}</p>}
    </div>
  );
  const lineWord = editing ? (isQuote ? "Quote lines" : "Order lines") : "Products";

  return (
    <form onSubmit={e => {
        e.preventDefault(); setError("");
        // Which button was pressed: "Create quote" or "Create sale order" (editing keeps the stage)
        const as = ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value;
        save.mutate({ asQuote: editing ? isQuote : as === "quote" });
      }}
      className="space-y-5" data-testid="sale-order-form">
      {editing && <p className="text-sm text-gray-600">Editing {isQuote ? "quote" : "order"} <b>{editing.orderNumber}</b></p>}
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      {/* Like Cin7's sale screen: customer, accounting and shipping details side by side */}
      <div className="grid gap-5 lg:grid-cols-3 items-start">
        <Section title="Customer details" testId="so-form-customer">
          <div className="space-y-3">
            {field("sale-orders-customer", "Customer *", <>
              <NameCombobox id="sale-orders-customer" value={customerName} onChange={setCustomerName} required listLabel="Customers"
                placeholder="Pick a customer or type a new name" className={`${INPUT} w-full`}
                options={customers.filter(c => c.status === "ACTIVE").map(c => ({ key: c.id, name: c.name, sub: c.contacts[0]?.email }))} />
            </>, customerName.trim() && !existing ? "New customer — will be added to Customers" : undefined)}
            {field("sale-orders-reference", "Customer reference",
              <input id="sale-orders-reference" value={header.customerReference} onChange={e => setHeader({ ...header, customerReference: e.target.value })}
                placeholder="Their PO number" autoComplete="off" className={`${INPUT} w-full`} />)}
          </div>
          <div data-testid="so-form-addresses">
            <AddressBlock title="Billing address" value={billing} onChange={setBilling} saved={saved} />
          </div>
        </Section>

        <Section title="Accounting details" testId="so-form-accounting">
          <div className="space-y-3">
            {field("sale-orders-terms", "Terms",
              <NameCombobox id="sale-orders-terms" value={header.paymentTerm} onChange={v => setHeader({ ...header, paymentTerm: v })} listLabel="Payment terms"
                placeholder={preview?.paymentTerm ? `${preview.paymentTerm} (customer's)` : "Customer's terms"} className={`${INPUT} w-full`}
                options={terms.map(t => ({ key: t.name, name: t.name, sub: t.meaning }))} />)}
            {/* Like Cin7: the customer's tier unless changed for this order; lines without a typed price follow it (G1) */}
            {field("sale-orders-price-tier", "Price tier",
              <select id="sale-orders-price-tier" value={header.priceTier} onChange={e => setHeader({ ...header, priceTier: e.target.value })}
                className={`${INPUT} w-full`}>
                <option value="">{customerTier} (customer&apos;s)</option>
                {tiers.map(t => <option key={t.number} value={t.name}>{t.name}</option>)}
              </select>)}
            {field("sale-orders-rep", "Sales rep",
              <NameCombobox id="sale-orders-rep" value={header.salesRep} onChange={v => setHeader({ ...header, salesRep: v })} listLabel="Sales reps"
                placeholder={preview?.salesRep ? `${preview.salesRep} (customer's)` : "Customer's sales rep"} className={`${INPUT} w-full`}
                options={salesReps.map(r => ({ key: r, name: r }))} />)}
            {(!editing || isQuote) && field("sale-orders-valid-until", "Quote valid until",
              <input id="sale-orders-valid-until" type="date" value={quoteValidUntil} onChange={e => setQuoteValidUntil(e.target.value)} className={`${INPUT} w-full`} />,
              !editing ? "Quotes only; empty = the organisation's default" : undefined)}
          </div>
        </Section>

        <Section title="Shipping details" testId="so-form-shipping">
          <div className="space-y-3">
            {field("sale-orders-ship-from-warehouse", "Ship from Warehouse *",
              <select id="sale-orders-ship-from-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required className={`${INPUT} w-full`}>
                <option value="">Select warehouse</option>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>)}
            <div className="grid grid-cols-2 gap-3">
              {field("sale-orders-ship-by", "Ship by",
                <input id="sale-orders-ship-by" type="date" value={header.shipBy} onChange={e => setHeader({ ...header, shipBy: e.target.value })} className={`${INPUT} w-full`} />)}
              {field("sale-orders-carrier", "Carrier",
                <input id="sale-orders-carrier" value={header.carrier} onChange={e => setHeader({ ...header, carrier: e.target.value })}
                  placeholder={preview?.carrier ? `${preview.carrier} (customer's)` : "Customer's carrier"} autoComplete="off" className={`${INPUT} w-full`} />)}
            </div>
            {field("sale-orders-shipping-notes", "Shipping notes",
              <input id="sale-orders-shipping-notes" value={header.shippingNotes} onChange={e => setHeader({ ...header, shippingNotes: e.target.value })}
                placeholder="Optional" autoComplete="off" className={`${INPUT} w-full`} />)}
          </div>
          <AddressBlock title="Shipping address" value={shipping} onChange={setShipping} saved={saved}>
            <div className="grid grid-cols-2 gap-2 mb-2">
              <input aria-label="Ship to company" placeholder="Ship to company" value={shipTo.company} autoComplete="off"
                onChange={e => setShipTo({ ...shipTo, company: e.target.value })} className={INPUT} />
              <input aria-label="Ship to contact" placeholder="Ship to contact" value={shipTo.contact} autoComplete="off"
                onChange={e => setShipTo({ ...shipTo, contact: e.target.value })} className={INPUT} />
            </div>
          </AddressBlock>
        </Section>
      </div>

      <Section title={`${lineWord} *`} testId="so-form-lines"
        actions={<button type="button" onClick={() => setLines([...lines, emptyLine()])} className={ADD_BUTTON}>+ Add line</button>}>
        <div className="grid grid-cols-12 gap-2 pb-2 mb-2 border-b border-gray-100 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
          <span className="col-span-4">Product</span><span>Qty</span><span>Price £</span>
          <span>Disc %</span><span className="col-span-2">VAT</span><span className="text-right">Net</span><span className="text-right">VAT £</span><span />
        </div>
        {lines.map((l, i) => {
          const p = previewLineFor(i);
          return (
            <div key={i} className="mb-2" data-testid={`so-form-line-${i}`}>
              <div className="grid grid-cols-12 gap-2 items-center">
                <div className="col-span-4">
                  {/* No typed price: the server prices it for the customer (contract price, else tier, else Retail; G1) */}
                  <ProductPicker label={`Product for line ${i + 1}`}
                    selected={l.productSku ? { id: Number(l.productId), sku: l.productSku, name: l.productName ?? "" } : undefined}
                    onSelect={pr => setLine(i, { productId: String(pr.id), unitName: "" })} />
                  <UnitSelect productId={l.productId ? Number(l.productId) : undefined} value={l.unitName ?? ""}
                    onChange={u => setLine(i, { unitName: u, unitPrice: "" })} label={`Unit for line ${i + 1}`} />
                </div>
                <input aria-label={`Quantity for line ${i + 1}`} type="number" min="0.0001" step="any" value={l.quantity} placeholder="Qty" required
                  onChange={e => setLine(i, { quantity: e.target.value })} className={INPUT} />
                <input aria-label={`Unit price for line ${i + 1}`} type="number" min="0" step="any" value={l.unitPrice}
                  placeholder={p && p.unitPrice != null ? String(Number(p.unitPrice)) : "£"}
                  title={p?.priceSource ? `${p.priceSource} price — type a price to change it` : undefined}
                  onChange={e => setLine(i, { unitPrice: e.target.value })} className={INPUT} />
                <input aria-label={`Discount for line ${i + 1}`} type="number" min="0" max="100" step="any" value={l.discountPercent}
                  placeholder={p ? String(p.discountPercent) : "%"} onChange={e => setLine(i, { discountPercent: e.target.value })} className={INPUT} />
                <div className="col-span-2">{vatSelect(l.taxRule, p && !p.taxOverridden ? p.taxName : undefined, v => setLine(i, { taxRule: v }), `VAT for line ${i + 1}`)}</div>
                <span className="text-right text-sm tabular-nums text-gray-700">{p ? formatGBP(p.netAmount) : "—"}</span>
                <span className="text-right text-sm tabular-nums text-gray-500">{p ? formatGBP(p.taxAmount) : "—"}</span>
                <button type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))} aria-label={`Remove line ${i + 1}`}
                  className="justify-self-end text-gray-400 hover:text-red-600 text-xs px-1">Remove</button>
              </div>
              {p?.priceSource && !l.unitPrice && (
                <p data-testid={`so-form-price-source-${i}`} className="text-[11px] text-gray-400 mt-0.5 ml-[33%]">{p.priceSource} price {formatGBP(p.unitPrice)}</p>
              )}
              {l.taxRule && (
                <input aria-label={`VAT reason for line ${i + 1}`} value={l.taxReason} onChange={e => setLine(i, { taxReason: e.target.value })}
                  placeholder="Reason for changing the VAT (needed when it is less VAT)" className={`${INPUT} w-full mt-1`} />
              )}
            </div>
          );
        })}
      </Section>

      <Section title="Additional charges and services" testId="so-form-charges"
        actions={<button type="button" onClick={() => setCharges([...charges, emptyCharge()])} className={ADD_BUTTON}>+ Add charge</button>}>
        {charges.length === 0 && <p className="text-sm text-gray-400">Carriage, delivery… none yet.</p>}
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
                <button type="button" onClick={() => setCharges(charges.filter((_, j) => j !== i))} aria-label={`Remove charge ${i + 1}`}
                  className="justify-self-end text-gray-400 hover:text-red-600 text-xs px-1">Remove</button>
              </div>
              {c.taxRule && (
                <input aria-label={`VAT reason for charge ${i + 1}`} value={c.taxReason} onChange={e => setCharge(i, { taxReason: e.target.value })}
                  placeholder="Reason for changing the VAT (needed when it is less VAT)" className={`${INPUT} w-full mt-1`} />
              )}
            </div>
          );
        })}
      </Section>

      <div className="grid gap-5 lg:grid-cols-3 items-start">
        <Section title="Comments" className="lg:col-span-2">
          <label htmlFor="sale-orders-notes" className="sr-only">Notes</label>
          <textarea id="sale-orders-notes" rows={3} value={header.notes} onChange={e => setHeader({ ...header, notes: e.target.value })}
            placeholder="Notes for this order (optional)" className={`${INPUT} w-full`} />
        </Section>
        <Section title="Totals">
          <dl data-testid="so-form-totals" className="text-sm grid grid-cols-2 gap-x-6 gap-y-1 tabular-nums">
            <dt className="text-gray-500">Before tax</dt><dd className="text-right">{formatGBP(preview?.subtotal ?? 0)}</dd>
            <dt className="text-gray-500">VAT</dt><dd className="text-right">{formatGBP(preview?.taxTotal ?? 0)}</dd>
            <dt className="font-semibold pt-1 border-t border-gray-100">Total</dt><dd className="text-right font-semibold pt-1 border-t border-gray-100">{formatGBP(preview?.total ?? 0)}</dd>
          </dl>
        </Section>
      </div>

      {previewError && <p className="text-xs text-amber-700">{previewError}</p>}
      {/* G2: warning at order entry; the order can be saved, but only a manager can confirm it */}
      {preview?.creditWarning && (
        <p data-testid="so-form-credit-warning" role="alert" className="text-sm text-red-800 bg-red-50 border border-red-200 rounded px-3 py-2">
          {preview.creditWarning}. The order can be saved; only a manager can confirm it.
        </p>
      )}

      {/* Always in view at the bottom of the screen, with the total (review R8) */}
      <StickyActions summary={<>Total <b className="text-gray-900 tabular-nums">{formatGBP(preview?.total ?? 0)}</b>
        {!editing && <span className="hidden md:inline text-xs text-gray-400 ml-3">A quote reserves no stock and becomes the order when the customer accepts it.</span>}</>}>
        {onCancel && <button type="button" onClick={onCancel} className="text-sm text-gray-600 hover:text-gray-900 px-3 py-2">Cancel</button>}
        {editing ? (
          <button type="submit" disabled={save.isPending}
            className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {save.isPending ? "Saving..." : "Save changes"}
          </button>
        ) : (
          <>
            <button type="submit" value="quote" disabled={save.isPending}
              className="border border-violet-300 bg-white text-violet-700 px-5 py-2 rounded-md text-sm font-medium hover:bg-violet-50 disabled:opacity-50">
              Create quote
            </button>
            <button type="submit" value="order" disabled={save.isPending}
              className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              {save.isPending ? "Saving..." : "Create Sale Order"}
            </button>
          </>
        )}
      </StickyActions>
    </form>
  );
}

// One address on the order: pick one of the customer's saved addresses, or type it (G4). Shown as one line; "Change"
// opens the fields (the fields stay in the page, only hidden, so the address is still sent and read).
function AddressBlock({ title, value, onChange, saved, children }: {
  title: string; value: PostalAddress; onChange: (a: PostalAddress) => void; saved: CustomerAddress[]; children?: React.ReactNode;
}) {
  const [editing, setEditing] = useState(false);
  const current = saved.findIndex(a => oneLine(a) === oneLine(value));
  const text = oneLine(value);
  return (
    <div className="mt-5 pt-4 border-t border-gray-100">
      <div className="flex items-center justify-between mb-2 gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">{title}</h3>
        <button type="button" onClick={() => setEditing(e => !e)} aria-expanded={editing} aria-label={`${editing ? "Done with" : "Change"} ${title.toLowerCase()}`}
          className="text-xs font-medium text-brand-700 hover:text-brand-900">{editing ? "Done" : text ? "Change" : "Add"}</button>
      </div>
      {saved.length > 0 && (
        <select aria-label={`${title}: saved addresses`} value={current >= 0 ? String(current) : ""}
          onChange={e => { const a = saved[Number(e.target.value)]; if (a) onChange(plain(a)); }}
          className={`${INPUT} w-full text-xs mb-2`}>
          <option value="">{current >= 0 ? "" : "Typed for this order"}</option>
          {saved.map((a, i) => <option key={a.id ?? i} value={i}>{a.type.charAt(0) + a.type.slice(1).toLowerCase()}: {oneLine(a)}</option>)}
        </select>
      )}
      {children}
      {!editing && <p className={`text-sm ${text ? "text-gray-700" : "text-gray-400"}`}>{text || "No address yet"}</p>}
      <div className={editing ? "" : "hidden"}>
        <AddressFields label={title} value={value} onChange={onChange} inputClass={`${INPUT} w-full`} />
      </div>
    </div>
  );
}
