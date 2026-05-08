"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSaleOrders, createSaleOrder, fulfillSaleOrder, cancelSaleOrder, getProducts, getWarehouses, getCategories } from "@/lib/api";
import { SaleOrder, Product, Warehouse, SaleOrderStatus, Category, Page } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";

const STATUS_COLOR: Record<SaleOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  FULFILLED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

export default function SaleOrdersPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", page],
    queryFn: () => getSaleOrders({ page }),
  });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });

  const orders: SaleOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const [customerName, setCustomerName] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState([{ categoryId: "", productId: "", quantity: "", unitPrice: "" }]);
  const [error, setError] = useState("");

  const createMutation = useMutation({
    mutationFn: createSaleOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sale-orders"] });
      setShowForm(false);
      resetForm();
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create order"),
  });

  const fulfillMutation = useMutation({
    mutationFn: fulfillSaleOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sale-orders"] });
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      qc.invalidateQueries({ queryKey: ["products-page"] });
    },
    onError: (e: any) => alert(e.response?.data?.error || "Failed to fulfill"),
  });

  const cancelMutation = useMutation({
    mutationFn: cancelSaleOrder,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sale-orders"] }),
  });

  const resetForm = () => { setCustomerName(""); setWarehouseId(""); setNotes(""); setLines([{ categoryId: "", productId: "", quantity: "", unitPrice: "" }]); setError(""); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      customerName, warehouseId: Number(warehouseId), notes,
      lines: lines.map(l => ({ productId: Number(l.productId), quantity: Number(l.quantity), unitPrice: l.unitPrice ? Number(l.unitPrice) : null })),
    });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Sale Orders</h1>
        <button onClick={() => { setShowForm(!showForm); if (showForm) resetForm(); }}
          className="bg-rose-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-rose-700">
          {showForm ? "Cancel" : "+ New SO"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">New Sale Order</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Customer *</label>
              <input value={customerName} onChange={e => setCustomerName(e.target.value)} required placeholder="Customer name"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Ship from Warehouse *</label>
              <select value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required
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
            <label className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
            <input value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
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
                  <tr key={o.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">SO-{o.id}</td>
                    <td className="px-4 py-3 font-medium">{o.customerName}</td>
                    <td className="px-4 py-3 text-gray-600">{o.warehouseName}</td>
                    <td className="px-4 py-3 text-gray-500">{o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">
                      {formatGBP(o.lines.reduce((sum, l) => sum + (l.quantity ?? 0) * (l.unitPrice ?? 0), 0))}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right space-x-2">
                      {o.status === "DRAFT" && (
                        <>
                          <button onClick={() => { if (confirm("Fulfill this order? This will deduct stock.")) fulfillMutation.mutate(o.id); }}
                            className="text-xs text-emerald-600 hover:text-emerald-800 font-medium">Fulfill</button>
                          <button onClick={() => { if (confirm("Cancel this SO?")) cancelMutation.mutate(o.id); }}
                            className="text-xs text-red-500 hover:text-red-700">Cancel</button>
                        </>
                      )}
                    </td>
                  </tr>
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
