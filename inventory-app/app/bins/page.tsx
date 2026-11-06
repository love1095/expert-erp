"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteBin, getBins, getProductBins, getProductsPage, getWarehouses, importBins, moveBinStock, saveBin, setDefaultBin } from "@/lib/api";
import { Bin, ProductBins, Warehouse } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm";
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });

/**
 * Phase N1: Inventory → Bins — the bins of a warehouse in walk order (the order pickers walk), a product's stock per
 * bin with its default bin and moves between bins, and opening bins from Cin7's Availability export.
 */
export default function BinsPage() {
  const { can } = useAuth();
  const manage = can("STOCK_TRANSFER");
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses-all"], queryFn: getWarehouses });
  const [wh, setWh] = useState<number | null>(null);
  const warehouseId = wh ?? warehouses[0]?.id ?? null;
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Bins" />
      <label className="flex items-center gap-2 text-sm">Warehouse
        <select aria-label="Warehouse" className={INPUT} value={warehouseId ?? ""} onChange={e => setWh(Number(e.target.value))}>
          {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
        </select>
      </label>
      {warehouseId && <>
        <BinList key={`l-${warehouseId}`} warehouseId={warehouseId} manage={manage} />
        <ProductStock key={`p-${warehouseId}`} warehouseId={warehouseId} manage={manage} />
        {manage && <BinImport key={`i-${warehouseId}`} warehouseId={warehouseId} />}
      </>}
    </div>
  );
}

function BinList({ warehouseId, manage }: { warehouseId: number; manage: boolean }) {
  const qc = useQueryClient();
  const { data: bins = [] } = useQuery<Bin[]>({ queryKey: ["bins", warehouseId], queryFn: () => getBins(warehouseId) });
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState("");
  const refresh = () => qc.invalidateQueries({ queryKey: ["bins", warehouseId] });
  const add = useMutation({ mutationFn: () => saveBin(null, { warehouseId, code }), onSuccess: () => { setCode(""); setMsg(""); refresh(); }, onError: e => setMsg(errorOf(e)) });
  const update = useMutation({ mutationFn: ({ b, patch }: { b: Bin; patch: Partial<Bin> }) => saveBin(b.id, { code: b.code, walkOrder: b.walkOrder, active: b.active, ...patch }),
    onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const remove = useMutation({ mutationFn: (id: number) => deleteBin(id), onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl" data-testid="bin-list">
      <div className="px-5 pt-4 flex flex-wrap items-center gap-2">
        <h2 className="font-semibold mr-auto">Bins, in walk order</h2>
        {manage && <>
          <input aria-label="New bin code" placeholder="e.g. A-01-03" className={INPUT} value={code} onChange={e => setCode(e.target.value)} />
          <button className={BTN_PRIMARY} disabled={!code.trim()} onClick={() => add.mutate()}>Add bin</button>
        </>}
      </div>
      {msg && <p role="alert" className="px-5 pt-2 text-sm text-red-700">{msg}</p>}
      <table className="w-full text-sm mt-2">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">Bin</th><th>Walk order</th><th className="text-right">Units</th><th className="text-right">Products</th><th>Status</th><th className="px-5" /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {bins.map(b => (
            <tr key={b.id} className={b.active ? "" : "text-gray-400"}>
              <td className="px-5 py-1.5 font-mono">{b.code}</td>
              <td>{manage ? <input aria-label={`Walk order of ${b.code}`} type="number" className={`${INPUT} w-20`} defaultValue={b.walkOrder}
                onBlur={e => Number(e.target.value) !== b.walkOrder && update.mutate({ b, patch: { walkOrder: Number(e.target.value) } })} /> : b.walkOrder}</td>
              <td className="text-right tabular-nums">{q(b.units)}</td>
              <td className="text-right tabular-nums">{b.products}</td>
              <td>{b.active ? "Active" : "Inactive"}</td>
              <td className="px-5 text-right space-x-2 whitespace-nowrap">{manage && <>
                <button className="text-xs text-brand-700 hover:underline" onClick={() => update.mutate({ b, patch: { active: !b.active } })}>{b.active ? "Make inactive" : "Make active"}</button>
                {Number(b.units) === 0 && <button className="text-xs text-red-600 hover:underline" onClick={() => remove.mutate(b.id)}>Delete</button>}
              </>}</td>
            </tr>
          ))}
          {bins.length === 0 && <tr><td colSpan={6} className="px-5 py-4 text-gray-400">No bins in this warehouse: its stock is all in one place</td></tr>}
        </tbody>
      </table>
    </section>
  );
}

