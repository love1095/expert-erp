"use client";

import { useAuth } from "@/contexts/AuthContext";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getProductsPage, deleteProduct, getCategories, importProductFile, exportProductFile, ProductFileType,
  deleteDeprecatedProducts,
} from "@/lib/api";
import { ExportMenu } from "@/components/FileMenus";
import { useRouter } from "next/navigation";
import Link from "next/link";
import MoreActions from "@/components/MoreActions";
import { TickBox, useSelection } from "@/components/BulkActions";
import { Product, Category, Page, ProductImportResult, StockImportResult, CustomerImportResult, FileImportResult } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useRef, useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import { useConfirm } from "@/components/ConfirmDialog";
import { SkeletonRows } from "@/components/ui/Skeleton";


// Cin7's product files (Products → Export / Import), in Cin7's columns
const EXPORT_FILES: { value: ProductFileType; label: string }[] = [
  { value: "INVENTORY_LIST", label: "Inventory list" },
  { value: "INVENTORY_LIST_NO_DESCRIPTIONS", label: "Inventory list (without descriptions)" },
  { value: "STOCK_ON_HAND", label: "Stock on hand" },
  { value: "UNITS_OF_MEASURE", label: "Units of measure" },
  { value: "ASSEMBLY_BOM", label: "Assembly BOM" },
  { value: "SIMPLE_PRICES", label: "Simple prices" },
  { value: "CUSTOM_PRICES", label: "Custom prices" },
];
const IMPORT_FILES = EXPORT_FILES.filter(f => f.value !== "INVENTORY_LIST_NO_DESCRIPTIONS");

// What each file does when imported
const IMPORT_HELP: Record<ProductFileType, string> = {
  INVENTORY_LIST: "A Cin7 Core inventory list (Products → Export → Inventory list, InventoryList_….csv), or a file exported here. "
    + "Products are matched by SKU (ProductCode): new ones are created, existing ones updated. Categories and suppliers are created "
    + "when missing. Prices of 0 or 9999.99 are treated as not set.",
  INVENTORY_LIST_NO_DESCRIPTIONS: "",
  STOCK_ON_HAND: "Cin7's Stock on hand file (StockOnHand_….csv), or one exported here: each Location is the warehouse of that name, and "
    + "its stock is set to the file's quantities (rows of the same product added up), costed at the file's AverageCost. Warehouses not in "
    + "the file are left alone. You see the changes first and apply them after.",
  UNITS_OF_MEASURE: "Cin7's units of measure file (AdditionalUOM_….csv): for each product in the file, its extra units become the file's "
    + "(a unit already there keeps its barcode and prices). Action \"Delete\" removes a unit.",
  ASSEMBLY_BOM: "Cin7's Assembly BOM file (AssemblyBOM_….csv): each kit in the file gets the file's components. The product must already "
    + "be a kit (made on its page); a kit on open orders is not changed.",
  SIMPLE_PRICES: "Cin7's Simple prices file (SimplePrices_….csv): price tiers 1 to 4 of products that exist (no product is created). "
    + "0 or 9999.99 = no price; tiers 5 to 10 are not used here.",
  CUSTOM_PRICES: "Cin7's Custom prices file (CustomPrices_….csv): each becomes a contract price for that customer from today, with no end.",
};

