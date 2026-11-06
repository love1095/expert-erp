"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bookCourier, cancelCourier, createPickList, getCourierLabel, getCourierServices, getCourierShipments, getDespatchNote, getOrderWarehouse, packOrder,
  shipPacked, trackCourier } from "@/lib/api";
import { CourierQuote, CourierShipment, OrderWarehouse } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, TrailCard } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });
const STAGE: Record<string, string> = { AUTHORISED: "Authorised", PICKING: "Picking", PICKED: "Picked", PACKED: "Packed", SHIPPED: "Shipped" };
const INPUT = "border border-gray-300 rounded px-1.5 py-1 text-xs w-16";

const gbp = (n: number | null) => n == null ? "" : Number(n).toLocaleString("en-GB", { style: "currency", currency: "GBP" });
const COURIER_STATUS: Record<string, string> = { BOOKED: "booked", SHIPPED: "shipped", DELIVERED: "delivered", CANCELLED: "cancelled" };

/**
 * Phase N2 / N3 / O: the order in the warehouse — its stage, picked / packed / shipped per line, cartons, booking a
 * courier for the packed cartons (label, tracking, cancel), ship (with the booking's tracking, e-mailed to the
 * customer when ticked), despatch note.
 */
export default function WarehouseCard({ orderId, onShipped }: { orderId: number; onShipped: () => void }) {
  const qc = useQueryClient();
  const { data: w } = useQuery<OrderWarehouse>({ queryKey: ["order-warehouse", orderId], queryFn: () => getOrderWarehouse(orderId) });
  const [carton, setCarton] = useState({ weight: "", l: "", w: "", h: "" });
  const [ship, setShip] = useState({ carrier: "", tracking: "" });
  const [msg, setMsg] = useState("");
  const [note, setNote] = useState("");
  const [emailCustomer, setEmailCustomer] = useState(true);
  const [quote, setQuote] = useState<CourierQuote | null>(null);
  const [service, setService] = useState("");
  const { data: bookings = [] } = useQuery<CourierShipment[]>({ queryKey: ["order-courier", orderId], queryFn: () => getCourierShipments(orderId) });
  const refresh = () => { qc.invalidateQueries({ queryKey: ["order-warehouse", orderId] }); qc.invalidateQueries({ queryKey: ["order-courier", orderId] }); setMsg(""); };
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
  const doShip = useMutation({ mutationFn: () => shipPacked(orderId, ship.carrier || undefined, ship.tracking || undefined, emailCustomer),
    onSuccess: r => { refresh(); setNote(r.email ?? ""); onShipped(); }, onError: fail });
  const services = useMutation({ mutationFn: () => getCourierServices(orderId),
    onSuccess: (q: CourierQuote) => { setQuote(q); setService(q.services[0]?.serviceCode ?? ""); setMsg(""); }, onError: fail });
  const book = useMutation({ mutationFn: () => bookCourier(orderId, service), onSuccess: () => { setQuote(null); refresh(); }, onError: fail });
  const track = useMutation({ mutationFn: (id: number) => trackCourier(id), onSuccess: refresh, onError: fail });
  const cancelBooking = useMutation({ mutationFn: (id: number) => cancelCourier(id), onSuccess: refresh, onError: fail });
  if (!w) return null;
  const packable = w.lines.some(l => Number(l.picked) > Number(l.packed));
  const packed = w.packages.some(p => p.status === "PACKED");
  const booked = bookings.find(b => b.status === "BOOKED");
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
        {bookings.length > 0 && <ul className="space-y-1" data-testid="courier-bookings">{bookings.map(b => (
          <li key={b.id} className={b.status === "CANCELLED" ? "text-gray-400 line-through" : ""}>
            {b.serviceName} {gbp(b.price)} ·{" "}
            {b.trackingUrl && b.status !== "CANCELLED" ? <a href={b.trackingUrl} target="_blank" rel="noreferrer" className="text-brand-700 hover:underline">{b.trackingNumber}</a> : b.trackingNumber}
            {" · "}<span data-testid="courier-status">{COURIER_STATUS[b.status]}</span>{b.trackingText ? ` (${b.trackingText})` : ""}{b.mode === "fake" ? " · test" : ""}
            {b.status !== "CANCELLED" && <span className="ml-2 space-x-2">
              <button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getCourierLabel(b.id), `Label-${b.trackingNumber}.pdf`)}>Label</button>
              <button className="text-brand-700 hover:underline" onClick={() => track.mutate(b.id)}>Track</button>
              {b.status === "BOOKED" && <button className="text-red-700 hover:underline" onClick={() => cancelBooking.mutate(b.id)}>Cancel booking</button>}
            </span>}
          </li>))}</ul>}
        {packed && !booked && !quote && <button className={BTN_SECONDARY} onClick={() => services.mutate()}>Book courier</button>}
        {quote && <div className="flex flex-wrap items-center gap-1">
          <select aria-label="Courier service" className="border border-gray-300 rounded px-1.5 py-1 text-xs" value={service} onChange={e => setService(e.target.value)}>
            {quote.services.map(o => <option key={o.serviceCode} value={o.serviceCode}>{o.name} · {gbp(o.price)} · {o.days} day{o.days === 1 ? "" : "s"}</option>)}
          </select>
          <button className={BTN_PRIMARY} disabled={!service || book.isPending} onClick={() => book.mutate()}>Book</button>
          <button className="text-gray-500 hover:underline" onClick={() => setQuote(null)}>Close</button>
          {quote.mode === "fake" && <span className="text-gray-500">Test courier: nothing is really booked</span>}
        </div>}
        {packed && booked && <p className="text-gray-600">Ships with {booked.serviceName}, tracking {booked.trackingNumber}</p>}
        {packed && <label className="flex items-center gap-1"><input type="checkbox" checked={emailCustomer} onChange={e => setEmailCustomer(e.target.checked)} />
          E-mail the tracking to the customer</label>}
        {packed && <div className="flex flex-wrap items-center gap-1">
          {!booked && <><input aria-label="Carrier" placeholder="Carrier" className={`${INPUT} w-24`} value={ship.carrier} onChange={e => setShip({ ...ship, carrier: e.target.value })} />
          <input aria-label="Tracking number" placeholder="Tracking" className={`${INPUT} w-28`} value={ship.tracking} onChange={e => setShip({ ...ship, tracking: e.target.value })} /></>}
          <button className={BTN_PRIMARY} onClick={() => doShip.mutate()}>Ship cartons</button>
        </div>}
        {w.packages.length > 0 && <button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getDespatchNote(orderId), `Despatch-${w.orderNumber}.pdf`)}>Despatch note</button>}
        {note && <p className="text-gray-600" data-testid="ship-email">{note}</p>}
        {msg && <p role="alert" className="text-red-700">{msg}</p>}
      </div>
    </TrailCard>
  );
}
