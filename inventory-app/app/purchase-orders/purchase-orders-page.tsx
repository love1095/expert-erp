"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrders, createPurchaseOrder, receivePurchaseOrder, cancelPurchaseOrder, getProducts, getWarehouses, getCategories, getSuppliers } from "@/lib/api";
import { PurchaseOrder, Product, Warehouse, PurchaseOrderStatus, Category, Supplier, Page } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";

const STATUS_COLOR: Record<PurchaseOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  RECEIVED:  "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

export default function PurchaseOrdersPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<PurchaseOrder>>({
    queryKey: ["purchase-orders", page],
    queryFn: () => getPurchaseOrders({ page }),
  });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: suppliers = [] } = useQuery<Supplier[]>({ queryKey: ["suppliers"], queryFn: getSuppliers });

  const orders: PurchaseOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const [supplierName, setSupplierName] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState([{ categoryId: "", productId: "", quantity: "", unitCost: "" }]);
  const [error, setError] = useState("");

  const createMutation = useMutation({
    mutationFn: createPurchaseOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["purchase-orders"] });
      setShowForm(false);
      resetForm();
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create order"),
  });

  const receiveMutation = useMutation({
    mutationFn: receivePurchaseOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["purchase-orders"] });
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      qc.invalidateQueries({ queryKey: ["products-page"] });
    },
    onError: (e: any) => alert(e.response?.data?.error || "Failed to receive"),
  });

  const cancelMutation = useMutation({
    mutationFn: cancelPurchaseOrder,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["purchase-orders"] }),
  });

  const resetForm = () => { setSupplierName(""); setWarehouseId(""); setNotes(""); setLines([{ categoryId: "", productId: "", quantity: "", unitCost: "" }]); setError(""); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      supplierName, warehouseId: Number(warehouseId), notes,
      lines: lines.map(l => ({ productId: Number(l.productId), quantity: Number(l.quantity), unitCost: l.unitCost ? Number(l.unitCost) : null })),
    });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Purchase Orders</h1>
        <button onClick={() => { setShowForm(!showForm); if (showForm) resetForm(); }}
          className="bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New PO"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">New Purchase Order</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="purchase-orders-supplier" className="block text-xs font-medium text-gray-600 mb-1">Supplier *</label>
              <select id="purchase-orders-supplier" value={supplierName} onChange={e => setSupplierName(e.target.value)} required
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
                <option value="">Select supplier</option>
                {suppliers.map(s => <option key={s.id} value={s.name}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="purchase-orders-receive-to-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Receive to Warehouse *</label>
              <select id="purchase-orders-receive-to-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} required
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
                <option value="">Select warehouse</option>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs font-medium text-gray-600">Lines *</label>
              <button type="button" onClick={() => setLines([...lines, { categoryId: "", productId: "", quantity: "", unitCost: "" }])}
                className="text-xs text-brand-600 hover:text-brand-800">+ Add line</button>
            </div>
            {lines.map((l, i) => (
              <div key={i} className="grid grid-cols-6 gap-2 mb-2">
                <div>
                  <select value={l.categoryId} onChange={e => { const nl = [...lines]; nl[i].categoryId = e.target.value; nl[i].productId = ""; setLines(nl); }}
                    className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400">
                    <option value="">All categories</option>
                    {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
                <div className="col-span-2">
                  <select value={l.productId} onChange={e => { const nl = [...lines]; nl[i].productId = e.target.value; setLines(nl); }} required
                    className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400">
                    <option value="">Product</option>
                    {(l.categoryId ? products.filter(p => p.categoryId === Number(l.categoryId)) : products).map(p => <option key={p.id} value={p.id}>{p.sku}</option>)}
                  </select>
                </div>
                <input type="number" min="0.0001" step="any" value={l.quantity} placeholder="Qty" required
                  onChange={e => { const nl = [...lines]; nl[i].quantity = e.target.value; setLines(nl); }}
                  className="border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400" />
                <input type="number" min="0" step="any" value={l.unitCost} placeholder="£ unit cost"
                  onChange={e => { const nl = [...lines]; nl[i].unitCost = e.target.value; setLines(nl); }}
                  className="border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400" />
                <button type="button" onClick={() => setLines(lines.filter((_, j) => j !== i))}
                  className="text-red-400 hover:text-red-600 text-xs">Remove</button>
              </div>
            ))}
          </div>

          <div>
            <label htmlFor="purchase-orders-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
            <input id="purchase-orders-notes" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Optional"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
          </div>

          <button type="submit" disabled={createMutation.isPending}
            className="bg-brand-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {createMutation.isPending ? "Saving..." : "Create Purchase Order"}
          </button>
        </form>
      )}

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["#", "Supplier", "Warehouse", "Lines", "Total (£)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No purchase orders yet</td></tr>}
                {orders.map(o => (
                  <tr key={o.id} data-testid={`po-row-${o.id}`} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">PO-{o.id}</td>
                    <td className="px-4 py-3 font-medium">{o.supplierName}</td>
                    <td className="px-4 py-3 text-gray-600">{o.warehouseName}</td>
                    <td className="px-4 py-3 text-gray-500">{o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">
                      {formatGBP(o.lines.reduce((sum, l) => sum + (l.quantity ?? 0) * (l.unitCost ?? 0), 0))}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right space-x-2">
                      {o.status === "DRAFT" && (
                        <>
                          <button onClick={() => { if (confirm("Mark as received? This will add stock.")) receiveMutation.mutate(o.id); }}
                            className="text-xs text-emerald-600 hover:text-emerald-800 font-medium">Receive</button>
                          <button onClick={() => { if (confirm("Cancel this PO?")) cancelMutation.mutate(o.id); }}
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
