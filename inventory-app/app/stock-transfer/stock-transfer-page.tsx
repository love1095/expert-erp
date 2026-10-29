"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { createTransfer, getProducts, getWarehouses, getCategories } from "@/lib/api";
import { Product, Warehouse, StockMovement, Category } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";

export default function StockTransferPage() {
  const qc = useQueryClient();

  const [categoryId, setCategoryId] = useState<number | undefined>(undefined);
  const [form, setForm] = useState({ productId: "", fromWarehouseId: "", toWarehouseId: "", quantity: "", notes: "" });
  const [result, setResult] = useState<StockMovement[] | null>(null);
  const [error, setError] = useState("");

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: products = [] } = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const mutation = useMutation({
    mutationFn: createTransfer,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      qc.invalidateQueries({ queryKey: ["movements"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      setResult(data);
      setForm({ productId: "", fromWarehouseId: "", toWarehouseId: "", quantity: "", notes: "" });
      setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Transfer failed"),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setResult(null);
    mutation.mutate({
      productId: Number(form.productId),
      fromWarehouseId: Number(form.fromWarehouseId),
      toWarehouseId: Number(form.toWarehouseId),
      quantity: Number(form.quantity),
      notes: form.notes,
    });
  };

  const filteredProducts = categoryId
    ? products.filter((p) => p.categoryId === categoryId)
    : products;

  return (
    <div className="max-w-xl">
      <h1 className="text-2xl font-bold mb-1">Stock Transfer</h1>
      <p className="text-gray-500 text-sm mb-6">Move stock between warehouses — creates TRANSFER_OUT + TRANSFER_IN atomically</p>

      <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
        {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

        {result && (
          <div className="bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-3 text-sm text-emerald-800">
            Transfer complete — ref <span className="font-mono font-bold">{result[0]?.referenceId}</span>
          </div>
        )}

        <div>
          <label htmlFor="stock-transfer-filter-by-category" className="block text-xs font-medium text-gray-600 mb-1">Filter by Category</label>
          <select id="stock-transfer-filter-by-category"
            value={categoryId ?? ""}
            onChange={(e) => {
              setCategoryId(e.target.value ? Number(e.target.value) : undefined);
              setForm({ ...form, productId: "" });
            }}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            <option value="">All categories</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>

        <div>
          <label htmlFor="stock-transfer-product" className="block text-xs font-medium text-gray-600 mb-1">Product *</label>
          <select id="stock-transfer-product"
            value={form.productId}
            onChange={(e) => setForm({ ...form, productId: e.target.value })}
            required
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            <option value="">Select product</option>
            {filteredProducts.map((p) => (
              <option key={p.id} value={p.id}>{p.sku} — {p.name} (stock: {Number(p.totalStock).toLocaleString()})</option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="stock-transfer-from-warehouse" className="block text-xs font-medium text-gray-600 mb-1">From Warehouse *</label>
            <select id="stock-transfer-from-warehouse"
              value={form.fromWarehouseId}
              onChange={(e) => setForm({ ...form, fromWarehouseId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            >
              <option value="">Select</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="stock-transfer-to-warehouse" className="block text-xs font-medium text-gray-600 mb-1">To Warehouse *</label>
            <select id="stock-transfer-to-warehouse"
              value={form.toWarehouseId}
              onChange={(e) => setForm({ ...form, toWarehouseId: e.target.value })}
              required
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            >
              <option value="">Select</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
        </div>

        <div>
          <label htmlFor="stock-transfer-quantity" className="block text-xs font-medium text-gray-600 mb-1">Quantity *</label>
          <input id="stock-transfer-quantity"
            type="number" min="0.0001" step="any"
            value={form.quantity}
            onChange={(e) => setForm({ ...form, quantity: e.target.value })}
            required
            placeholder="e.g. 50"
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
        </div>

        <div>
          <label htmlFor="stock-transfer-notes" className="block text-xs font-medium text-gray-600 mb-1">Notes</label>
          <input id="stock-transfer-notes"
            value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
            placeholder="Optional reason"
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
        </div>

        <button
          type="submit"
          disabled={mutation.isPending}
          className="w-full bg-indigo-600 text-white py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
        >
          {mutation.isPending ? "Processing..." : "Transfer Stock"}
        </button>
      </form>
    </div>
  );
}
