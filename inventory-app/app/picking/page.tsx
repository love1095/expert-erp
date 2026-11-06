"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createPickList, getPickListPdf, getPickLists, getSaleOrders } from "@/lib/api";
import { PickList } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;

/**
 * Phase N2: Sale → Pick & pack — tick open orders for one pick list (a wave), and the pick lists with their PDF.
 * A pick list is picked from its page (or the phone page, by scanning); packing and shipping are on the order.
 */
export default function PickingPage() {
  const router = useRouter();
  const [ticked, setTicked] = useState<number[]>([]);
  const [msg, setMsg] = useState("");
  const { data: orders } = useQuery<{ content: { id: number; orderNumber: string; customerName: string; warehouseName?: string; fulfilmentStatus: string; orderedAt?: string }[] }>({
    queryKey: ["open-orders-to-pick"], queryFn: () => getSaleOrders({ status: "CONFIRMED", size: 200 }) });
  const { data: lists = [] } = useQuery<PickList[]>({ queryKey: ["pick-lists"], queryFn: () => getPickLists() });
  const create = useMutation({ mutationFn: () => createPickList(ticked), onSuccess: (p: PickList) => router.push(`/picking/${p.id}`), onError: e => setMsg(errorOf(e)) });
  const toggle = (id: number) => setTicked(t => (t.includes(id) ? t.filter(x => x !== id) : [...t, id]));
  return (
    <div className="p-6 max-w-6xl space-y-5">
      <PageHeader title="Pick & pack" />
      <section className="bg-white border border-gray-200 rounded-xl" data-testid="orders-to-pick">
        <div className="px-5 pt-4 flex items-center gap-3">
          <h2 className="font-semibold mr-auto">Open orders</h2>
          <button className={BTN_PRIMARY} disabled={ticked.length === 0 || create.isPending} onClick={() => create.mutate()}>
            Create pick list{ticked.length > 1 ? ` (wave of ${ticked.length})` : ""}</button>
        </div>
        {msg && <p role="alert" className="px-5 text-sm text-red-700">{msg}</p>}
        <table className="w-full text-sm mt-2">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2 w-8" /><th>Order</th><th>Customer</th><th>Shipped</th></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {(orders?.content ?? []).map(o => (
              <tr key={o.id}>
                <td className="px-5 py-1.5"><input type="checkbox" aria-label={`Tick ${o.orderNumber}`} checked={ticked.includes(o.id)} onChange={() => toggle(o.id)} /></td>
                <td><Link href={`/sale-orders/${o.id}`} className="text-brand-700 hover:underline">{o.orderNumber}</Link></td>
                <td>{o.customerName}</td>
                <td className="text-gray-500">{({ UNFULFILLED: "Nothing yet", PARTIALLY_FULFILLED: "Part", FULFILLED: "All" } as Record<string, string>)[o.fulfilmentStatus] ?? o.fulfilmentStatus}</td>
              </tr>
            ))}
            {(orders?.content ?? []).length === 0 && <tr><td colSpan={4} className="px-5 py-4 text-gray-400">No open orders</td></tr>}
          </tbody>
        </table>
      </section>
      <section className="bg-white border border-gray-200 rounded-xl" data-testid="pick-lists">
        <h2 className="font-semibold px-5 pt-4">Pick lists</h2>
        <table className="w-full text-sm mt-2">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">List</th><th>Orders</th><th>Warehouse</th><th>Status</th><th>Made</th><th className="px-5" /></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {lists.map(p => (
              <tr key={p.id}>
                <td className="px-5 py-1.5"><Link href={`/picking/${p.id}`} className="text-brand-700 hover:underline">{p.number}</Link></td>
                <td>{p.orders.join(", ")}</td><td>{p.warehouse}</td>
                <td>{({ OPEN: "To pick", PICKED: "Picked", CANCELLED: "Cancelled" } as Record<string, string>)[p.status]}</td>
                <td className="text-gray-500">{new Date(p.createdAt).toLocaleString("en-GB")}</td>
                <td className="px-5 text-right"><button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getPickListPdf(p.id), `${p.number}.pdf`)}>PDF</button></td>
              </tr>
            ))}
            {lists.length === 0 && <tr><td colSpan={6} className="px-5 py-4 text-gray-400">No pick lists yet</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
