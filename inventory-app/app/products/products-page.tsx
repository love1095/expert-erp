"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getProductsPage, getProduct, createProduct, updateProduct, deleteProduct, getCategories, importProducts, exportProducts,
} from "@/lib/api";
import { Product, Category, Page, ProductImportResult } from "@/types";
import { formatGBP } from "@/lib/currency";
import { useEffect, useRef, useState } from "react";
import Pagination from "@/components/Pagination";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const BUTTON_SECONDARY = "border border-gray-300 text-gray-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-50 disabled:opacity-50";

const EMPTY_FORM = {
  sku: "", name: "", unit: "", categoryId: "", status: "ACTIVE", brand: "", barcode: "",
  costPrice: "", sellPrice: "", tradePrice: "", minStock: "", reorderQuantity: "", supplierProductCode: "",
  shortDescription: "", description: "",
};
type Form = typeof EMPTY_FORM;

const TEXT_FIELDS: { label: string; key: keyof Form; placeholder?: string }[] = [
  { label: "SKU *", key: "sku", placeholder: "e.g. PROD-001" },
  { label: "Name *", key: "name", placeholder: "Product name" },
  { label: "Unit *", key: "unit", placeholder: "e.g. Each, Pair, Box of 10" },
  { label: "Brand", key: "brand" },
  { label: "Barcode", key: "barcode" },
  { label: "Supplier product code", key: "supplierProductCode" },
];
const NUMBER_FIELDS: { label: string; key: keyof Form; placeholder?: string }[] = [
  { label: "Cost price (£)", key: "costPrice", placeholder: "What you pay" },
  { label: "Selling price (£)", key: "sellPrice", placeholder: "Price on Shopify" },
  { label: "Trade price (£)", key: "tradePrice", placeholder: "Cin7 price tier 2" },
  { label: "Min stock", key: "minStock", placeholder: "Reorder at this level" },
  { label: "Reorder quantity", key: "reorderQuantity", placeholder: "How many to order" },
];

const STATUS_BADGE: Record<Product["status"], string> = {
  ACTIVE: "bg-emerald-50 text-emerald-700",
  DEPRECATED: "bg-gray-100 text-gray-500",
};

