"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getProductsPage, createProduct, deleteProduct, getCategories } from "@/lib/api";
import { Product, Category, Page } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useState } from "react";
import Pagination from "@/components/Pagination";

export default function ProductsPage() {
  const qc = useQueryClient();

  const [selectedCategoryId, setSelectedCategoryId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [form, setForm] = useState({ sku: "", name: "", description: "", unit: "", categoryId: "" });
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState("");

  const { data: categories = [] } = useQuery<Category[]>({
    queryKey: ["categories"],
    queryFn: getCategories,
  });

  const { data, isLoading } = useQuery<Page<Product>>({
    queryKey: ["products-page", selectedCategoryId, page],
    queryFn: () => getProductsPage({ categoryId: selectedCategoryId, page, size: 20 }),
  });

  const products = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const createMutation = useMutation({
    mutationFn: createProduct,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["products-page"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      setShowForm(false);
      setForm({ sku: "", name: "", description: "", unit: "", categoryId: "" });
      setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create product"),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteProduct,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["products-page"] });
      qc.invalidateQueries({ queryKey: ["products"] });
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({
      ...form,
      categoryId: form.categoryId ? Number(form.categoryId) : null,
    });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Products</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 transition-colors"
        >
          {showForm ? "Cancel" : "+ Add Product"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 gap-4">
          <h2 className="col-span-2 font-semibold text-gray-800">New Product</h2>
          {error && <p className="col-span-2 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}
          {[
            { label: "SKU *", key: "sku", placeholder: "e.g. PROD-001" },
            { label: "Name *", key: "name", placeholder: "Product name" },
            { label: "Unit *", key: "unit", placeholder: "e.g. pcs, kg, box" },
            { label: "Description", key: "description", placeholder: "Optional description" },
          ].map(({ label, key, placeholder }) => (
            <div key={key}>
              <label className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
              <input
                value={form[key as keyof typeof form]}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })}
                placeholder={placeholder}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
              />
            </div>
          ))}
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Category</label>
            <select
              value={form.categoryId}
              onChange={(e) => setForm({ ...form, categoryId: e.target.value })}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            >
              <option value="">— None —</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </div>
          <div className="col-span-2 flex justify-end">
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
            >
              {createMutation.isPending ? "Saving..." : "Save Product"}
            </button>
          </div>
        </form>
      )}

      <div className="flex items-center gap-3 mb-4">
        <select
          value={selectedCategoryId ?? ""}
          onChange={(e) => {
            setSelectedCategoryId(e.target.value ? Number(e.target.value) : undefined);
            setPage(0);
          }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 w-48"
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
                  {["SKU", "Name", "Category", "Unit", "Cost Price", "Total Stock", ""].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {products.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">No products yet</td></tr>
                )}
                {products.map((p) => (
                  <tr key={p.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs text-gray-500">{p.sku}</td>
                    <td className="px-4 py-3 font-medium">{p.name}</td>
                    <td className="px-4 py-3">
                      {p.categoryName ? (
                        <span className="inline-block bg-indigo-50 text-indigo-700 text-xs px-2 py-0.5 rounded-full font-medium">
                          {p.categoryName}
                        </span>
                      ) : (
                        <span className="text-gray-300 text-xs">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-500">{p.unit}</td>
                    <td className="px-4 py-3 text-gray-600 tabular-nums">{formatGBP(p.costPrice)}</td>
                    <td className="px-4 py-3">
                      <span className={`font-semibold ${Number(p.totalStock) <= 0 ? "text-red-600" : "text-emerald-700"}`}>
                        {Number(p.totalStock).toLocaleString()}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => { if (confirm(`Delete "${p.name}"?`)) deleteMutation.mutate(p.id); }}
                        className="text-xs text-red-500 hover:text-red-700"
                      >
                        Delete
                      </button>
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
