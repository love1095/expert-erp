"use client";

import { useQuery } from "@tanstack/react-query";
import { getMovementsPage, getProducts, getWarehouses, getCategories, exportMovementsCsv } from "@/lib/api";
import { StockMovement, Product, Warehouse, Category, Page } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";

const TYPE_COLORS: Record<string, string> = {
  PURCHASE_IN:    "bg-emerald-100 text-emerald-700",
  ADJUSTMENT_IN:  "bg-blue-100 text-blue-700",
  TRANSFER_IN:    "bg-sky-100 text-sky-700",
  SALE_RETURN_IN: "bg-teal-100 text-teal-700",
  SALE_OUT:       "bg-red-100 text-red-700",
  ADJUSTMENT_OUT: "bg-orange-100 text-orange-700",
  TRANSFER_OUT:   "bg-purple-100 text-purple-700",
};

export default function StockHistoryPage() {
  const [categoryId, setCategoryId] = useState("");
  const [productId, setProductId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [page, setPage] = useState(0);

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: products = [] }   = useQuery<Product[]>({ queryKey: ["products"], queryFn: () => getProducts() });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const { data, isLoading } = useQuery<Page<StockMovement>>({
    queryKey: ["movements-page", productId, warehouseId, categoryId, page],
    queryFn: () => getMovementsPage({
      productId:   productId   ? Number(productId)   : undefined,
      warehouseId: warehouseId ? Number(warehouseId) : undefined,
      categoryId:  categoryId && !productId ? Number(categoryId) : undefined,
      page,
      size: 20,
    }),
  });

  const movements = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const filteredProducts = categoryId
    ? products.filter((p) => p.categoryId === Number(categoryId))
    : products;

  const resetPage = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement>) => {
    setter(e.target.value);
    setPage(0);
  };

  return (
    <div>
      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold mb-1">Stock History</h1>
          <p className="text-gray-500 text-sm">Full movement timeline — filter by category, product, or warehouse</p>
        </div>
        <button
          onClick={async () => {
            const blob = await exportMovementsCsv({
              productId:   productId   ? Number(productId)   : undefined,
              warehouseId: warehouseId ? Number(warehouseId) : undefined,
            });
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url; a.download = "movements.csv"; a.click();
            URL.revokeObjectURL(url);
          }}
          className="text-sm border border-gray-300 rounded-lg px-4 py-2 hover:bg-gray-50 text-gray-600"
        >
          Export CSV
        </button>
      </div>

      <div className="flex gap-3 mb-5">
        <select
          value={categoryId}
          onChange={resetPage((v) => { setCategoryId(v); setProductId(""); })}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 w-48"
        >
          <option value="">All categories</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>

        <select
          value={productId}
          onChange={resetPage(setProductId)}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 w-64"
        >
          <option value="">All products</option>
          {filteredProducts.map((p) => <option key={p.id} value={p.id}>{p.sku} — {p.name}</option>)}
        </select>

        <select
          value={warehouseId}
          onChange={resetPage(setWarehouseId)}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 w-48"
        >
          <option value="">All warehouses</option>
          {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
      </div>

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Type", "Product", "Warehouse", "Qty", "Ref Type", "Ref ID", "Notes", "Date"].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {movements.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No movements found</td></tr>
                )}
                {movements.map((m) => (
                  <tr key={m.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded whitespace-nowrap ${TYPE_COLORS[m.type] || "bg-gray-100 text-gray-600"}`}>
                        {m.type.replace("_", " ")}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium text-xs">{m.productName}</div>
                      <div className="text-gray-400 text-xs">{m.productSku}</div>
                    </td>
                    <td className="px-4 py-3 text-gray-600 text-xs">{m.warehouseName}</td>
                    <td className="px-4 py-3 font-semibold">{Number(m.quantity).toLocaleString()}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs">{m.referenceType?.replace("_", " ") || "—"}</td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-500">{m.referenceId || "—"}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs">{m.notes || "—"}</td>
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