type AnyImportResult = ProductImportResult | StockImportResult | CustomerImportResult | FileImportResult;

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
  // The file type chosen on the Import menu (null: the import panel is closed)
  const [importType, setImportType] = useState<ProductFileType | null>(null);
  const [deprecateMissing, setDeprecateMissing] = useState(false);
  const [importResult, setImportResult] = useState<AnyImportResult | null>(null);
  const [importError, setImportError] = useState("");
  // Stock on hand: the file previewed, kept to apply it
  const [stockFile, setStockFile] = useState<File | null>(null);
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
    mutationFn: importProductFile,
    onSuccess: (r: AnyImportResult, v) => {
      setImportResult(r); setImportError("");
      setStockFile(v.type === "STOCK_ON_HAND" && !v.apply ? v.file : null);
      refresh();
    },
    onError: (e: any) => { setImportResult(null); setStockFile(null); setImportError(e.response?.data?.error || "Import failed"); },
    onSettled: () => { if (fileInput.current) fileInput.current.value = ""; },
  });

  const openImport = (type: ProductFileType) => {
    setImportType(type); setImportResult(null); setImportError(""); setStockFile(null);
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
          <MoreActions label={importMutation.isPending ? "Importing…" : "Import"}
            actions={IMPORT_FILES.map(f => ({ label: f.label, onClick: () => openImport(f.value) }))} />
          <ExportMenu items={EXPORT_FILES.map(f => ({ label: f.label, file: () => exportProductFile(f.value) }))} onError={setImportError} />
          <Link href="/products/new"
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 transition-colors">
            + Add Product
          </Link>
      </PageHeader>

      {importError && !importType && <p role="alert" data-testid="products-export-error" className="mb-4 text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {importError}</p>}
      {importType && (
        <section data-testid="products-import" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-3">
          <div className="flex items-start justify-between">
            <h2 className="font-semibold text-gray-800">Import: {EXPORT_FILES.find(f => f.value === importType)?.label}</h2>
            <button type="button" onClick={() => setImportType(null)} className="text-xs text-gray-500 hover:underline">Close</button>
          </div>
          <p className="text-xs text-gray-500">{IMPORT_HELP[importType]}</p>
          {importType === "INVENTORY_LIST" && (
            <label className="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" checked={deprecateMissing} onChange={e => setDeprecateMissing(e.target.checked)} />
              Mark active products that are not in the file as Deprecated (use with a full export)
            </label>
          )}
          <div className="flex items-center gap-3">
            <label htmlFor="products-import-file" className="sr-only">CSV file</label>
            <input id="products-import-file" ref={fileInput} type="file" accept=".csv,text/csv" disabled={importMutation.isPending}
              onChange={e => { const f = e.target.files?.[0]; if (f) importMutation.mutate({ file: f, type: importType, deprecateMissing }); }}
              className="text-sm" />
            {importMutation.isPending && <span className="text-sm text-gray-500">Importing… large files take a minute</span>}
          </div>
          {importError && <p role="alert" data-testid="products-import-error" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {importError}</p>}
          {importResult && (
            <div data-testid="products-import-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3">
              <ImportSummary type={importType} r={importResult} />
              {stockFile && (importResult as StockImportResult).changed > 0 && (
                <button type="button" disabled={importMutation.isPending}
                  onClick={async () => {
                    if (await ask({ title: `Change the stock of ${(importResult as StockImportResult).changed} product(s) to the file's quantities?`, action: "Apply" }))
                      importMutation.mutate({ file: stockFile, type: "STOCK_ON_HAND", apply: true });
                  }}
                  className="mt-2 bg-brand-600 text-white px-3 py-1.5 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
                  Apply these changes
                </button>
              )}
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

// One line on what an import did, in the words of its file type
function ImportSummary({ type, r }: { type: ProductFileType; r: AnyImportResult }) {
  if (type === "INVENTORY_LIST") {
    const p = r as ProductImportResult;
    return (
      <p>
        ✓ {p.rows.toLocaleString()} rows: {p.created.toLocaleString()} created,{" "}
        {p.updated.toLocaleString()} updated, {p.unchanged.toLocaleString()} unchanged
        {p.deprecated > 0 && <>, {p.deprecated.toLocaleString()} not in the file marked Deprecated</>}.
        {(p.categoriesCreated > 0 || p.suppliersCreated > 0) && <> New: {p.categoriesCreated} categories, {p.suppliersCreated} suppliers.</>}
      </p>
    );
  }
  if (type === "STOCK_ON_HAND") {
    const s = r as StockImportResult;
    return (
      <p>
        {s.applied ? "✓ Applied" : "Preview, nothing changed yet"}: {s.rows.toLocaleString()} products in {s.warehouseName || "no warehouse"};{" "}
        {s.changed.toLocaleString()} to change, {s.unchanged.toLocaleString()} unchanged
        {s.unknownSkus > 0 && <>, {s.unknownSkus} not products here</>}. Units {s.unitsBefore} → {s.unitsAfter}.
      </p>
    );
  }
  if (type === "CUSTOM_PRICES") {
    const c = r as CustomerImportResult;
    return <p>✓ {c.rows.toLocaleString()} rows: {c.prices.toLocaleString()} contract prices.</p>;
  }
  const f = r as FileImportResult;
  return <p>✓ {f.rows.toLocaleString()} rows: {f.changed.toLocaleString()} products changed, {f.unchanged.toLocaleString()} unchanged.</p>;
}