export default function ProductsPage() {
  const qc = useQueryClient();

  const [selectedCategoryId, setSelectedCategoryId] = useState<number | undefined>(undefined);
  // Like Cin7: active products by default
  const [status, setStatus] = useState("ACTIVE");
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<Form>(EMPTY_FORM);
  // The full product being edited (keeps fields the form does not show); null = adding a new product
  const [editing, setEditing] = useState<Product | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState("");
  const [showImport, setShowImport] = useState(false);
  const [deprecateMissing, setDeprecateMissing] = useState(false);
  const [importResult, setImportResult] = useState<ProductImportResult | null>(null);
  const [importError, setImportError] = useState("");
  const [exporting, setExporting] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  // Search as you type, once typing pauses
  useEffect(() => {
    const t = setTimeout(() => { setQ(search.trim()); setPage(0); }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const { data: categories = [] } = useQuery<Category[]>({
    queryKey: ["categories"],
    queryFn: getCategories,
  });

  const { data, isLoading } = useQuery<Page<Product>>({
    queryKey: ["products-page", selectedCategoryId, status, q, page],
    queryFn: () => getProductsPage({
      categoryId: selectedCategoryId, status: status === "ALL" ? undefined : status, q: q || undefined, page, size: 20,
    }),
  });

  const products = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["products-page"] });
    qc.invalidateQueries({ queryKey: ["products"] });
    qc.invalidateQueries({ queryKey: ["categories"] });
  };

  const closeForm = () => {
    setShowForm(false);
    setEditing(null);
    setForm(EMPTY_FORM);
    setError("");
  };

  const saveMutation = useMutation({
    mutationFn: (data: unknown) => (editing == null ? createProduct(data) : updateProduct(editing.id, data)),
    onSuccess: () => { refresh(); closeForm(); },
    onError: (e: any) => {
      const fields = e.response?.data?.fields;
      setError(fields ? Object.values(fields).join(", ") : e.response?.data?.error || "Failed to save product");
    },
  });

  const startEdit = async (p: Product) => {
    // The list leaves out long texts; load the whole product
    const full: Product = await getProduct(p.id);
    const str = (v: unknown) => (v == null ? "" : String(v));
    setEditing(full);
    setForm({
      sku: full.sku, name: full.name, unit: full.unit, categoryId: str(full.categoryId), status: full.status,
      brand: str(full.brand), barcode: str(full.barcode),
      costPrice: str(full.costPrice), sellPrice: str(full.sellPrice), tradePrice: str(full.tradePrice),
      minStock: str(full.minStock), reorderQuantity: str(full.reorderQuantity),
      supplierProductCode: str(full.supplierProductCode),
      shortDescription: str(full.shortDescription), description: str(full.description),
    });
    setError("");
    setShowForm(true);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const deleteMutation = useMutation({
    mutationFn: deleteProduct,
    onSuccess: refresh,
    onError: (e: any) => alert(e.response?.data?.error || "Failed to delete product"),
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

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const num = (v: string) => (v.trim() === "" ? null : Number(v));
    const text = (v: string) => (v.trim() === "" ? null : v);
    saveMutation.mutate({
      // Fields the form does not show (family, options, Cin7 details, type...) are sent back unchanged
      ...(editing ?? {}),
      sku: form.sku, name: form.name, unit: form.unit, status: form.status,
      categoryId: form.categoryId ? Number(form.categoryId) : null,
      brand: text(form.brand), barcode: text(form.barcode), supplierProductCode: text(form.supplierProductCode),
      costPrice: num(form.costPrice), sellPrice: num(form.sellPrice), tradePrice: num(form.tradePrice),
      minStock: num(form.minStock), reorderQuantity: num(form.reorderQuantity),
      shortDescription: text(form.shortDescription), description: text(form.description),
    });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6 gap-3 flex-wrap">
        <h1 className="text-2xl font-bold">Products</h1>
        <div className="flex gap-2">
          <button onClick={() => { setShowImport(!showImport); setImportError(""); }} className={BUTTON_SECONDARY}>
            Import
          </button>
          <button onClick={handleExport} disabled={exporting} className={BUTTON_SECONDARY}>
            {exporting ? "Exporting..." : "Export"}
          </button>
          <button
            onClick={() => (showForm ? closeForm() : setShowForm(true))}
            className="bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 transition-colors"
          >
            {showForm ? "Cancel" : "+ Add Product"}
          </button>
        </div>
      </div>

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

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 lg:grid-cols-3 gap-4">
          <h2 className="col-span-2 lg:col-span-3 font-semibold text-gray-800">{editing == null ? "New Product" : `Edit ${form.sku}`}</h2>
          {error && <p className="col-span-2 lg:col-span-3 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}
          {editing?.familySku && (
            <p className="col-span-2 lg:col-span-3 text-xs text-gray-500">
              Part of family <b>{editing.familyName ?? editing.familySku}</b>
              {editing.variantOptions?.length ? <> — {editing.variantOptions.map(o => `${o.name}: ${o.value}`).join(", ")}</> : null}.
              On Shopify the family is one product with a variant per SKU.
            </p>
          )}
          {TEXT_FIELDS.map(({ label, key, placeholder }) => (
            <div key={key}>
              <label htmlFor={`products-${key}`} className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
              <input id={`products-${key}`} value={form[key]} placeholder={placeholder}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })} className={INPUT} />
            </div>
          ))}
          <div>
            <label htmlFor="products-category" className="block text-xs font-medium text-gray-600 mb-1">Category</label>
            <select id="products-category" value={form.categoryId}
              onChange={(e) => setForm({ ...form, categoryId: e.target.value })} className={INPUT}>
              <option value="">— None —</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="products-status" className="block text-xs font-medium text-gray-600 mb-1">Status</label>
            <select id="products-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })} className={INPUT}>
              <option value="ACTIVE">Active</option>
              <option value="DEPRECATED">Deprecated (hidden, not sent to Shopify)</option>
            </select>
          </div>
          {NUMBER_FIELDS.map(({ label, key, placeholder }) => (
            <div key={key}>
              <label htmlFor={`products-${key}`} className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
              <input id={`products-${key}`} type="number" step="any" min={0} value={form[key]} placeholder={placeholder}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })} className={INPUT} />
            </div>
          ))}
          <div className="col-span-2 lg:col-span-3">
            <label htmlFor="products-shortDescription" className="block text-xs font-medium text-gray-600 mb-1">Short description</label>
            <input id="products-shortDescription" value={form.shortDescription}
              onChange={(e) => setForm({ ...form, shortDescription: e.target.value })} className={INPUT} />
          </div>
          <div className="col-span-2 lg:col-span-3">
            <label htmlFor="products-description" className="block text-xs font-medium text-gray-600 mb-1">Description (text or HTML)</label>
            <textarea id="products-description" rows={4} value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })} className={`${INPUT} font-mono text-xs`} />
          </div>
          <div className="col-span-2 lg:col-span-3 flex justify-end">
            <button type="submit" disabled={saveMutation.isPending}
              className="bg-brand-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              {saveMutation.isPending ? "Saving..." : "Save Product"}
            </button>
          </div>
        </form>
      )}

      <div className="flex items-center gap-3 mb-4 flex-wrap">
        <label htmlFor="products-search" className="sr-only">Search products</label>
        <input id="products-search" type="search" value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search SKU or name" className="border border-gray-300 rounded-md px-3 py-2 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-brand-400" />
        <label htmlFor="products-filter-status" className="sr-only">Status</label>
        <select id="products-filter-status" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
          <option value="ACTIVE">Active</option>
          <option value="DEPRECATED">Deprecated</option>
          <option value="ALL">All statuses</option>
        </select>
        <label htmlFor="products-filter-category" className="sr-only">Category</label>
        <select id="products-filter-category"
          value={selectedCategoryId ?? ""}
          onChange={(e) => {
            setSelectedCategoryId(e.target.value ? Number(e.target.value) : undefined);
            setPage(0);
          }}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 w-48"
        >
          <option value="">All categories</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </div>

      {isLoading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["SKU", "Name", "Brand", "Category", "Unit", "Cost Price", "Selling Price", "Total Stock", "Status", ""].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {products.length === 0 && (
                  <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">No products{q ? ` matching "${q}"` : ""}</td></tr>
                )}
                {products.map((p) => (
                  <tr key={p.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs text-gray-500 whitespace-nowrap">{p.sku}</td>
                    <td className="px-4 py-3">
                      <div className="font-medium">{p.name}</div>
                      {p.variantOptions?.length ? (
                        <div className="text-xs text-gray-400">{p.variantOptions.map(o => o.value).join(" · ")}</div>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{p.brand ?? <span className="text-gray-300">—</span>}</td>
                    <td className="px-4 py-3">
                      {p.categoryName ? (
                        <span className="inline-block bg-brand-50 text-brand-700 text-xs px-2 py-0.5 rounded-full font-medium whitespace-nowrap">
                          {p.categoryName}
                        </span>
                      ) : (
                        <span className="text-gray-300 text-xs">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-500 whitespace-nowrap">{p.unit}</td>
                    <td className="px-4 py-3 text-gray-600 tabular-nums">{formatGBP(p.costPrice)}</td>
                    <td className="px-4 py-3 text-gray-900 tabular-nums">{formatGBP(p.sellPrice)}</td>
                    <td className="px-4 py-3">
                      <span className={`font-semibold ${Number(p.totalStock) <= 0 ? "text-red-600" : "text-emerald-700"}`}>
                        {Number(p.totalStock).toLocaleString()}
                      </span>
                      {Number(p.reservedStock) > 0 && (
                        <span className={`block text-[11px] ${Number(p.availableStock) < 0 ? "text-red-600" : "text-gray-400"}`}>
                          {Number(p.availableStock).toLocaleString()} available
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_BADGE[p.status]}`}>
                        {p.status === "ACTIVE" ? "Active" : "Deprecated"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right whitespace-nowrap space-x-3">
                      <button onClick={() => startEdit(p)} className="text-xs text-brand-600 hover:text-brand-800">
                        Edit
                      </button>
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
