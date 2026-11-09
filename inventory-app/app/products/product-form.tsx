"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/contexts/AuthContext";
import { createProduct, getCategories, updateProduct } from "@/lib/api";
import { Category, Product } from "@/types";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";

const EMPTY_FORM = {
  sku: "", name: "", unit: "", categoryId: "", status: "ACTIVE", type: "STOCK", brand: "", barcode: "",
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

function toForm(p?: Product): Form {
  if (!p) return EMPTY_FORM;
  const str = (v: unknown) => (v == null ? "" : String(v));
  return {
    sku: p.sku, name: p.name, unit: p.unit, categoryId: str(p.categoryId), status: p.status, type: p.type,
    brand: str(p.brand), barcode: str(p.barcode),
    costPrice: str(p.costPrice), sellPrice: str(p.sellPrice), tradePrice: str(p.tradePrice),
    minStock: str(p.minStock), reorderQuantity: str(p.reorderQuantity),
    supplierProductCode: str(p.supplierProductCode),
    shortDescription: str(p.shortDescription), description: str(p.description),
  };
}

/**
 * The product's main fields, for a new product (/products/new) and for Edit on the product's page (review R2, rule D1).
 * `editing` must be the whole product (from GET /products/{id}): fields the form does not show are sent back unchanged.
 */
export default function ProductForm({ editing, onSaved, onCancel }: {
  editing?: Product;
  onSaved: (p: Product) => void;
  onCancel: () => void;
}) {
  const qc = useQueryClient();
  // Phase I1: cost price only for roles with "Product - Prices: cost price"
  const { can } = useAuth();
  const seeCost = can("PRODUCT_COST");
  const [form, setForm] = useState<Form>(() => toForm(editing));
  const [error, setError] = useState("");
  const { data: categories = [] } = useQuery<Category[]>({ queryKey: ["categories"], queryFn: getCategories });

  const save = useMutation({
    mutationFn: (data: unknown) => (editing == null ? createProduct(data) : updateProduct(editing.id, data)),
    onSuccess: (saved: Product) => {
      qc.invalidateQueries({ queryKey: ["products-page"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      qc.invalidateQueries({ queryKey: ["categories"] });
      qc.invalidateQueries({ queryKey: ["product", saved?.id] });
      onSaved(saved);
    },
    onError: (e: any) => {
      const fields = e.response?.data?.fields;
      setError(fields ? Object.values(fields).join(", ") : e.response?.data?.error || "Failed to save product");
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const num = (v: string) => (v.trim() === "" ? null : Number(v));
    const text = (v: string) => (v.trim() === "" ? null : v);
    save.mutate({
      // Fields the form does not show (family, options, Cin7 details...) are sent back unchanged
      ...(editing ?? {}),
      sku: form.sku, name: form.name, unit: form.unit, status: form.status, type: form.type,
      categoryId: form.categoryId ? Number(form.categoryId) : null,
      brand: text(form.brand), barcode: text(form.barcode), supplierProductCode: text(form.supplierProductCode),
      costPrice: num(form.costPrice), sellPrice: num(form.sellPrice), tradePrice: num(form.tradePrice),
      minStock: num(form.minStock), reorderQuantity: num(form.reorderQuantity),
      shortDescription: text(form.shortDescription), description: text(form.description),
    });
  };

  return (
    <form onSubmit={handleSubmit} data-testid="product-form" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 lg:grid-cols-3 gap-4">
      {error && <p role="alert" className="col-span-2 lg:col-span-3 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}
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
          <option value="DISCONTINUED">Discontinued (sold while stock lasts, not reordered)</option>
          <option value="DEPRECATED">Archived (hidden, not sent to Shopify; Cin7: Deprecated)</option>
        </select>
      </div>
      <div>
        <label htmlFor="products-type" className="block text-xs font-medium text-gray-600 mb-1">Type</label>
        <select id="products-type" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className={INPUT}>
          <option value="STOCK">Stock</option>
          <option value="SERVICE">Service</option>
          <option value="KIT">Kit (made of other products)</option>
        </select>
      </div>
      {NUMBER_FIELDS.filter(f => seeCost || f.key !== "costPrice").map(({ label, key, placeholder }) => (
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
      <div className="col-span-2 lg:col-span-3 flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>
        <button type="submit" disabled={save.isPending}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving..." : "Save Product"}
        </button>
      </div>
    </form>
  );
}