function ProductStock({ warehouseId, manage }: { warehouseId: number; manage: boolean }) {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [productId, setProductId] = useState<number | null>(null);
  const [move, setMove] = useState({ from: "", to: "", qty: "" });
  const [msg, setMsg] = useState("");
  const { data: found } = useQuery<{ content: { id: number; sku: string; name: string }[] }>({ queryKey: ["bin-product-search", search],
    queryFn: () => getProductsPage({ q: search, size: 8 }), enabled: search.trim().length >= 2 });
  const { data: bins = [] } = useQuery<Bin[]>({ queryKey: ["bins", warehouseId], queryFn: () => getBins(warehouseId) });
  const { data: pb } = useQuery<ProductBins>({ queryKey: ["product-bins", productId, warehouseId], queryFn: () => getProductBins(productId!, warehouseId), enabled: !!productId });
  const refresh = () => { qc.invalidateQueries({ queryKey: ["product-bins"] }); qc.invalidateQueries({ queryKey: ["bins", warehouseId] }); };
  const setDefault = useMutation({ mutationFn: (binId: number | null) => setDefaultBin(productId!, warehouseId, binId), onSuccess: refresh, onError: e => setMsg(errorOf(e)) });
  const doMove = useMutation({
    mutationFn: () => moveBinStock({ productId, warehouseId, fromBinId: move.from ? Number(move.from) : null, toBinId: move.to ? Number(move.to) : null, quantity: Number(move.qty) }),
    onSuccess: () => { setMsg("Moved"); setMove({ from: "", to: "", qty: "" }); refresh(); }, onError: e => setMsg(errorOf(e)),
  });
  const active = bins.filter(b => b.active);
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="product-bins">
      <h2 className="font-semibold">A product&apos;s stock per bin</h2>
      <div className="flex flex-wrap gap-2 items-center text-sm">
        <input aria-label="Find a product" placeholder="SKU or name" className={`${INPUT} w-64`} value={search} onChange={e => setSearch(e.target.value)} />
        {(found?.content ?? []).map(p => (
          <button key={p.id} className={`text-xs px-2 py-1 rounded border ${productId === p.id ? "border-brand-600 text-brand-700" : "border-gray-200"}`}
            onClick={() => { setProductId(p.id); setMsg(""); }}>{p.sku}</button>
        ))}
      </div>
      {pb && (<>
        <p className="text-sm">In the warehouse: <b>{q(pb.warehouseStock)}</b> · not in a bin: <b data-testid="unbinned">{q(pb.unbinned)}</b></p>
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-500 text-left"><tr><th>Bin</th><th className="text-right">Quantity</th><th className="px-4">Default</th></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {pb.bins.map(b => <tr key={b.binId}><td className="py-1 font-mono">{b.code}</td><td className="text-right tabular-nums">{q(b.quantity)}</td>
              <td className="px-4">{pb.defaultBinId === b.binId ? "✓" : ""}</td></tr>)}
            {pb.bins.length === 0 && <tr><td colSpan={3} className="py-2 text-gray-400">In no bin</td></tr>}
          </tbody>
        </table>
        {manage && <div className="flex flex-wrap items-center gap-2 text-sm">
          <span>Default bin</span>
          <select aria-label="Default bin" className={INPUT} value={pb.defaultBinId ?? ""} onChange={e => setDefault.mutate(e.target.value ? Number(e.target.value) : null)}>
            <option value="">None</option>{active.map(b => <option key={b.id} value={b.id}>{b.code}</option>)}
          </select>
          <span className="ml-4">Move</span>
          <input aria-label="Quantity to move" className={`${INPUT} w-20 text-right`} value={move.qty} onChange={e => setMove({ ...move, qty: e.target.value })} />
          <select aria-label="From bin" className={INPUT} value={move.from} onChange={e => setMove({ ...move, from: e.target.value })}>
            <option value="">from no bin</option>{bins.map(b => <option key={b.id} value={b.id}>from {b.code}</option>)}
          </select>
          <select aria-label="To bin" className={INPUT} value={move.to} onChange={e => setMove({ ...move, to: e.target.value })}>
            <option value="">to no bin</option>{active.map(b => <option key={b.id} value={b.id}>to {b.code}</option>)}
          </select>
          <button className={BTN_SECONDARY} disabled={!move.qty} onClick={() => doMove.mutate()}>Move</button>
        </div>}
      </>)}
      {msg && <p role="status" className="text-sm text-gray-600">{msg}</p>}
    </section>
  );
}

function BinImport({ warehouseId }: { warehouseId: number }) {
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<{ applied: boolean; rows: number; binsCreated: number; changed: number; warnings: string[] } | null>(null);
  const [msg, setMsg] = useState("");
  const run = useMutation({
    mutationFn: (apply: boolean) => importBins(file!, warehouseId, apply),
    onSuccess: r => { setResult(r); setMsg(""); if (r.applied) qc.invalidateQueries({ queryKey: ["bins", warehouseId] }); },
    onError: e => setMsg(errorOf(e)),
  });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-2">
      <h2 className="font-semibold">Opening bins from Cin7</h2>
      <p className="text-xs text-gray-500">Cin7&apos;s Availability export (Inventory → Availability → Export) for this location: rows with a Bin set that bin to their OnHand. Import the stock first.</p>
      <div className="flex items-center gap-2 text-sm">
        <input aria-label="Cin7 availability file" type="file" accept=".csv" className="text-xs" onChange={e => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
        <button className={BTN_SECONDARY} disabled={!file} onClick={() => run.mutate(false)}>Preview</button>
      </div>
      {msg && <p role="alert" className="text-sm text-red-700">{msg}</p>}
      {result && <div className="text-sm bg-gray-50 rounded px-3 py-2">
        {result.applied ? "✓ Imported" : "Preview"}: {result.rows} rows with a bin, {result.binsCreated} new bins, {result.changed} quantities set
        <ul className="text-xs text-amber-800 list-disc ml-5">{result.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
        {!result.applied && result.changed > 0 && <button className={BTN_PRIMARY} onClick={() => run.mutate(true)}>Import</button>}
      </div>}
    </section>
  );
}
