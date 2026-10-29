"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getMovementsPage, createMovement, getProducts, getWarehouses, getCategories } from "@/lib/api";
import { StockMovement, Product, Warehouse, Category, Page } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";

export default function StockMovementsPage() {
  const qc = useQueryClient();

  const [filterCategoryId, setFilterCategoryId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [formCategoryId, setFormCategoryId] = useState<number | undefined>(undefined);
  const [form, setForm] = useState({ productId: "", warehouseId: "", quantity: "", type: "ADJUSTMENT_IN", notes: "" });
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState("");

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data, isLoading } = useQuery<Page<StockMovement>>({
    queryKey: ["movements-page", filterCategoryId, page],
    queryFn: () => getMovementsPage({ categoryId: filterCategoryId, page, size: 20 }),
  });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const movements = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const createMutation = useMutation({
    mutationFn: createMovement,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["movements-page"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      qc.invalidateQueries({ queryKey: ["products-page"] });
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      setShowForm(false);
      setFormCategoryId(undefined);
      setForm({ productId: "", warehouseId: "", quantity: "", type: "ADJUSTMENT_IN", notes: "" });
      setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to record movement"),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      productId: Number(form.productId),
      warehouseId: Number(form.warehouseId),
      quantity: Number(form.quantity),
      type: form.type,
      notes: form.notes,
    });
  };

  const formProducts = formCategoryId
    ? products.filter((p) => p.categoryId === formCategoryId)
    : products;

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Stock Movements</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="bg-amber-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-amber-700 transition-colors"
        >
          {showForm ? "Cancel" : "+ Record Movement"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 gap-4">
          <h2 className="col-span-2 font-semibold text-gray-800">New Stock Movement</h2>
          {error && <p className="col-span-2 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div>
            <label htmlFor="stock-movements-filter-by-category" className="block text-xs font-medium text-gray-600 mb-1">Filter by Category</label>
            <select id="stock-movements-filter-by-category"
              value={formCategoryId ?? ""}
              onChange={(e) => {
                setFormCategoryId(e.target.value ? Number(e.target.value) : undefined);
                setForm({ ...form, productId: "" });
              }}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            >
              <option value="">All categories</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>

          <div>
            <label htmlFor="stock-movements-product" className="block text-xs font-medium text-gray-600 mb-1">Product *</label>
            <select id="stock-movements-product"
              value={form.productId}
              onChange={(e) => setForm({ ...form, productId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            >
              <option value="">Select product</option>
              {formProducts.map((p) => <option key={p.id} value={p.id}>{p.sku} — {p.name}</option>)}
            </select>
          </div>

          <div>
            <label htmlFor="stock-movements-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Warehouse *</label>
            <select id="stock-movements-warehouse"
              value={form.warehouseId}
              onChange={(e) => setForm({ ...form, warehouseId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            >
              <option value="">Select warehouse</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>

          <div>
            <label htmlFor="stock-movements-type" className="block text-xs font-medium text-gray-600 mb-1">Type *</label>
            <select id="stock-movements-type"
              value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            >
              <option value="ADJUSTMENT_IN">ADJUSTMENT IN</option>
              <option value="ADJUSTMENT_OUT">ADJUSTMENT OUT</option>
            </select>
          </div>

          <div>
            <label htmlFor="stock-movements-quantity" className="block text-xs font-medium text-gray-600 mb-1">Quantity *</label>
            <input id="stock-movements-quantity"
              type="number" min="0.0001" step="any"
              value={form.quantity}
              onChange={(e) => setForm({ ...form, quantity: e.target.value })}
              required
              placeholder="e.g. 100"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            />
          </div>

          <div>
            <label htmlFor="stock-movements-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
            <input id="stock-movements-notes"
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
              placeholder="Optional notes"
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400"
            />
          </div>

          <div className="col-span-2 flex justify-end">
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="bg-amber-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-amber-700 disabled:opacity-50"
            >
              {createMutation.isPending ? "Saving..." : "Record Movement"}
            </button>
          </div>
        </form>
      )}

      <div className="flex items-center gap-3 mb-4">
        <select
          value={filterCategoryId ?? ""}
          onChange={(e) => {
            setFilterCategoryId(e.target.value ? Number(e.target.value) : undefined);
            setPage(0);
          }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400 w-48"
        >
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </select>
      </div>

      {isLoading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Type", "Product", "Warehouse", "Quantity", "Ref ID", "Date"].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {movements.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">No movements yet</td></tr>
                )}
                {movements.map((m) => (
                  <tr key={m.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <span className={`text-xs font-bold px-2 py-0.5 rounded whitespace-nowrap ${
                        m.type.endsWith("_IN") ? "bg-emerald-100 text-emerald-700" : "bg-red-100 text-red-700"
                      }`}>
                        {m.type.replace("_", " ")}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium">{m.productName}</div>
                      <div className="text-xs text-gray-400">{m.productSku}</div>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{m.warehouseName}</td>
                    <td className="px-4 py-3 font-semibold">{Number(m.quantity).toLocaleString()}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs">{m.referenceId || "—"}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={m.createdAt} showTime /></td>
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
