"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cancelStockTake, countStockTakeLine, getBins, getStockTake, postStockTake, scanStockTake } from "@/lib/api";
import { StockTake, StockTakeLine } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const gbp = (n: number | null) => n == null ? "" : Number(n).toLocaleString("en-GB", { style: "currency", currency: "GBP" });
const q = (n: number | null) => n == null ? "" : Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });
const TAKE_STATUS: Record<string, string> = { COUNTING: "Counting", POSTED: "Posted", CANCELLED: "Cancelled" };

/**
 * Phase N5 / N4: one stock take. Lines in bin walk order; type the count of a line, or scan products into the box (a
 * handheld scanner types the code and presses Enter) with the bin being counted. The variance and its value show as you
 * go; lines not counted are left alone. A manager posts it: the variances become stock-take adjustments.
 */
export default function StockTakePage({ id, compact = false }: { id: number; compact?: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { user, can } = useAuth();
  const manager = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || user?.role === "MANAGER";
  const { data: st } = useQuery<StockTake>({ queryKey: ["stock-take", id], queryFn: () => getStockTake(id) });
  const { data: bins = [] } = useQuery<{ id: number; code: string; active: boolean }[]>({ queryKey: ["bins", st?.warehouseId], queryFn: () => getBins(st!.warehouseId), enabled: !!st });
  const [code, setCode] = useState("");
  const [binId, setBinId] = useState<number | "">("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const scanBox = useRef<HTMLInputElement>(null);
  const put = (t: StockTake) => qc.setQueryData(["stock-take", id], t);
  const scan = useMutation({
    mutationFn: (c: string) => scanStockTake(id, c, binId === "" ? null : binId),
    onSuccess: (t: StockTake) => { setMsg({ ok: true, text: `✓ ${code}` }); setCode(""); put(t); scanBox.current?.focus(); },
    onError: e => { setMsg({ ok: false, text: errorOf(e) }); setCode(""); scanBox.current?.focus(); },
  });
  const count = useMutation({ mutationFn: ({ line, v }: { line: number; v: number | null }) => countStockTakeLine(id, line, v), onSuccess: put,
    onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const post = useMutation({ mutationFn: () => postStockTake(id), onSuccess: (r: { stockTake: StockTake; adjustments: number; warnings: string[] }) => {
    put(r.stockTake); setWarnings(r.warnings); setMsg({ ok: true, text: `Posted: ${r.adjustments} adjustment${r.adjustments === 1 ? "" : "s"}` });
    qc.invalidateQueries({ queryKey: ["stock-takes"] });
  }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const cancel = useMutation({ mutationFn: () => cancelStockTake(id), onSuccess: put, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  if (!st) return <div className="p-6 text-sm text-gray-500">Loading…</div>;
  const counting = st.status === "COUNTING" && can("STOCK_ADJUST");
  return (
    <div className={compact ? "space-y-3" : "p-4 md:p-6 max-w-5xl space-y-4"}>
      {!compact && <PageHeader title={`Stock take ${st.reference}`} />}
      <p className="text-sm text-gray-600">{st.warehouse} · {st.scope === "FULL" ? "Full" : "Partial"} · <span data-testid="take-status">{TAKE_STATUS[st.status]}</span>
        {" · "}{st.countedLines} of {st.lineCount} counted · variance <span data-testid="take-variance">{gbp(st.varianceValue)}</span>
        {st.note ? ` · ${st.note}` : ""}</p>
      {counting && (
        <form className="flex flex-wrap gap-2 items-center" onSubmit={e => { e.preventDefault(); if (code.trim()) scan.mutate(code.trim()); }}>
          <select className="border rounded px-2 py-2 text-sm" aria-label="Counting bin" value={binId} onChange={e => setBinId(e.target.value === "" ? "" : Number(e.target.value))}>
            <option value="">No bin</option>
            {bins.filter(b => b.active).map(b => <option key={b.id} value={b.id}>{b.code}</option>)}
          </select>
          <input ref={scanBox} autoFocus className="border rounded px-3 py-2 flex-1 min-w-40 text-base" placeholder="Scan barcode or type SKU" aria-label="Scan"
                 value={code} onChange={e => setCode(e.target.value)} />
          <button className={BTN_PRIMARY} disabled={scan.isPending}>Count 1</button>
        </form>
      )}
      {msg && <p role="alert" className={`text-sm ${msg.ok ? "text-green-700" : "text-red-700"}`}>{msg.text}</p>}
      {warnings.length > 0 && <ul className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded p-3 list-disc pl-6" data-testid="take-warnings">
        {warnings.map(w => <li key={w}>{w}</li>)}</ul>}
      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
        <table className="w-full text-sm" data-testid="take-lines">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">Bin</th><th>Product</th><th className="text-right">Expected</th>
            <th className="text-right">Counted</th><th className="text-right">Variance</th>{!compact && <th className="text-right px-4">Value</th>}</tr></thead>
          <tbody className="divide-y divide-gray-100">
            {st.lines.map(l => <Row key={`${l.id}-${l.counted}`} l={l} editable={counting} compact={compact} onCount={v => count.mutate({ line: l.id, v })} />)}
          </tbody>
        </table>
      </div>
      {st.status === "COUNTING" && (
        <div className="flex gap-3">
          {manager && <button className={BTN_PRIMARY} disabled={post.isPending || st.countedLines === 0} onClick={async () => { if (await ask({ title: "Post this stock take?", message: "The differences are booked as stock adjustments.", action: "Post" })) post.mutate(); }}>Post stock take</button>}
          {can("STOCK_ADJUST") && <button className={BTN_DANGER} onClick={async () => { if (await ask({ title: "Cancel this stock take?", action: "Cancel stock take", danger: true })) cancel.mutate(); }}>Cancel stock take</button>}
          {!manager && <span className="text-sm text-gray-500 self-center">A manager posts the stock take.</span>}
        </div>
      )}
      {!compact && <Link href="/stock-takes" className="text-sm text-brand-700 hover:underline">All stock takes</Link>}
    </div>
  );
}

function Row({ l, editable, compact, onCount }: { l: StockTakeLine; editable: boolean; compact: boolean; onCount: (v: number | null) => void }) {
  const [v, setV] = useState(l.counted == null ? "" : String(Number(l.counted)));
  const save = () => {
    const next = v.trim() === "" ? null : Number(v);
    if (next !== (l.counted == null ? null : Number(l.counted))) onCount(next);
  };
  return (
    <tr data-testid={`take-line-${l.sku}${l.bin ? `-${l.bin}` : ""}`}>
      <td className="px-4 py-1.5">{l.bin ?? <span className="text-gray-400">No bin</span>}</td>
      <td>{l.sku} <span className="text-gray-500">{l.name}</span></td>
      <td className="text-right">{q(l.expected)}</td>
      <td className="text-right">{editable
        ? <input type="number" min="0" step="any" className="border rounded px-2 py-1 w-20 text-right" aria-label={`Counted ${l.sku}${l.bin ? ` ${l.bin}` : ""}`}
                 value={v} onChange={e => setV(e.target.value)} onBlur={save} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); save(); } }} />
        : q(l.counted)}</td>
      <td className={`text-right ${l.variance != null && l.variance < 0 ? "text-red-700" : l.variance ? "text-green-700" : ""}`}>
        {l.variance == null ? <span className="text-gray-400">—</span> : `${l.variance > 0 ? "+" : ""}${q(l.variance)}`}</td>
      {!compact && <td className="text-right px-4">{gbp(l.varianceValue)}</td>}
    </tr>
  );
}
