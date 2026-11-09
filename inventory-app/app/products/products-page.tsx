"use client";

import { useAuth } from "@/contexts/AuthContext";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getProductsPage, deleteProduct, getCategories, importProducts, exportProducts,
  deleteDeprecatedProducts,
} from "@/lib/api";
import { useRouter } from "next/navigation";
import Link from "next/link";
import MoreActions from "@/components/MoreActions";
import { TickBox, useSelection } from "@/components/BulkActions";
import { Product, Category, Page, ProductImportResult } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useRef, useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import { useConfirm } from "@/components/ConfirmDialog";
import { SkeletonRows } from "@/components/ui/Skeleton";

const BUTTON_SECONDARY = "border border-gray-300 bg-white text-gray-700 px-4 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50";

const STATUS_BADGE: Record<Product["status"], string> = {
  ACTIVE: "bg-emerald-50 text-emerald-700",
  DISCONTINUED: "bg-amber-50 text-amber-800",
  DEPRECATED: "bg-gray-100 text-gray-500",
};

export default function ProductsPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  // Phase I1: cost price only for roles with "Product - Prices: cost price"
  const { can } = useAuth();
  const seeCost = can("PRODUCT_COST");

  const [selectedCategoryId, setSelectedCategoryId] = useState<number | undefined>(undefined);
  // Like Cin7: active products by default
  const [status, setStatus] = useState("ACTIVE");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [showImport, setShowImport] = useState(false);
  const [deprecateMissing, setDeprecateMissing] = useState(false);
  const [importResult, setImportResult] = useState<ProductImportResult | null>(null);
  const [importError, setImportError] = useState("");
  const [exporting, setExporting] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const { data: categories = [] } = useQuery<Category[]>({
    queryKey: ["categories"],
    queryFn: getCategories,
  });

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Product>>({
    queryKey: ["products-page", selectedCategoryId, status, q, page, size],
    queryFn: () => getProductsPage({
      categoryId: selectedCategoryId, status: status === "ALL" ? undefined : status, q: q || undefined, page, size,
    }),
  });

  const products = data?.content ?? [];
  const selection = useSelection(products);
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["products-page"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["categories"] });
  };

  // What a More action did, shown above the list
  const [actionResult, setActionResult] = useState<{ ok: boolean; lines: string[] } | null>(null);
  const router = useRouter();

  // Delete the ticked products one by one; a product with history is refused and reported
  const deleteTicked = async (rows: Product[]) => {
    const lines: string[] = [];
    let done = 0;
    for (const p of rows) {
      try { await deleteProduct(p.id); done++; }
      catch (e: any) { lines.push(`${p.sku}: ${e.response?.data?.error || "not deleted"}`); }
    }
    setActionResult({ ok: lines.length === 0, lines: [`${done} deleted${lines.length ? `, ${lines.length} not deleted` : ""}`, ...lines] });
    selection.clear();
    refresh();
  };
  const deleteDeprecated = useMutation({
    mutationFn: deleteDeprecatedProducts,
    onSuccess: (r: { deleted: number; kept: number }) => {
      setActionResult({ ok: true, lines: [`${r.deleted} deprecated product${r.deleted !== 1 ? "s" : ""} deleted`
        + (r.kept > 0 ? `; ${r.kept} kept because they have stock movements or orders` : "")] });
      refresh();
    },
    onError: (e: any) => setActionResult({ ok: false, lines: [e.response?.data?.error || "Could not delete deprecated products"] }),
  });

  const importMutation = useMutation({
    mutationFn: importProducts,
    onSuccess: (r: ProductImportResult) => { setImportResult(r); setImportError(""); refresh(); },
    onError: (e: any) => { setImportResult(null); setImportError(e.response?.data?.error || "Import failed"); },
    onSettled: () => { if (fileInput.current) fileInput.current.value = ""; },
  });

  const handleExport = async () => {
    setExporting(true);
    try {
      const blob = await exportProducts({ status: status === "ALL" ? undefined : status });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `products_${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setExporting(false);
    }
  };

  return (
    <div>
      <PageHeader title="Products" count={data?.totalElements} noun="products">
          {/* Cin7: More actions acts on the ticked products, plus Delete deprecated products */}
          <MoreActions actions={[
            { label: "Edit", onClick: () => router.push(`/products/${selection.selected[0].id}?edit=1`),
              disabledReason: selection.selected.length === 1 ? undefined : "Tick one product" },
            { label: `Delete${selection.selected.length ? ` (${selection.selected.length})` : ""}`, danger: true,
              disabledReason: selection.selected.length ? undefined : "Tick the products to delete",
              onClick: async () => { if (await ask({ title: `Delete ${selection.selected.length} product(s)? Products with stock movements or orders are kept.`, action: "Delete", danger: true })) deleteTicked(selection.selected); } },
            { label: "Delete deprecated products", danger: true, divider: true,
              onClick: async () => { if (await ask({ title: "Delete every deprecated product that has no stock movements or orders?", action: "Delete", danger: true })) deleteDeprecated.mutate(); } },
          ]} />
          <button onClick={() => { setShowImport(!showImport); setImportError(""); }} className={BUTTON_SECONDARY}>
            Import
          </button>
          <button onClick={handleExport} disabled={exporting} className={BUTTON_SECONDARY}>
            {exporting ? "Exporting..." : "Export"}
          </button>
          <Link href="/products/new"
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 transition-colors">
            + Add Product
          </Link>
      </PageHeader>

      {showImport && (
        <section data-testid="products-import" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-3">
          <h2 className="font-semibold text-gray-800">Import products</h2>
          <p className="text-xs text-gray-500">
            A Cin7 Core inventory list (Inventory → Export → <b>InventoryList_….csv</b>), or a file exported here.
            Products are matched by SKU (ProductCode): new ones are created, existing ones updated. Categories and suppliers
            are created when missing. Prices of 0 or 9999.99 are treated as not set.
          </p>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={deprecateMissing} onChange={e => setDeprecateMissing(e.target.checked)} />
            Mark active products that are not in the file as Deprecated (use with a full export)
          </label>
          <div className="flex items-center gap-3">
            <label htmlFor="products-import-file" className="sr-only">CSV file</label>
            <input id="products-import-file" ref={fileInput} type="file" accept=".csv,text/csv" disabled={importMutation.isPending}
              onChange={e => { const f = e.target.files?.[0]; if (f) importMutation.mutate({ file: f, deprecateMissing }); }}
              className="text-sm" />
            {importMutation.isPending && <span className="text-sm text-gray-500">Importing… large files take a minute</span>}
          </div>
          {importError && <p role="alert" data-testid="products-import-error" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {importError}</p>}
          {importResult && (
            <div data-testid="products-import-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3">
              <p>
                ✓ {importResult.rows.toLocaleString()} rows: {importResult.created.toLocaleString()} created,{" "}
                {importResult.updated.toLocaleString()} updated, {importResult.unchanged.toLocaleString()} unchanged
                {importResult.deprecated > 0 && <>, {importResult.deprecated.toLocaleString()} not in the file marked Deprecated</>}.
                {(importResult.categoriesCreated > 0 || importResult.suppliersCreated > 0) && (
                  <> New: {importResult.categoriesCreated} categories, {importResult.suppliersCreated} suppliers.</>
                )}
              </p>
              {importResult.warnings.length > 0 && (
                <ul className="mt-2 text-xs text-amber-800 list-disc ml-5 space-y-0.5 max-h-40 overflow-y-auto">
                  {importResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              )}
            </div>
          )}
        </section>
      )}

      <ListToolbar
        search={q} onSearch={v => { setQ(v); setPage(0); }}
        searchLabel="Search products" placeholder="SKU or name"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
        <label htmlFor="products-filter-status" className="sr-only">Status</label>
        <select id="products-filter-status" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}
          className={FILTER_SELECT}>
          <option value="ACTIVE">Active</option>
          <option value="DISCONTINUED">Discontinued</option>
          <option value="DEPRECATED">Archived</option>
          <option value="ALL">All statuses</option>
        </select>
        <label htmlFor="products-filter-category" className="sr-only">Category</label>
        <select id="products-filter-category"
          value={selectedCategoryId ?? ""}
          onChange={(e) => {
            setSelectedCategoryId(e.target.value ? Number(e.target.value) : undefined);
            setPage(0);
          }}
          className={`${FILTER_SELECT} w-48`}
        >
          <option value="">All categories</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        </>} />

      {actionResult && (
        <div role="status" data-testid="products-action-result"
          className={`mb-3 flex items-start justify-between gap-4 text-sm px-3 py-2 rounded-lg ${actionResult.ok ? "bg-emerald-50 text-emerald-800" : "bg-amber-50 text-amber-900"}`}>
          <ul>{actionResult.lines.map(l => <li key={l}>{l}</li>)}</ul>
          <button type="button" onClick={() => setActionResult(null)} aria-label="Close" className="opacity-60 hover:opacity-100">×</button>
        </div>
      )}
      {selection.selected.length > 0 && (
        <p className="mb-2 text-xs text-gray-600">
          {selection.selected.length} ticked · use <b>More actions</b> ·{" "}
          <button type="button" onClick={selection.clear} className="underline">clear</button>
        </p>
      )}

      {isLoading ? (
        <SkeletonRows />
      ) : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <ListHead cols={["SKU", "Name", "Brand", "Category", "Unit", "Status", ...(seeCost ? ["Cost price"] : []), "Selling price", "Stock"]}
                right={["Cost price", "Selling price", "Stock"]}
                lead={<th className="w-8"><TickBox checked={selection.allTicked} onChange={selection.toggleAll} label="Select all products on this page" /></th>} />
              <tbody className="divide-y divide-gray-100">
                {products.length === 0 && (
                  <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">No products{q ? ` matching "${q}"` : ""}</td></tr>
                )}
                {products.map((p) => (
                  <tr key={p.id} data-testid={`product-row-${p.id}`} onClick={() => router.push(`/products/${p.id}`)} className="hover:bg-gray-50 cursor-pointer">
                    <td className="w-8"><TickBox checked={selection.isTicked(p.id)} onChange={() => selection.toggle(p.id)} label={`Select ${p.sku}`} /></td>
                    <td className="font-mono text-xs">
                      <Link href={`/products/${p.id}`} onClick={e => e.stopPropagation()} className="text-gray-700 hover:underline">{p.sku}</Link>
                    </td>
                    <td className="font-medium">
                      <Clip text={p.variantOptions?.length ? `${p.name} (${p.variantOptions.map(o => o.value).join(" · ")})` : p.name} width="16rem" />
                    </td>
                    <td className="text-gray-600"><Clip text={p.brand} width="7rem" /></td>
                    <td>
                      {p.categoryName ? (
                        <span title={p.categoryName} className="inline-block max-w-32 truncate align-middle bg-brand-50 text-brand-700 text-xs px-2 py-0.5 rounded-full font-medium">
                          {p.categoryName}
                        </span>
                      ) : (
                        <span className="text-gray-300 text-xs">—</span>
                      )}
                    </td>
                    <td className="text-gray-500"><Clip text={p.unit} width="5.5rem" /></td>
                    <td>
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_BADGE[p.status]}`}>
                        {p.status === "ACTIVE" ? "Active" : p.status === "DISCONTINUED" ? "Discontinued" : "Archived"}
                      </span>
                    </td>
                    {seeCost && <td className="num text-gray-600">{formatGBP(p.costPrice)}</td>}
                    <td className="num text-gray-900">{formatGBP(p.sellPrice)}</td>
                    <td className="num">
                      <span className={`font-semibold ${Number(p.totalStock) <= 0 ? "text-red-600" : "text-emerald-700"}`}>
                        {Number(p.totalStock).toLocaleString()}
                      </span>
                      {Number(p.reservedStock) > 0 && (
                        <span className={`block text-[11px] ${Number(p.availableStock) < 0 ? "text-red-600" : "text-gray-400"}`}>
                          {Number(p.availableStock).toLocaleString()} available
                        </span>
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
