"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getProductUnits, saveProductUnits } from "@/lib/api";
import { Product, ProductUnit } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, Card, TD, TH } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const INPUT = "border border-gray-300 rounded px-2 py-1 text-sm w-full";

type Row = { name: string; factor: string; barcode: string; sellPrice: string; buyPrice: string };

/**
 * Phase P3: the product's extra units of measure (Pair, Box of 100, Carton). Stock and cost stay in its own unit; an
 * order line in another unit moves quantity × "holds". Empty prices: the product's price × "holds".
 */
export function UnitsSection({ p }: { p: Product }) {
  const qc = useQueryClient();
  const { can } = useAuth();
  const edit = can("PRODUCT_EDIT");
  const { data: units } = useQuery<ProductUnit[]>({ queryKey: ["product-units", p.id], queryFn: () => getProductUnits(p.id) });
  const [rows, setRows] = useState<Row[]>([]);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const s = (v: number | null | undefined) => (v == null ? "" : String(Number(v)));
  useEffect(() => {
    if (units) setRows(units.map(u => ({ name: u.name, factor: s(u.factor), barcode: u.barcode ?? "", sellPrice: s(u.sellPrice), buyPrice: s(u.buyPrice) })));
  }, [units]);
  const n = (v: string) => (v.trim() === "" ? null : Number(v));
  const save = useMutation({
    mutationFn: () => saveProductUnits(p.id, rows.map(r => ({ name: r.name, factor: Number(r.factor), barcode: r.barcode || null, sellPrice: n(r.sellPrice), buyPrice: n(r.buyPrice) }))),
    onSuccess: () => { setMsg({ ok: true, text: "Saved" }); qc.invalidateQueries({ queryKey: ["product-units", p.id] }); },
    onError: e => setMsg({ ok: false, text: errorOf(e) }),
  });
  const set = (i: number, patch: Partial<Row>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Card title="Units of measure" testId="product-units">
      <p className="text-sm text-gray-600 mb-2">Stock and cost are kept in <b>{p.unit}</b>. Other units can be sold or bought.</p>
      <table className="w-full text-sm">
        <thead><tr><th className={TH}>Unit</th><th className={TH}>Holds ({p.unit})</th><th className={TH}>Barcode</th><th className={TH}>Sell price £</th><th className={TH}>Buy price £</th><th className={TH} /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {rows.map((r, i) => (
            <tr key={i}>
              <td className={TD}><input className={INPUT} aria-label={`Unit ${i + 1} name`} disabled={!edit} value={r.name} onChange={e => set(i, { name: e.target.value })} /></td>
              <td className={TD}><input className={INPUT} aria-label={`Unit ${i + 1} holds`} type="number" min="0" step="any" disabled={!edit} value={r.factor} onChange={e => set(i, { factor: e.target.value })} /></td>
              <td className={TD}><input className={INPUT} aria-label={`Unit ${i + 1} barcode`} disabled={!edit} value={r.barcode} onChange={e => set(i, { barcode: e.target.value })} /></td>
              <td className={TD}><input className={INPUT} aria-label={`Unit ${i + 1} sell price`} type="number" min="0" step="any" disabled={!edit} placeholder="× holds" value={r.sellPrice} onChange={e => set(i, { sellPrice: e.target.value })} /></td>
              <td className={TD}><input className={INPUT} aria-label={`Unit ${i + 1} buy price`} type="number" min="0" step="any" disabled={!edit} placeholder="× holds" value={r.buyPrice} onChange={e => set(i, { buyPrice: e.target.value })} /></td>
              <td className={`${TD} text-right`}>{edit && <button className="text-red-700 hover:underline" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</button>}</td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={6} className={`${TD} text-gray-400`}>Only {p.unit}</td></tr>}
        </tbody>
      </table>
      {edit && <div className="mt-3 flex items-center gap-2 text-sm">
        <button className={BTN_SECONDARY} onClick={() => setRows([...rows, { name: "", factor: "", barcode: "", sellPrice: "", buyPrice: "" }])}>Add unit</button>
        <button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save units</button>
        {msg && <span role="status" className={msg.ok ? "text-green-700" : "text-red-700"}>{msg.text}</span>}
      </div>}
    </Card>
  );
}
