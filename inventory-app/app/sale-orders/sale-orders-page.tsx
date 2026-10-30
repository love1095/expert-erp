"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSaleOrders, createSaleOrder, getProducts, getWarehouses, getCategories, getCustomers } from "@/lib/api";
import { SaleOrder, Product, Warehouse, SaleOrderStatus, FulfilmentStatus, Category, Customer, Page } from "@/types";
import { Fragment, useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import SaleOrderPanel from "./sale-order-panel";
import { ShopifyImportButton, ShopifyImportPanel } from "./shopify-import";
import { useAuth } from "@/contexts/AuthContext";

const STATUS_COLOR: Record<SaleOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  CONFIRMED: "bg-sky-100 text-sky-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

const FULFILMENT_LABEL: Record<FulfilmentStatus, { label: string; color: string }> = {
  UNFULFILLED:         { label: "Not shipped", color: "text-gray-400" },
  PARTIALLY_FULFILLED: { label: "Partly shipped", color: "text-amber-600" },
  FULFILLED:           { label: "Shipped", color: "text-emerald-600" },
};

export default function SaleOrdersPage() {
  const { user } = useAuth();
  // The Shopify import is admin-only (like the Shopify settings)
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const [showShopifyImport, setShowShopifyImport] = useState(false);
  const qc = useQueryClient();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", page],
    queryFn: () => getSaleOrders({ page }),
  });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers });

  const orders: SaleOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const [customerName, setCustomerName] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState([{ categoryId: "", productId: "", quantity: "", unitPrice: "" }]);
  const [error, setError] = useState("");
  const [openId, setOpenId] = useState<number | null>(null);

  const createMutation = useMutation({
    mutationFn: createSaleOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sale-orders"] });
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customers-page"] });
      setShowForm(false);
      resetForm();
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create order"),
  });

  const resetForm = () => { setCustomerName(""); setWarehouseId(""); setNotes(""); setLines([{ categoryId: "", productId: "", quantity: "", unitPrice: "" }]); setError(""); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // A name matching an existing customer orders for that customer; any other name creates a new one
    const existing = customers.find(c => c.name.toLowerCase() === customerName.trim().toLowerCase());
    createMutation.mutate({
      customerId: existing?.id, customerName: existing ? undefined : customerName.trim(),
      warehouseId: Number(warehouseId), notes,
      lines: lines.map(l => ({ productId: Number(l.productId), quantity: Number(l.quantity), unitPrice: l.unitPrice ? Number(l.unitPrice) : null })),
    });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Sale Orders</h1>
        <div className="flex gap-2">
          {isAdmin && <ShopifyImportButton onOpen={() => setShowShopifyImport(true)} />}
          <button onClick={() => { setShowForm(!showForm); if (showForm) resetForm(); }}
            className="bg-rose-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-rose-700">
            {showForm ? "Cancel" : "+ New SO"}
          </button>
        </div>
      </div>

      {isAdmin && showShopifyImport && <ShopifyImportPanel onClose={() => setShowShopifyImport(false)} />}

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">New Sale Order</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="sale-orders-customer" className="block text-xs font-medium text-gray-600 mb-1">Customer *</label>
              <input id="sale-orders-customer" list="sale-orders-customer-list" value={customerName} onChange={e => setCustomerName(e.target.value)} required
                placeholder="Pick a customer or type a new name" autoComplete="off"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
              <datalist id="sale-orders-customer-list">
                {customers.filter(c => c.status === "ACTIVE").map(c => <option key={c.id} value={c.name}>{c.contacts[0]?.email ?? ""}</option>)}
              </datalist>
              {customerName.trim() && !customers.some(c => c.name.toLowerCase() === customerName.trim().toLowerCase()) && (
                <p className="text-[11px] text-gray-400 mt-1">New customer — will be added to Customers</p>
              )}
            </div>
            <div>
              <label htmlFor="sale-orders-ship-from-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Ship from Warehouse *</label>
              <select id="sale-orders-ship-from-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400">
                <option value="">Select warehouse</option>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs font-medium text-gray-600">Lines *</label>
              <button type="button" onClick={() => setLines([...lines, { categoryId: "", productId: "", quantity: "", unitPrice: "" }])}
                className="text-xs text-rose-600 hover:text-rose-800">+ Add line</button>
            </div>
            {lines.map((l, i) => (
              <div key={i} className="grid grid-cols-6 gap-2 mb-2">
                <div>
                  <select value={l.categoryId} onChange={e => { const nl = [...lines]; nl[i].categoryId = e.target.value; nl[i].productId = ""; setLines(nl); }}
                    className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-rose-400">
                    <option value="">All categories</option>
                    {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
                <div className="col-span-2">
                  <select value={l.productId} onChange={e => { const nl = [...lines]; nl[i].productId = e.target.value; setLines(nl); }} required
                    className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-rose-400">
                    <option value="">Product</option>
                    {(l.categoryId ? products.filter(p => p.categoryId === Number(l.categoryId)) : products).map(p => <option key={p.id} value={p.id}>{p.sku}</option>)}
                  </select>
                </div>
                <input type="number" min="0.0001" step="any" value={l.quantity} placeholder="Qty" required
                  onChange={e => { const nl = [...lines]; nl[i].quantity = e.target.value; setLines(nl); }}
                  className="border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-rose-400" />
                <input type="number" min="0" step="any" value={l.unitPrice} placeholder="£ unit price"
                  onChange={e => { const nl = [...lines]; nl[i].unitPrice = e.target.value; setLines(nl); }}
                  className="border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-rose-400" />
                <button type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))}
                  className="text-red-400 hover:text-red-600 text-xs">Remove</button>
              </div>
            ))}
          </div>

          <div>
            <label htmlFor="sale-orders-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
            <input id="sale-orders-notes" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
          </div>

          <button type="submit" disabled={createMutation.isPending}
            className="bg-rose-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-rose-700 disabled:opacity-50">
            {createMutation.isPending ? "Saving..." : "Create Sale Order"}
          </button>
        </form>
      )}

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["#", "Customer", "Warehouse", "Lines", "Total (£)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No sale orders yet</td></tr>}
                {orders.map(o => (
                  <Fragment key={o.id}>
                  <tr data-testid={`so-row-${o.id}`} className={`hover:bg-gray-50 ${openId === o.id ? "bg-gray-50" : ""}`}>
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">
                      SO-{o.id}
                      {o.source === "SHOPIFY" && (
                        <span className="block mt-1 font-sans text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 w-fit">
                          Shopify {o.externalReference}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-medium">{o.customerName}</td>
                    <td className="px-4 py-3 text-gray-600">{o.warehouseName}</td>
                    <td className="px-4 py-3 text-gray-500">{o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">
                      {formatGBP(o.lines.reduce((sum, l) => sum + (l.quantity ?? 0) * (l.unitPrice ?? 0), 0))}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                      {o.needsReview && (
                        <span title={o.reviewReason} className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-amber-100 text-amber-800">Needs review</span>
                      )}
                      {o.status !== "DRAFT" && o.status !== "CANCELLED" && (
                        <span className={`block mt-1 text-[11px] ${FULFILMENT_LABEL[o.fulfilmentStatus].color}`}>
                          {FULFILMENT_LABEL[o.fulfilmentStatus].label}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => setOpenId(openId === o.id ? null : o.id)}
                        className="text-xs text-rose-600 hover:text-rose-800 font-medium">
                        {openId === o.id ? "Close" : "Manage"}
                      </button>
                    </td>
                  </tr>
                  {openId === o.id && (
                    <tr>
                      <td colSpan={8} className="p-0">
                        <SaleOrderPanel orderId={o.id} warehouses={warehouses} />
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
