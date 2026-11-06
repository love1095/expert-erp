"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createPickList, getDespatchNote, getOrderWarehouse, packOrder, shipPacked } from "@/lib/api";
import { OrderWarehouse } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, TrailCard } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });
const STAGE: Record<string, string> = { AUTHORISED: "Authorised", PICKING: "Picking", PICKED: "Picked", PACKED: "Packed", SHIPPED: "Shipped" };
const INPUT = "border border-gray-300 rounded px-1.5 py-1 text-xs w-16";

/** Phase N2 / N3: the order in the warehouse — its stage, picked / packed / shipped per line, cartons, ship, despatch note. */
export default function WarehouseCard({ orderId, onShipped }: { orderId: number; onShipped: () => void }) {
  const qc = useQueryClient();
  const { data: w } = useQuery<OrderWarehouse>({ queryKey: ["order-warehouse", orderId], queryFn: () => getOrderWarehouse(orderId) });
  const [carton, setCarton] = useState({ weight: "", l: "", w: "", h: "" });
  const [ship, setShip] = useState({ carrier: "", tracking: "" });
  const [msg, setMsg] = useState("");
  const refresh = () => { qc.invalidateQueries({ queryKey: ["order-warehouse", orderId] }); setMsg(""); };
  const fail = (e: unknown) => setMsg(errorOf(e));
  const pick = useMutation({ mutationFn: () => createPickList([orderId]), onSuccess: refresh, onError: fail });
  const pack = useMutation({
    mutationFn: () => {
      const lines = (w?.lines ?? []).map(l => ({ saleOrderLineId: l.saleOrderLineId, quantity: Number(l.picked) - Number(l.packed) })).filter(l => l.quantity > 0);
      const n = (s: string) => (s ? Number(s) : null);
      return packOrder(orderId, [{ weightKg: n(carton.weight), lengthCm: n(carton.l), widthCm: n(carton.w), heightCm: n(carton.h), lines }]);
    },
    onSuccess: () => { setCarton({ weight: "", l: "", w: "", h: "" }); refresh(); }, onError: fail,
  });
  const doShip = useMutation({ mutationFn: () => shipPacked(orderId, ship.carrier || undefined, ship.tracking || undefined),
    onSuccess: () => { refresh(); onShipped(); }, onError: fail });
  if (!w) return null;
  const packable = w.lines.some(l => Number(l.picked) > Number(l.packed));
  const packed = w.packages.some(p => p.status === "PACKED");
  return (
    <TrailCard title="Warehouse" badge={<span data-testid="warehouse-stage">{STAGE[w.stage] ?? w.stage}</span>} testId="warehouse-card">
      <table className="w-full text-xs">
        <thead className="text-gray-500"><tr><th className="text-left">Product</th><th className="text-right">Picked</th><th className="text-right">Packed</th><th className="text-right">Shipped</th></tr></thead>
        <tbody>{w.lines.map(l => (
          <tr key={l.saleOrderLineId}><td className="truncate max-w-[8rem]">{l.sku}</td><td className="text-right">{q(l.picked)}</td>
            <td className="text-right">{q(l.packed)}</td><td className="text-right">{q(l.shipped)}/{q(l.ordered)}</td></tr>))}</tbody>
      </table>
      {w.packages.length > 0 && <ul className="text-xs mt-2 space-y-0.5">{w.packages.map(p => (
        <li key={p.id}>Carton {p.number}{p.weightKg ? ` · ${q(p.weightKg)} kg` : ""} · {p.lines.map(l => `${q(l.quantity)} × ${l.sku}`).join(", ")} ·{" "}
          <span className={p.status === "SHIPPED" ? "text-green-700" : "text-amber-700"}>{p.status === "SHIPPED" ? "shipped" : "to ship"}</span></li>))}</ul>}
      <div className="mt-2 space-y-2 text-xs">
        {w.stage === "AUTHORISED" && <button className={BTN_SECONDARY} onClick={() => pick.mutate()}>Make pick list</button>}
        {w.stage === "PICKING" && <Link href="/picking" className="text-brand-700 hover:underline">Being picked: open the pick lists</Link>}
        {packable && <div className="flex flex-wrap items-center gap-1">
          <input aria-label="Carton weight kg" placeholder="kg" className={INPUT} value={carton.weight} onChange={e => setCarton({ ...carton, weight: e.target.value })} />
          <input aria-label="Carton length cm" placeholder="L cm" className={INPUT} value={carton.l} onChange={e => setCarton({ ...carton, l: e.target.value })} />
          <input aria-label="Carton width cm" placeholder="W cm" className={INPUT} value={carton.w} onChange={e => setCarton({ ...carton, w: e.target.value })} />
          <input aria-label="Carton height cm" placeholder="H cm" className={INPUT} value={carton.h} onChange={e => setCarton({ ...carton, h: e.target.value })} />
          <button className={BTN_SECONDARY} onClick={() => pack.mutate()}>Pack into a carton</button>
        </div>}
        {packed && <div className="flex flex-wrap items-center gap-1">
          <input aria-label="Carrier" placeholder="Carrier" className={`${INPUT} w-24`} value={ship.carrier} onChange={e => setShip({ ...ship, carrier: e.target.value })} />
          <input aria-label="Tracking number" placeholder="Tracking" className={`${INPUT} w-28`} value={ship.tracking} onChange={e => setShip({ ...ship, tracking: e.target.value })} />
          <button className={BTN_PRIMARY} onClick={() => doShip.mutate()}>Ship cartons</button>
        </div>}
        {w.packages.length > 0 && <button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getDespatchNote(orderId), `Despatch-${w.orderNumber}.pdf`)}>Despatch note</button>}
        {msg && <p role="alert" className="text-red-700">{msg}</p>}
      </div>
    </TrailCard>
  );
}
