"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createInvoice, getProducts, getSaleOrders, getPurchaseOrders } from "@/lib/api";
import { Invoice, InvoiceType, Page, Product, SaleOrder, PurchaseOrder } from "@/types";
import { useToast } from "@/components/ui/Toast";

const CURRENCIES = ["USD", "EUR", "GBP", "VND"];
const emptyItem = () => ({ productId: "", description: "", quantity: "", unitPrice: "" });

/** A hand-typed invoice or bill (not from an order), on its own page /invoices/new (review R2, rule D1). */
export default function InvoiceForm({ onCreated, onCancel }: { onCreated: (inv: Invoice) => void; onCancel: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [formType, setFormType] = useState<InvoiceType>("SALES");
  const [partyName, setPartyName]     = useState("");
  const [saleOrderId, setSaleOrderId] = useState("");
  const [poId, setPoId]               = useState("");
  const [issueDate, setIssueDate]     = useState(new Date().toISOString().slice(0, 10));
  const [dueDate, setDueDate]         = useState("");
  const [currency, setCurrency]       = useState("USD");
  const [notes, setNotes]             = useState("");
  const [items, setItems]             = useState([emptyItem()]);
  const [formError, setFormError]     = useState("");

  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: salesOrders } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders-all"],
    queryFn:  () => getSaleOrders({ size: 9999 }),
    enabled:  formType === "SALES",
  });
  const { data: purchaseOrders } = useQuery<Page<PurchaseOrder>>({
    queryKey: ["purchase-orders-all"],
    queryFn:  () => getPurchaseOrders({ size: 9999 }),
    enabled:  formType === "PURCHASE",
  });

  const createMut = useMutation({
    mutationFn: createInvoice,
    onSuccess:  (inv: Invoice) => { qc.invalidateQueries({ queryKey: ["invoices"] }); toast("Invoice created"); onCreated(inv); },
    onError:    (e: any) => setFormError(e.response?.data?.error || "Failed to create invoice"),
  });

  function updateItem(i: number, key: string, val: string) {
    setItems(prev => prev.map((it, idx) => idx === i ? { ...it, [key]: val } : it));
  }
  function addItem()    { setItems(prev => [...prev, emptyItem()]); }
  function removeItem(i: number) { setItems(prev => prev.filter((_, idx) => idx !== i)); }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError("");
    createMut.mutate({
      type:          formType,
      partyName,
      saleOrderId:   saleOrderId ? Number(saleOrderId) : null,
      purchaseOrderId: poId     ? Number(poId)         : null,
      issueDate,
      dueDate:       dueDate || null,
      currency,
      notes:         notes || null,
      items: items.map(it => ({
        productId:   it.productId ? Number(it.productId) : null,
        description: it.description || null,
        quantity:    parseFloat(it.quantity),
        unitPrice:   parseFloat(it.unitPrice),
      })),
    });
  }

  return (
    <form onSubmit={handleSubmit} data-testid="invoice-form"
      className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">

      {formError && (
        <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{formError}</p>
      )}

      {/* type + party */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="invoices-type" className="text-xs font-medium text-gray-600 block mb-1">Type</label>
          <select id="invoices-type" value={formType} onChange={e => setFormType(e.target.value as InvoiceType)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm">
            <option value="SALES">Sales</option>
            <option value="PURCHASE">Purchase</option>
          </select>
        </div>
        <div>
          <label htmlFor="invoices-party" className="text-xs font-medium text-gray-600 block mb-1">
            {formType === "SALES" ? "Customer" : "Supplier"}
          </label>
          <input id="invoices-party" value={partyName} onChange={e => setPartyName(e.target.value)} required
            placeholder={formType === "SALES" ? "Customer name" : "Supplier name"}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm" />
        </div>
      </div>

      {/* optional order link */}
      {formType === "SALES" && salesOrders?.content && (
        <div>
          <label htmlFor="invoices-sale-order" className="text-xs font-medium text-gray-600 block mb-1">
            Link to Sale Order (optional)
          </label>
          <select id="invoices-sale-order" value={saleOrderId} onChange={e => setSaleOrderId(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm">
            <option value="">— none —</option>
            {salesOrders.content.map(so => (
              <option key={so.id} value={so.id}>{so.orderNumber} · {so.customerName}</option>
            ))}
          </select>
        </div>
      )}

      {formType === "PURCHASE" && purchaseOrders?.content && (
        <div>
          <label htmlFor="invoices-purchase-order" className="text-xs font-medium text-gray-600 block mb-1">
            Link to Purchase Order (optional)
          </label>
          <select id="invoices-purchase-order" value={poId} onChange={e => setPoId(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm">
            <option value="">— none —</option>
            {purchaseOrders.content.map(po => (
              <option key={po.id} value={po.id}>PO-{po.id} · {po.supplierName}</option>
            ))}
          </select>
        </div>
      )}

      {/* dates + currency */}
      <div className="grid grid-cols-3 gap-4">
        <div>
          <label htmlFor="invoices-issue-date" className="text-xs font-medium text-gray-600 block mb-1">Issue Date</label>
          <input id="invoices-issue-date" type="date" value={issueDate} onChange={e => setIssueDate(e.target.value)} required
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm" />
        </div>
        <div>
          <label htmlFor="invoices-due-date-optional" className="text-xs font-medium text-gray-600 block mb-1">Due Date (optional)</label>
          <input id="invoices-due-date-optional" type="date" value={dueDate} onChange={e => setDueDate(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm" />
        </div>
        <div>
          <label htmlFor="invoices-currency" className="text-xs font-medium text-gray-600 block mb-1">Currency</label>
          <select id="invoices-currency" value={currency} onChange={e => setCurrency(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm">
            {CURRENCIES.map(c => <option key={c}>{c}</option>)}
          </select>
        </div>
      </div>

      {/* items */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="text-xs font-medium text-gray-600">Line Items</label>
          <button type="button" onClick={addItem}
            className="text-xs text-brand-600 hover:text-brand-800 font-medium">
            + Add item
          </button>
        </div>
        <div className="space-y-2">
          {items.map((it, i) => (
            <div key={i} className="grid grid-cols-12 gap-2 items-start">
              <div className="col-span-3">
                <select value={it.productId} onChange={e => updateItem(i, "productId", e.target.value)}
                  className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-xs">
                  <option value="">— no product —</option>
                  {products.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </div>
              <div className="col-span-4">
                <input value={it.description} onChange={e => updateItem(i, "description", e.target.value)}
                  placeholder="Description"
                  className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-xs" />
              </div>
              <div className="col-span-2">
                <input type="number" step="0.0001" min="0.0001" value={it.quantity}
                  onChange={e => updateItem(i, "quantity", e.target.value)}
                  placeholder="Qty" required
                  className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-xs" />
              </div>
              <div className="col-span-2">
                <input type="number" step="0.01" min="0.01" value={it.unitPrice}
                  onChange={e => updateItem(i, "unitPrice", e.target.value)}
                  placeholder="Unit price" required
                  className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-xs" />
              </div>
              <div className="col-span-1 flex justify-end pt-1">
                {items.length > 1 && (
                  <button type="button" onClick={() => removeItem(i)}
                    className="text-xs text-red-400 hover:text-red-600">✕</button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* notes */}
      <div>
        <label htmlFor="invoices-notes-optional" className="text-xs font-medium text-gray-600 block mb-1">Notes (optional)</label>
        <textarea id="invoices-notes-optional" value={notes} onChange={e => setNotes(e.target.value)} rows={2}
          className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm resize-none" />
      </div>

      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>
        <button type="submit" disabled={createMut.isPending} aria-busy={createMut.isPending}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {createMut.isPending ? "Creating..." : "Create Invoice"}
        </button>
      </div>
    </form>
  );
}
