"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getPriceTiers, updatePriceTiers } from "@/lib/api";
import { PriceTier } from "@/types";

type Row = { number: string; name: string; discountPercent: string; customers: number };
const INPUT = "border border-gray-300 rounded-md px-2 py-1 text-sm";

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.error ?? "Could not save the price tiers";
}

/**
 * Phase G1: the organisation's price tiers, numbered like Cin7's PriceTier1..10. Tiers 1-4 take each product's own
 * price for the tier (from Cin7's file) unless given a discount; tiers 5-10 are a discount off tier 1.
 */
export default function PriceTiersCard({ isAdmin }: { isAdmin: boolean }) {
  const qc = useQueryClient();
  const { data: tiers } = useQuery<PriceTier[]>({ queryKey: ["price-tiers"], queryFn: getPriceTiers });
  const [rows, setRows] = useState<Row[] | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const shown: Row[] = rows ?? (tiers ?? []).map(t => ({ number: String(t.number), name: t.name,
    discountPercent: t.discountPercent != null ? String(t.discountPercent) : "", customers: t.customers }));

  const save = useMutation({
    mutationFn: () => updatePriceTiers(shown.map(r => ({ number: Number(r.number), name: r.name.trim(),
      discountPercent: r.discountPercent === "" ? null : Number(r.discountPercent) }))),
    onSuccess: (saved: PriceTier[]) => { setRows(null); qc.setQueryData(["price-tiers"], saved); setMessage({ ok: true, text: "Saved" }); },
    onError: e => setMessage({ ok: false, text: errorText(e) }),
  });
  const set = (i: number, patch: Partial<Row>) => { setMessage(null); setRows(shown.map((r, j) => j === i ? { ...r, ...patch } : r)); };
  const next = () => { for (let n = 1; n <= 10; n++) if (!shown.some(r => Number(r.number) === n)) return n; return null; };

  return (
    <section data-testid="price-tiers" className="bg-white border border-gray-200 rounded-xl p-6">
      <h2 className="font-semibold text-gray-800">Price tiers</h2>
      <p className="text-xs text-gray-500 mt-0.5 mb-3">
        A customer&apos;s tier prices their order lines (unless they have a contract price). Tiers 1–4 use each product&apos;s own price for
        that tier, from Cin7&apos;s PriceTier columns (a product without one gets tier 1); with a discount, or for tiers 5–10, the tier is that
        percentage off tier 1. Renaming a tier renames it on its customers.
      </p>
      <table className="text-sm">
        <thead><tr className="text-xs text-gray-500 text-left"><th className="pr-3 py-1">Tier</th><th className="pr-3">Name</th><th className="pr-3">Discount off tier 1</th><th>Customers</th><th></th></tr></thead>
        <tbody>
          {shown.map((r, i) => (
            <tr key={r.number}>
              <td className="pr-3 py-1 tabular-nums text-gray-500">{r.number}</td>
              <td className="pr-3"><input aria-label={`Tier ${r.number} name`} value={r.name} disabled={!isAdmin} maxLength={100}
                onChange={e => set(i, { name: e.target.value })} className={`${INPUT} w-44`} /></td>
              <td className="pr-3">
                {r.number === "1" ? <span className="text-xs text-gray-400">base price</span> : (
                  <span className="inline-flex items-center gap-1">
                    <input aria-label={`Tier ${r.number} discount`} type="number" min="0" max="99.99" step="any" value={r.discountPercent} disabled={!isAdmin}
                      placeholder={Number(r.number) <= 4 ? "product price" : "required"}
                      onChange={e => set(i, { discountPercent: e.target.value })} className={`${INPUT} w-32`} />%
                  </span>
                )}
              </td>
              <td className="tabular-nums text-gray-600 pr-3">{r.customers}</td>
              <td>{isAdmin && r.number !== "1" && r.customers === 0 && (
                <button type="button" onClick={() => setRows(shown.filter((_, j) => j !== i))} className="text-xs text-red-600 hover:underline">Remove</button>)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {isAdmin && (
        <div className="mt-3 flex items-center gap-3">
          {next() != null && <button type="button" onClick={() => setRows([...shown, { number: String(next()), name: "", discountPercent: "", customers: 0 }])}
            className="text-sm text-brand-700 hover:underline">+ Add tier</button>}
          <button type="button" onClick={() => save.mutate()} disabled={save.isPending}
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {save.isPending ? "Saving..." : "Save price tiers"}
          </button>
          {message && <span role="status" className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.ok ? "✓ " : "✕ "}{message.text}</span>}
        </div>
      )}
    </section>
  );
}
