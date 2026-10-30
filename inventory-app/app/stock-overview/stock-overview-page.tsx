"use client";

import { useQuery } from "@tanstack/react-query";
import { getStockOverviewPage, getCategories } from "@/lib/api";
import { StockLevel, Category, Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import StockImport from "./stock-import";

export default function StockOverviewPage() {
  const [categoryId, setCategoryId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [showImport, setShowImport] = useState(false);

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });

  const { data, isLoading, refetch } = useQuery<Page<StockLevel>>({
    queryKey: ["stock-overview", categoryId, page],
    queryFn: () => getStockOverviewPage({ categoryId, page, size: 20 }),
  });

  const levels = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Stock Overview</h1>
          <p className="text-gray-500 text-sm mt-0.5">Current stock computed from all movements (IN − OUT)</p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => setShowImport(!showImport)}
            className="border border-gray-300 text-gray-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-50">
            Import stock
          </button>
          <button onClick={() => refetch()}
            className="text-sm text-sky-600 border border-sky-200 bg-sky-50 px-4 py-2 rounded-lg hover:bg-sky-100 transition-colors">
            Refresh
          </button>
        </div>
      </div>

      {showImport && <StockImport onImported={() => refetch()} />}

      <div className="mb-5">
        <select
          value={categoryId ?? ""}
          onChange={e => { setCategoryId(e.target.value ? Number(e.target.value) : undefined); setPage(0); }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 w-48"
        >
          <option value="">All categories</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
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
                  {["SKU", "Product", "Category", "Warehouse", "Unit", "Min Stock", "On hand", "Reserved", "Available", ""].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {levels.length === 0 && (
                  <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">No stock data — record some movements first</td></tr>
                )}
                {levels.map((l, i) => (
                  <tr key={i} className={`hover:bg-gray-50 ${l.lowStock ? "bg-red-50" : ""}`}>
                    <td className="px-4 py-3 font-mono text-xs text-gray-500">{l.productSku}</td>
                    <td className="px-4 py-3 font-medium">{l.productName}</td>
                    <td className="px-4 py-3">
                      {l.categoryName ? (
                        <span className="inline-block bg-indigo-50 text-indigo-700 text-xs px-2 py-0.5 rounded-full font-medium">
                          {l.categoryName}
                        </span>
                      ) : <span className="text-gray-300 text-xs">—</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{l.warehouseName}</td>
                    <td className="px-4 py-3 text-gray-500">{l.unit}</td>
                    <td className="px-4 py-3 text-gray-400 text-sm">{l.minStock != null ? Number(l.minStock).toLocaleString() : "—"}</td>
                    <td className="px-4 py-3">
                      <span className={`font-bold text-base ${Number(l.stock) <= 0 ? "text-red-600" : l.lowStock ? "text-amber-600" : "text-emerald-700"}`}>
                        {Number(l.stock).toLocaleString()}
                      </span>
                    </td>
                    <td className="px-4 py-3 tabular-nums text-gray-500">{Number(l.reserved) ? Number(l.reserved).toLocaleString() : "—"}</td>
                    <td className={`px-4 py-3 tabular-nums font-semibold ${Number(l.available) < 0 ? "text-red-600" : "text-gray-800"}`}>
                      {Number(l.available).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      {l.lowStock && (
                        <span className="text-xs font-semibold px-2 py-0.5 rounded bg-red-100 text-red-700">Low Stock</span>
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
