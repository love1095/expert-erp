"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getPickLists, getPurchaseOrder, getPurchaseOrders, getStockTakes, lookupProduct, receivePurchaseLines } from "@/lib/api";
import { FoundProduct, PickList, PurchaseOrder, StockTake } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import StockTakePage from "../stock-takes/[id]/stock-take-page";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });
type Mode = "PICK" | "RECEIVE" | "COUNT";

/**
 * Phase N4: the warehouse on a phone. Big buttons, one scan box: pick an open pick list (its page takes scans), receive
 * a purchase order by scanning what arrived, or count a stock take. A handheld scanner types the code and presses Enter.
 */
export default function ScanPage() {
  const [mode, setMode] = useState<Mode>("PICK");
  return (
    <div className="p-3 max-w-xl mx-auto space-y-3">
      <h1 className="text-xl font-semibold">Scanner</h1>
      <div className="grid grid-cols-3 gap-2" role="tablist">
        {(["PICK", "RECEIVE", "COUNT"] as Mode[]).map(m => (
          <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)}
                  className={`py-3 rounded-lg text-base font-medium border ${mode === m ? "bg-brand-600 text-white border-brand-600" : "bg-white border-gray-300"}`}>
            {({ PICK: "Pick", RECEIVE: "Receive", COUNT: "Count" } as Record<Mode, string>)[m]}</button>
        ))}
      </div>
      {mode === "PICK" && <Pick />}
      {mode === "RECEIVE" && <Receive />}
      {mode === "COUNT" && <Count />}
    </div>
  );
}

function Pick() {
  const { data: lists = [] } = useQuery<PickList[]>({ queryKey: ["pick-lists", "OPEN"], queryFn: () => getPickLists("OPEN") });
  return (
    <ul className="space-y-2" data-testid="scan-pick">
      {lists.map(p => (
        <li key={p.id}><Link href={`/picking/${p.id}`} className="block bg-white border border-gray-200 rounded-lg p-4">
          <span className="font-medium">{p.number}</span> <span className="text-gray-500">{p.orders.join(", ")} · {p.lines.length} lines</span></Link></li>
      ))}
      {lists.length === 0 && <li className="text-gray-400 p-4">Nothing to pick</li>}
    </ul>
  );
}

function Count() {
  const { data: takes = [] } = useQuery<StockTake[]>({ queryKey: ["stock-takes", "COUNTING"], queryFn: () => getStockTakes("COUNTING") });
  const [id, setId] = useState<number | null>(null);
  if (id) return <div className="space-y-2"><button className="text-brand-700 text-sm" onClick={() => setId(null)}>← Stock takes</button><StockTakePage id={id} compact /></div>;
  return (
    <ul className="space-y-2" data-testid="scan-count">
      {takes.map(t => (
        <li key={t.id}><button className="w-full text-left bg-white border border-gray-200 rounded-lg p-4" onClick={() => setId(t.id)}>
          <span className="font-medium">{t.reference}</span> <span className="text-gray-500">{t.warehouse} · {t.countedLines} of {t.lineCount} counted</span></button></li>
      ))}
      {takes.length === 0 && <li className="text-gray-400 p-4">No stock take being counted</li>}
    </ul>
  );
}

