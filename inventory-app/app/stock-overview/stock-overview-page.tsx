"use client";

import { useQuery } from "@tanstack/react-query";
import { getStockOverviewPage, getCategories } from "@/lib/api";
import { StockLevel, Category, Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";
import StockImport from "./stock-import";

export default function StockOverviewPage() {
  const [categoryId, setCategoryId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [search, setSearch] = useState("");
  const [showImport, setShowImport] = useState(false);

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });

  const { data, isLoading, isFetching, refetch } = useQuery<Page<StockLevel>>({
    queryKey: ["stock-overview", categoryId, page, size, search],
    queryFn: () => getStockOverviewPage({ categoryId, page, size, q: search || undefined }),
  });

  const levels = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  return (
    <div>
      <PageHeader title="Stock Overview" subtitle="On hand, reserved and available per product and warehouse">
        <button onClick={() => setShowImport(!showImport)}
          className="border border-gray-300 bg-white text-gray-700 px-4 py-2 rounded-md text-sm font-medium hover:bg-gray-50">
          Import stock
        </button>
      </PageHeader>

      {showImport && <StockImport onImported={() => refetch()} />}

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        placeholder="SKU or product name"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
          <label htmlFor="stock-category-filter" className="sr-only">Category</label>
          <select id="stock-category-filter"
            value={categoryId ?? ""}
            onChange={e => { setCategoryId(e.target.value ? Number(e.target.value) : undefined); setPage(0); }}
            className={`${FILTER_SELECT} w-48`}>
            <option value="">All categories</option>
            {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </>} />

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
                        <span className="inline-block bg-brand-50 text-brand-700 text-xs px-2 py-0.5 rounded-full font-medium">
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
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage}
            size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
        </>
      )}
    </div>
  );
}
