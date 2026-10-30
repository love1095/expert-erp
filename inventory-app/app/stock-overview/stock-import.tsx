"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { getWarehouses, importStock } from "@/lib/api";
import { StockImportResult, Warehouse } from "@/types";
import { formatGBP } from "@/lib/currency";

const qty = (n: number) => n.toLocaleString();

// Cin7 stock file → stock in one warehouse set to the file's OnHand. Preview first, then import.
export default function StockImport({ onImported }: { onImported: () => void }) {
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses-all"], queryFn: getWarehouses });
  const [warehouseId, setWarehouseId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<StockImportResult | null>(null);
  const [error, setError] = useState("");

  const run = useMutation({
    mutationFn: (apply: boolean) => importStock({ file: file!, warehouseId: Number(warehouseId), apply }),
    onSuccess: (r: StockImportResult) => { setResult(r); setError(""); if (r.applied) onImported(); },
    onError: (e: any) => { setResult(null); setError(e.response?.data?.error || "Import failed"); },
  });
  const reset = () => { setResult(null); setError(""); };

  return (
    <section data-testid="stock-import" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-3">
      <h2 className="font-semibold text-gray-800">Import stock on hand</h2>
      <p className="text-xs text-gray-500">
        A Cin7 Core stock file (Inventory → Availability → Export → <b>AvailabilityReport_….csv</b>), one location at a time.
        Each product in the file gets the file&apos;s <b>OnHand</b> in the chosen warehouse: the difference is recorded as a stock
        adjustment, costed from the file&apos;s stock value (as Cin7 values it, £0 included; the product&apos;s cost price when a row has none). Products not in the file are left alone; importing the same file again changes nothing.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <label htmlFor="stock-import-warehouse" className="text-sm text-gray-700">Warehouse</label>
        <select id="stock-import-warehouse" value={warehouseId} onChange={e => { setWarehouseId(e.target.value); reset(); }}
          className="border border-gray-300 rounded-md px-3 py-1.5 text-sm">
          <option value="">Choose…</option>
          {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
        <label htmlFor="stock-import-file" className="sr-only">CSV file</label>
        <input id="stock-import-file" type="file" accept=".csv,text/csv"
          onChange={e => { setFile(e.target.files?.[0] ?? null); reset(); }} className="text-sm" />
        <button onClick={() => run.mutate(false)} disabled={!file || !warehouseId || run.isPending}
          className="border border-gray-300 text-gray-700 px-4 py-1.5 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50">
          Preview
        </button>
        {run.isPending && <span className="text-sm text-gray-500">Working…</span>}
      </div>

      {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {error}</p>}

      {result && (
        <div data-testid="stock-import-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3 space-y-2">
          <p>
            {result.applied ? "✓ Imported" : "Preview"} into <b>{result.warehouseName}</b>
            {result.fileLocation && <> from Cin7 location <b>{result.fileLocation}</b></>}:{" "}
            {qty(result.rows)} rows, <b>{qty(result.changed)} products change</b>, {qty(result.unchanged)} already right
            {result.unknownSkus > 0 && <>, {qty(result.unknownSkus)} SKUs with stock not found</>}.
          </p>
          <p className="text-gray-600">
            Units for these products: {qty(result.unitsBefore)} → <b>{qty(result.unitsAfter)}</b> · stock value after: <b>{formatGBP(result.valueAfter)}</b>
          </p>
          {result.changes.length > 0 && (
            <div className="max-h-60 overflow-y-auto">
              <table className="w-full text-xs" data-testid="stock-import-changes">
                <thead className="text-gray-500 text-left sticky top-0 bg-gray-50">
                  <tr><th className="py-1">SKU</th><th>Product</th><th className="text-right">Now</th><th className="text-right">After</th><th className="text-right">Unit cost</th></tr>
                </thead>
                <tbody className="divide-y divide-gray-200 tabular-nums">
                  {result.changes.map(c => (
                    <tr key={c.sku}>
                      <td className="py-1 font-mono">{c.sku}</td><td className="truncate max-w-[16rem]">{c.name}</td>
                      <td className="text-right">{qty(c.before)}</td><td className="text-right font-semibold">{qty(c.after)}</td>
                      <td className="text-right">{c.unitCost != null ? formatGBP(c.unitCost) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {result.changed > result.changes.length && <p className="text-xs text-gray-400 mt-1">Largest {result.changes.length} changes shown</p>}
            </div>
          )}
          {result.warnings.length > 0 && (
            <ul className="text-xs text-amber-800 list-disc ml-5 space-y-0.5 max-h-40 overflow-y-auto">
              {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          )}
          {!result.applied && result.changed > 0 && (
            <button onClick={() => run.mutate(true)} disabled={run.isPending}
              className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              Import {qty(result.changed)} changes
            </button>
          )}
        </div>
      )}
    </section>
  );
}
