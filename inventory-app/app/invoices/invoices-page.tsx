"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getInvoices, createInvoice, issueInvoice, cancelInvoice,
  getProducts, getSaleOrders, getPurchaseOrders,
} from "@/lib/api";
import { useRouter } from "next/navigation";
import { Invoice, InvoiceType, Page, Product, SaleOrder, PurchaseOrder } from "@/types";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import { formatGBP } from "@/lib/currency";
import { INVOICE_STATUS_COLOR as STATUS_COLOR } from "./invoice-view";

// ─── constants ──────────────────────────────────────────────────────────────

const CURRENCIES = ["USD", "EUR", "GBP", "VND"];

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
  // Opened from an order with ?q=INV-1
  useEffect(() => {
    const q = new URLSearchParams(window.location.search).get("q");
    if (q) setSearch(q);
  }, []);
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

  const router = useRouter();

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
    onSuccess:  () => qc.invalidateQueries({ queryKey: ["invoices"] }),
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to issue invoice"),
  });

  const cancelMut = useMutation({
    mutationFn: (id: number) => cancelInvoice(id),
    onSuccess:  () => qc.invalidateQueries({ queryKey: ["invoices"] }),
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to cancel invoice"),
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

      <div>
        <div>
          {isLoading ? (
            <p className="text-gray-400 text-sm">Loading…</p>
          ) : (
            <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
              <table className="list-table">
                <ListHead cols={["Number", "Type", "Party", "Status", "Due", "Total", "Paid", ""]} right={["Total", "Paid"]} />
                <tbody className="divide-y divide-gray-100">
                  {invoiceList.length === 0 && (
                    <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400 text-sm">{search || statusFilter || typeFilter ? "No invoices match" : "No invoices yet"}</td></tr>
                  )}
                  {invoiceList.map(inv => (
                    <tr key={inv.id}
                      data-testid={`invoice-row-${inv.id}`}
                      onClick={() => router.push(`/invoices/${inv.id}`)}
                      className="hover:bg-gray-50 cursor-pointer">
                      <td className="font-mono text-xs text-gray-900 font-semibold">
                        {inv.invoiceNumber}
                        {inv.kind === "CREDIT_NOTE" && <span className="block font-sans text-[10px] font-semibold text-gray-500">Credit note</span>}
                      </td>
                      <td>
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${
                          inv.type === "SALES" ? "bg-sky-100 text-sky-700" : "bg-purple-100 text-purple-700"
                        }`}>{inv.type}</span>
                      </td>
                      <td className="text-gray-700"><Clip text={inv.partyName} width="16rem" /></td>
                      <td>
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[inv.status]}`}>
                          {inv.status}
                        </span>
                      </td>
                      <td className="text-gray-500">
                        {inv.dueDate ? new Date(inv.dueDate).toLocaleDateString("en-GB") : "—"}
                      </td>
                      <td className="num text-gray-800">{formatGBP(inv.totalAmount)}</td>
                      <td className="num text-gray-500">{inv.amountPaid > 0 ? formatGBP(inv.amountPaid) : "—"}</td>
                      <td className="text-right space-x-2">
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

      </div>
    </div>
  );
}
