"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getCategories, getInventoryMovement, getInventoryMovementCsv, getWarehouses } from "@/lib/api";
import { InventoryMovementReport } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";
import { PeriodPicker, download, errorText, qty, usePeriod } from "../report-parts";

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white";

/**
 * Phase H4: Cin7's Inventory Movement Summary. Per location and product: quantity and FIFO value at the start, in, out,
 * quantity on hand, unit cost and stock on hand value at the end of the period.
 */
export default function InventoryMovementPage() {
  const [period, setPeriod] = usePeriod();
  const [warehouseId, setWarehouseId] = useState<number | undefined>();
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [search, setSearch] = useState("");
  const [csvError, setCsvError] = useState("");
  const params = { ...period.period, warehouseId, categoryId };
  const { data, isLoading, error } = useQuery<InventoryMovementReport>({ queryKey: ["inventory-movement", params], queryFn: () => getInventoryMovement(params) });
  const { data: warehouses = [] } = useQuery<{ id: number; name: string }[]>({ queryKey: ["warehouses-all"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<{ id: number; name: string }[]>({ queryKey: ["categories"], queryFn: getCategories });

  const q = search.trim().toLowerCase();
  const rows = (data?.rows ?? []).filter(r => !q || [r.sku, r.product, r.warehouse, r.category].some(v => v?.toLowerCase().includes(q)));
  const total = (k: "openingValue" | "inValue" | "outValue" | "closingValue") =>
    q ? rows.reduce((s, r) => s + Number(r[k]), 0) : Number(data?.totals[k] ?? 0);

  return (
    <div data-testid="inventory-movement">
      <PageHeader title="Inventory Movement Summary" subtitle="Stock on hand and its FIFO value: at the start, in, out, and at the end of the period">
        <button type="button" className={BTN_SECONDARY}
          onClick={() => download(getInventoryMovementCsv(params), `InventoryMovement_${params.from}_${params.to}.csv`).catch(() => setCsvError("Could not export"))}>
          Export CSV
        </button>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-2 mb-3">
        <PeriodPicker value={period} onChange={setPeriod} />
        <select aria-label="Location" className={INPUT} value={warehouseId ?? ""} onChange={e => setWarehouseId(e.target.value ? Number(e.target.value) : undefined)}>
          <option value="">All locations</option>
          {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
        <select aria-label="Category" className={INPUT} value={categoryId ?? ""} onChange={e => setCategoryId(e.target.value ? Number(e.target.value) : undefined)}>
          <option value="">All categories</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <input type="search" aria-label="Search" placeholder="Type text to search" className={`${INPUT} ml-auto w-56`}
          value={search} onChange={e => setSearch(e.target.value)} />
      </div>
      {(csvError || error) && <p role="alert" className="mb-3 text-sm text-red-600">{csvError || errorText(error, "stock movement")}</p>}
      {isLoading && <p className="text-sm text-gray-400">Working it out…</p>}
      {data && (
        <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
          <table className="w-full text-sm" data-testid="movement-table">
            <thead>
              <tr className="text-left text-xs text-gray-500 border-b border-gray-200">
                <th className="px-3 py-2 font-medium">Location</th><th className="px-3 font-medium">SKU</th><th className="px-3 font-medium">Product</th>
                <th className="px-3 font-medium">Unit</th>
                <th className="px-3 font-medium text-right">Opening</th><th className="px-3 font-medium text-right">Opening value</th>
                <th className="px-3 font-medium text-right">In</th><th className="px-3 font-medium text-right">In value</th>
                <th className="px-3 font-medium text-right">Out</th><th className="px-3 font-medium text-right">Out value</th>
                <th className="px-3 font-medium text-right">Quantity on hand</th><th className="px-3 font-medium text-right">Unit cost</th>
                <th className="px-3 font-medium text-right">Stock on hand</th>
              </tr>
            </thead>
            <tbody className="tabular-nums">
              {rows.map(r => (
                <tr key={`${r.warehouseId}-${r.productId}`} className="border-b border-gray-100">
                  <td className="px-3 py-1.5">{r.warehouse}</td>
                  <td className="px-3"><Link href={`/products/${r.productId}`} className="text-brand-700 hover:underline">{r.sku}</Link></td>
                  <td className="px-3">{r.product}</td>
                  <td className="px-3 text-gray-600">{r.unit}</td>
                  <td className="px-3 text-right">{qty(r.openingQuantity)}</td>
                  <td className="px-3 text-right">{formatGBP(r.openingValue)}</td>
                  <td className="px-3 text-right">{qty(r.inQuantity)}</td>
                  <td className="px-3 text-right">{formatGBP(r.inValue)}</td>
                  <td className="px-3 text-right">{qty(r.outQuantity)}</td>
                  <td className="px-3 text-right">{formatGBP(r.outValue)}</td>
                  <td className={`px-3 text-right ${Number(r.closingQuantity) < 0 ? "text-red-600" : ""}`}>{qty(r.closingQuantity)}</td>
                  <td className="px-3 text-right">{r.unitCost == null ? "" : formatGBP(r.unitCost)}</td>
                  <td className="px-3 text-right">{formatGBP(r.closingValue)}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={13} className="px-3 py-6 text-center text-gray-500">No stock held or moved in this period</td></tr>}
            </tbody>
            <tfoot>
              <tr className="bg-gray-50 font-semibold border-t border-gray-200" data-testid="movement-total">
                <td className="px-3 py-2" colSpan={4}>Grand Total ({rows.length})</td>
                <td /><td className="px-3 text-right">{formatGBP(total("openingValue"))}</td>
                <td /><td className="px-3 text-right">{formatGBP(total("inValue"))}</td>
                <td /><td className="px-3 text-right">{formatGBP(total("outValue"))}</td>
                <td /><td /><td className="px-3 text-right">{formatGBP(total("closingValue"))}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      <p className="mt-2 text-xs text-gray-500">
        FIFO: stock in at the cost it came in at, out at the cost of the oldest layers; supplier bills at another price correct the
        stock still held on the day the bill was entered.
      </p>
    </div>
  );
}
