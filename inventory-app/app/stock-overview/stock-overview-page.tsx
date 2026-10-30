"use client";

import { useQuery } from "@tanstack/react-query";
import { getStockOverviewPage, getCategories } from "@/lib/api";
import { StockLevel, Category, Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import StockImport from "./stock-import";
import { formatGBP } from "@/lib/currency";

export default function StockOverviewPage() {
  const [categoryId, setCategoryId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [search, setSearch] = useState("");
  const [showImport, setShowImport] = useState(false);
  // Cin7's "Only active" and "Show only non-zero quantities"
  const [activeOnly, setActiveOnly] = useState(false);
  const [nonZero, setNonZero] = useState(false);

  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });

  const { data, isLoading, isFetching, refetch } = useQuery<Page<StockLevel>>({
    queryKey: ["stock-overview", categoryId, page, size, search, activeOnly, nonZero],
    queryFn: () => getStockOverviewPage({ categoryId, page, size, q: search || undefined, activeOnly, nonZero }),
  });

  const levels = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  return (
    <div>
      <PageHeader title="Stock Overview" subtitle="Availability per product and warehouse: on hand, allocated to orders, available and on order">
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
          <label className="flex items-center gap-1.5 text-sm text-gray-700">
            <input type="checkbox" checked={activeOnly} onChange={e => { setActiveOnly(e.target.checked); setPage(0); }} />
            Only active
          </label>
          <label className="flex items-center gap-1.5 text-sm text-gray-700 mr-2">
            <input type="checkbox" checked={nonZero} onChange={e => { setNonZero(e.target.checked); setPage(0); }} />
            Show only non-zero quantities
          </label>
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
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <ListHead cols={["Category", "Product", "Warehouse", "Min", "Stock value", "On hand", "Allocated", "Available", "On order", "Next delivery"]}
                right={["Min", "Stock value", "On hand", "Allocated", "Available", "On order"]} />
              <tbody className="divide-y divide-gray-100">
                {levels.length === 0 && (
                  <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">{search || nonZero || activeOnly || categoryId ? "Nothing matches" : "No stock data — record some movements first"}</td></tr>
                )}
                {levels.map(l => {
                  const id = `${l.productId}-${l.warehouseId}`;
                  return (
                    <tr key={id} data-testid={`stock-row-${id}`} title={l.lowStock ? "Low stock: at or below the minimum" : undefined}
                      className={`hover:bg-gray-50 ${l.lowStock ? "bg-red-50" : ""}`}>
                      <td className="text-gray-600"><Clip text={l.categoryName} width="8rem" /></td>
                      <td className="text-gray-800" title={`${l.productSku}: ${l.productName} (${l.unit})`}>
                        <Clip text={`${l.productSku}: ${l.productName}`} width="19rem" />
                      </td>
                      <td className="text-gray-600"><Clip text={l.warehouseName} width="9rem" /></td>
                      <td className="num text-gray-400">{l.minStock != null ? Number(l.minStock).toLocaleString() : "—"}</td>
                      <td className="num text-gray-600">{l.stockValue != null ? formatGBP(l.stockValue) : "—"}</td>
                      <td className="num">
                        <span className={`font-semibold ${Number(l.stock) <= 0 ? "text-red-600" : l.lowStock ? "text-amber-600" : "text-emerald-700"}`}>
                          {Number(l.stock).toLocaleString()}
                        </span>
                      </td>
                      <td data-testid={`stock-allocated-${id}`} className="num text-gray-500">{Number(l.reserved) ? Number(l.reserved).toLocaleString() : "—"}</td>
                      <td data-testid={`stock-available-${id}`} className={`num font-semibold ${Number(l.available) < 0 ? "text-red-600" : "text-gray-800"}`}>
                        {Number(l.available).toLocaleString()}
                      </td>
                      <td className="num text-gray-600">{Number(l.onOrder) ? Number(l.onOrder).toLocaleString() : "—"}</td>
                      <td className="text-gray-500">{l.nextDelivery ? new Date(l.nextDelivery).toLocaleDateString("en-GB") : Number(l.onOrder) ? "Not set" : "—"}</td>
                    </tr>
                  );
                })}
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