function Receive() {
  const qc = useQueryClient();
  const { data: orders } = useQuery<{ content: { id: number; orderNumber: string; supplierName: string }[] }>({
    queryKey: ["purchase-orders", "ORDERED"], queryFn: () => getPurchaseOrders({ status: "ORDERED", size: 100 }) });
  const [poId, setPoId] = useState<number | null>(null);
  const { data: po } = useQuery<PurchaseOrder>({ queryKey: ["purchase-order", poId], queryFn: () => getPurchaseOrder(poId!), enabled: !!poId });
  const [scanned, setScanned] = useState<Record<number, number>>({});
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [key, setKey] = useState(() => crypto.randomUUID());
  const box = useRef<HTMLInputElement>(null);
  const scan = useMutation({
    mutationFn: (c: string) => lookupProduct(c),
    onSuccess: (p: FoundProduct) => {
      const line = po?.lines.find(l => l.productId === p.id && l.outstandingQuantity - (scanned[l.id] ?? 0) > 0) ?? po?.lines.find(l => l.productId === p.id);
      if (!line) setMsg({ ok: false, text: `${p.sku} is not on ${po?.orderNumber}` });
      else { setScanned(s => ({ ...s, [line.id]: (s[line.id] ?? 0) + 1 })); setMsg({ ok: true, text: `✓ ${p.sku}` }); }
      setCode(""); box.current?.focus();
    },
    onError: e => { setMsg({ ok: false, text: errorOf(e) }); setCode(""); box.current?.focus(); },
  });
  const receive = useMutation({
    mutationFn: () => receivePurchaseLines({ id: poId!, data: { idempotencyKey: key,
      lines: Object.entries(scanned).filter(([, n]) => n > 0).map(([l, n]) => ({ purchaseOrderLineId: Number(l), quantity: n })) } }),
    onSuccess: () => { setMsg({ ok: true, text: "Received" }); setScanned({}); setKey(crypto.randomUUID()); qc.invalidateQueries({ queryKey: ["purchase-order", poId] }); },
    onError: e => setMsg({ ok: false, text: errorOf(e) }),
  });
  if (!poId) return (
    <ul className="space-y-2" data-testid="scan-receive">
      {(orders?.content ?? []).map(o => (
        <li key={o.id}><button className="w-full text-left bg-white border border-gray-200 rounded-lg p-4" onClick={() => { setPoId(o.id); setScanned({}); setMsg(null); }}>
          <span className="font-medium">{o.orderNumber}</span> <span className="text-gray-500">{o.supplierName}</span></button></li>
      ))}
      {(orders?.content ?? []).length === 0 && <li className="text-gray-400 p-4">No purchase orders waiting</li>}
    </ul>
  );
  const total = Object.values(scanned).reduce((a, b) => a + b, 0);
  return (
    <div className="space-y-3">
      <button className="text-brand-700 text-sm" onClick={() => setPoId(null)}>← Purchase orders</button>
      <p className="font-medium">{po?.orderNumber} <span className="text-gray-500 font-normal">{po?.supplierName}</span></p>
      <form className="flex gap-2" onSubmit={e => { e.preventDefault(); if (code.trim()) scan.mutate(code.trim()); }}>
        <input ref={box} autoFocus className="border rounded px-3 py-3 flex-1 text-base" placeholder="Scan barcode or type SKU" aria-label="Scan"
               value={code} onChange={e => setCode(e.target.value)} />
        <button className={BTN_SECONDARY} disabled={!po || scan.isPending}>Add</button>
      </form>
      {msg && <p role="alert" className={`text-sm ${msg.ok ? "text-green-700" : "text-red-700"}`}>{msg.text}</p>}
      <table className="w-full text-sm bg-white border border-gray-200 rounded-lg" data-testid="receive-lines">
        <thead className="text-xs text-gray-500 text-left"><tr><th className="px-3 py-2">Product</th><th className="text-right">To come</th><th className="text-right px-3">Scanned</th></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {(po?.lines ?? []).map(l => (
            <tr key={l.id}><td className="px-3 py-2">{l.productSku}</td><td className="text-right">{q(l.outstandingQuantity)}</td>
              <td className={`text-right px-3 ${(scanned[l.id] ?? 0) > l.outstandingQuantity ? "text-amber-700" : ""}`}>{scanned[l.id] ?? 0}</td></tr>
          ))}
        </tbody>
      </table>
      <button className={`${BTN_PRIMARY} w-full py-3`} disabled={total === 0 || receive.isPending} onClick={() => receive.mutate()}>Receive {total} scanned</button>
    </div>
  );
}
