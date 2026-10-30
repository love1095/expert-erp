"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getInvoices, createInvoice, issueInvoice, cancelInvoice, payInvoice,
  getProducts, getSaleOrders, getPurchaseOrders,
} from "@/lib/api";
import { Invoice, InvoiceStatus, InvoiceType, PaymentMethod, Page, Product, SaleOrder, PurchaseOrder } from "@/types";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";
import CreditNoteForm from "./credit-note-form";

// ─── constants ──────────────────────────────────────────────────────────────

const STATUS_COLOR: Record<InvoiceStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  ISSUED:    "bg-blue-100 text-blue-700",
  PAID:      "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const CURRENCIES = ["USD", "EUR", "GBP", "VND"];
const METHODS: PaymentMethod[] = ["CASH", "BANK", "STRIPE"];

// ─── empty item helper ───────────────────────────────────────────────────────

const emptyItem = () => ({ productId: "", description: "", quantity: "", unitPrice: "" });

// ─── main component ──────────────────────────────────────────────────────────

export default function InvoicesPage() {
  const qc = useQueryClient();

  // list state
  const [page, setPage]         = useState(0);
  const [typeFilter, setTypeFilter] = useState<string>("");
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [search, setSearch] = useState("");
  const [size, setSize] = useState(20);

  // form state
  const [showForm, setShowForm] = useState(false);
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

  // detail / payment panel
  const [selected, setSelected]   = useState<Invoice | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState<PaymentMethod>("CASH");
  const [payError, setPayError]   = useState("");
  const [crediting, setCrediting] = useState(false);
  const isCreditNote = selected?.kind === "CREDIT_NOTE";

  // ── queries ────────────────────────────────────────────────────────────────

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Invoice>>({
    queryKey: ["invoices", page, size, typeFilter, statusFilter, search],
    queryFn:  () => getInvoices({ page, size, type: typeFilter || undefined, status: statusFilter || undefined, q: search || undefined }),
  });

  const { data: products = [] } = useQuery<Product[]>({
    queryKey: ["products"],
    queryFn:  () => getProducts(),
    enabled:  showForm,
  });

  const { data: salesOrders } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders-all"],
    queryFn:  () => getSaleOrders({ size: 9999 }),
    enabled:  showForm && formType === "SALES",
  });

  const { data: purchaseOrders } = useQuery<Page<PurchaseOrder>>({
    queryKey: ["purchase-orders-all"],
    queryFn:  () => getPurchaseOrders({ size: 9999 }),
    enabled:  showForm && formType === "PURCHASE",
  });

  // ── mutations ──────────────────────────────────────────────────────────────

  const createMut = useMutation({
    mutationFn: createInvoice,
    onSuccess:  () => { qc.invalidateQueries({ queryKey: ["invoices"] }); resetForm(); },
    onError:    (e: any) => setFormError(e.response?.data?.error || "Failed to create invoice"),
  });

  const issueMut = useMutation({
    mutationFn: (id: number) => issueInvoice(id),
    onSuccess:  (updated: Invoice) => { qc.invalidateQueries({ queryKey: ["invoices"] }); setSelected(updated); },
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to issue invoice"),
  });

  const cancelMut = useMutation({
    mutationFn: (id: number) => cancelInvoice(id),
    onSuccess:  (updated: Invoice) => { qc.invalidateQueries({ queryKey: ["invoices"] }); setSelected(updated); },
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to cancel invoice"),
  });

  const payMut = useMutation({
    mutationFn: ({ id, amount, method }: { id: number; amount: string; method: PaymentMethod }) =>
      payInvoice(id, { amount: parseFloat(amount), method }),
    onSuccess:  (updated: Invoice) => {
      qc.invalidateQueries({ queryKey: ["invoices"] });
      setSelected(updated);
      setPayAmount("");
      setPayError("");
    },
    onError: (e: any) => setPayError(e.response?.data?.error || "Payment failed"),
  });

  // ── helpers ────────────────────────────────────────────────────────────────

  function resetForm() {
    setShowForm(false); setFormType("SALES"); setPartyName(""); setSaleOrderId("");
    setPoId(""); setIssueDate(new Date().toISOString().slice(0, 10)); setDueDate("");
    setCurrency("USD"); setNotes(""); setItems([emptyItem()]); setFormError("");
  }

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

  const invoiceList = data?.content ?? [];

  // ── render ─────────────────────────────────────────────────────────────────

  return (
    <div>
      {/* header */}
      <PageHeader title="Invoices" subtitle="Sales and purchase invoices and credit notes">
        <button onClick={() => { setShowForm(s => !s); setFormError(""); }}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New Invoice"}
        </button>
      </PageHeader>

      {/* create form */}
      {showForm && (
        <form onSubmit={handleSubmit}
          className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="text-sm font-semibold text-gray-700">New Invoice</h2>

          {formError && (
            <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{formError}</p>
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

          <button type="submit" disabled={createMut.isPending}
            className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {createMut.isPending ? "Creating..." : "Create Invoice"}
          </button>
        </form>
      )}

      {/* filter bar */}
      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        placeholder="Number, customer or supplier"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
          <label htmlFor="invoices-type-filter" className="sr-only">Type</label>
          <select id="invoices-type-filter" value={typeFilter} onChange={e => { setTypeFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">Sales and purchase</option>
            <option value="SALES">Sales</option>
            <option value="PURCHASE">Purchase</option>
          </select>
          <label htmlFor="invoices-status-filter" className="sr-only">Status</label>
          <select id="invoices-status-filter" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="ISSUED">Issued (awaiting payment)</option>
            <option value="PAID">Paid</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </>} />

      {/* two-column layout when detail panel is open */}
      <div className={`flex gap-4 ${selected ? "items-start" : ""}`}>
        {/* invoice table */}
        <div className={`flex-1 min-w-0 ${selected ? "max-w-[calc(100%-320px)]" : ""}`}>
          {isLoading ? (
            <p className="text-gray-400 text-sm">Loading…</p>
          ) : (
            <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {["Number", "Type", "Party", "Total", "Paid", "Status", "Due", ""].map(h => (
                      <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {invoiceList.length === 0 && (
                    <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400 text-sm">No invoices yet</td></tr>
                  )}
                  {invoiceList.map(inv => (
                    <tr key={inv.id}
                      onClick={() => { setSelected(inv); setCrediting(false); }}
                      className={`hover:bg-gray-50 cursor-pointer ${selected?.id === inv.id ? "bg-brand-50" : ""}`}>
                      <td className="px-4 py-3 font-mono text-xs text-brand-600 font-semibold">
                        {inv.invoiceNumber}
                        {inv.kind === "CREDIT_NOTE" && <span className="block font-sans text-[10px] font-semibold text-brand-600">Credit note</span>}
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${
                          inv.type === "SALES" ? "bg-sky-100 text-sky-700" : "bg-purple-100 text-purple-700"
                        }`}>{inv.type}</span>
                      </td>
                      <td className="px-4 py-3 text-gray-700 max-w-[140px] truncate">{inv.partyName}</td>
                      <td className="px-4 py-3 tabular-nums text-gray-700">
                        {inv.currency} {inv.totalAmount.toFixed(2)}
                      </td>
                      <td className="px-4 py-3 tabular-nums text-gray-500 text-xs">
                        {inv.amountPaid > 0 ? inv.amountPaid.toFixed(2) : "—"}
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[inv.status]}`}>
                          {inv.status}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-400">
                        {inv.dueDate ? new Date(inv.dueDate).toLocaleDateString() : "—"}
                      </td>
                      <td className="px-4 py-3 text-right space-x-2 whitespace-nowrap">
                        {inv.status === "DRAFT" && (
                          <button onClick={e => { e.stopPropagation(); issueMut.mutate(inv.id); }}
                            className="text-xs text-brand-700 hover:underline font-medium">Issue</button>
                        )}
                        {(inv.status === "DRAFT" || inv.status === "ISSUED") && inv.kind !== "CREDIT_NOTE" && (
                          <button onClick={e => { e.stopPropagation(); cancelMut.mutate(inv.id); }}
                            className="text-xs text-gray-400 hover:text-red-500 font-medium">Cancel</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && (
            <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage}
              size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
          )}
        </div>

        {/* detail panel */}
        {selected && (
          <div className="w-80 shrink-0 bg-white border border-gray-200 rounded-xl p-5 space-y-4">
            <div className="flex items-start justify-between">
              <div>
                <p className="font-mono text-sm font-bold text-brand-600">{selected.invoiceNumber}</p>
                {isCreditNote && (
                  <p className="text-xs font-semibold text-brand-600">Credit note for {selected.creditedInvoiceNumber}</p>
                )}
                <p className="text-xs text-gray-500 mt-0.5">{selected.partyName}</p>
              </div>
              <button onClick={() => setSelected(null)} className="text-gray-300 hover:text-gray-500 text-lg leading-none">×</button>
            </div>

            {/* amounts */}
            <div className="bg-gray-50 rounded-lg p-3 space-y-1 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Before tax</span>
                <span className="tabular-nums">{Number(selected.subtotal).toFixed(2)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">VAT</span>
                <span className="tabular-nums">{Number(selected.taxTotal).toFixed(2)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Total</span>
                <span className="font-semibold">{selected.currency} {selected.totalAmount.toFixed(2)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Paid</span>
                <span className="text-emerald-600 font-semibold">{selected.amountPaid.toFixed(2)}</span>
              </div>
              <div className="flex justify-between border-t border-gray-200 pt-1 mt-1">
                <span className="text-gray-500">Balance</span>
                <span className={`font-bold ${(selected.totalAmount - selected.amountPaid) > 0 ? "text-red-600" : "text-emerald-600"}`}>
                  {(selected.totalAmount - selected.amountPaid).toFixed(2)}
                </span>
              </div>
            </div>

            {/* items */}
            <div>
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Items</p>
              <div className="space-y-1">
                {selected.items.map(item => (
                  <div key={item.id} className="flex justify-between text-xs">
                    <span className="text-gray-600 truncate max-w-[160px]" title={item.taxName ?? undefined}>
                      {Number(item.quantity).toLocaleString()} × {item.productSku ?? item.description ?? "—"}
                    </span>
                    <span className="text-gray-700 tabular-nums">
                      {item.totalPrice.toFixed(2)}{Number(item.taxAmount) > 0 && <span className="text-gray-400"> +{Number(item.taxAmount).toFixed(2)} VAT</span>}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* payments history */}
            {selected.payments.length > 0 && (
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">{isCreditNote ? "Refunds" : "Payments"}</p>
                <div className="space-y-1">
                  {selected.payments.map(p => (
                    <div key={p.id} className="flex justify-between text-xs">
                      <span className="text-gray-500">
                        {p.method === "CREDIT_NOTE" ? `Credit note ${p.reference ?? ""}` : p.method} · {new Date(p.paidAt).toLocaleDateString()}
                        {p.reference && p.method !== "CREDIT_NOTE" && ` · ${p.reference}`}
                      </span>
                      <span className="text-emerald-600 font-semibold">{p.amount.toFixed(2)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* credit note & restock */}
            {crediting && (
              <CreditNoteForm invoice={selected} onCancel={() => setCrediting(false)}
                onDone={cn => {
                  setCrediting(false);
                  setSelected(cn);
                  qc.invalidateQueries({ queryKey: ["invoices"] });
                  qc.invalidateQueries({ queryKey: ["sale-order"] });
                  qc.invalidateQueries({ queryKey: ["stock-overview"] });
                }} />
            )}
            {!crediting && !isCreditNote && selected.type === "SALES" && (selected.status === "ISSUED" || selected.status === "PAID") && (
              <button onClick={() => setCrediting(true)}
                className="w-full border border-brand-300 text-brand-700 py-1.5 rounded-lg text-xs font-medium hover:bg-brand-50">
                Credit note & restock
              </button>
            )}

            {/* record payment (a refund, on a credit note) */}
            {selected.status === "ISSUED" && !crediting && (
              <div className="border-t border-gray-100 pt-3">
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">{isCreditNote ? "Record refund" : "Record Payment"}</p>
                {payError && <p className="text-xs text-red-500 mb-2">{payError}</p>}
                <div className="space-y-2">
                  <input type="number" step="0.01" min="0.01" value={payAmount}
                    onChange={e => setPayAmount(e.target.value)} placeholder="Amount"
                    className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm" />
                  <select value={payMethod} onChange={e => setPayMethod(e.target.value as PaymentMethod)}
                    className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm">
                    {METHODS.map(m => <option key={m}>{m}</option>)}
                  </select>
                  <button
                    disabled={!payAmount || payMut.isPending}
                    onClick={() => payMut.mutate({ id: selected.id, amount: payAmount, method: payMethod })}
                    className="w-full bg-brand-600 text-white py-1.5 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
                    {payMut.isPending ? "Saving…" : isCreditNote ? "Record refund" : "Record Payment"}
                  </button>
                </div>
              </div>
            )}

            {/* action buttons in panel */}
            <div className="flex gap-2 border-t border-gray-100 pt-3">
              {selected.status === "DRAFT" && (
                <button onClick={() => issueMut.mutate(selected.id)}
                  disabled={issueMut.isPending}
                  className="flex-1 bg-brand-600 text-white py-1.5 rounded-md text-xs font-medium hover:bg-brand-700 disabled:opacity-50">
                  Issue Invoice
                </button>
              )}
              {(selected.status === "DRAFT" || selected.status === "ISSUED") && !isCreditNote && (
                <button onClick={() => cancelMut.mutate(selected.id)}
                  disabled={cancelMut.isPending}
                  className="flex-1 border border-gray-300 text-gray-600 py-1.5 rounded-lg text-xs font-medium hover:border-red-400 hover:text-red-600 disabled:opacity-50">
                  Cancel
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
