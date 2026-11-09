"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { cancelPickList, confirmPickList, getPickList, getPickListPdf, scanPick } from "@/lib/api";
import { PickList } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });

/**
 * Phase N2 / N4: a pick list in walk order. Scan a product's barcode or SKU into the box (a handheld scanner types it
 * and presses Enter): it counts on the next line of that product; a product not on the list is refused. Confirm
 * takes the quantities picked (what was scanned, or everything when nothing was) out of their bins.
 */
export default function PickListPage({ id }: { id: number }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data: pl } = useQuery<PickList>({ queryKey: ["pick-list", id], queryFn: () => getPickList(id) });
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const scanBox = useRef<HTMLInputElement>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ["pick-list", id] });
  const scan = useMutation({
    mutationFn: (c: string) => scanPick(id, c),
    onSuccess: () => { setMsg({ ok: true, text: `✓ ${code}` }); setCode(""); refresh(); scanBox.current?.focus(); },
    onError: e => { setMsg({ ok: false, text: errorOf(e) }); setCode(""); scanBox.current?.focus(); },
  });
  const confirm = useMutation({ mutationFn: () => confirmPickList(id), onSuccess: () => { setMsg({ ok: true, text: "Picked" }); refresh(); }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const cancel = useMutation({ mutationFn: () => cancelPickList(id), onSuccess: refresh, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  if (!pl) return <div className="p-6 text-sm text-gray-500">Loading…</div>;
  const open = pl.status === "OPEN";
  return (
    <div className="p-4 md:p-6 max-w-4xl space-y-4">
      <PageHeader title={`Pick list ${pl.number}`} />
      <p className="text-sm text-gray-600">{pl.warehouse} · {pl.orders.join(", ")} · <span data-testid="pick-status">{({ OPEN: "To pick", PICKED: "Picked", CANCELLED: "Cancelled" } as Record<string, string>)[pl.status]}</span></p>
      {open && (
        <form className="flex gap-2" onSubmit={e => { e.preventDefault(); if (code.trim()) scan.mutate(code.trim()); }}>
          <input ref={scanBox} autoFocus aria-label="Scan barcode or SKU" placeholder="Scan barcode or SKU" value={code} onChange={e => setCode(e.target.value)}
            className="flex-1 border-2 border-brand-400 rounded-md px-3 py-3 text-lg" inputMode="text" />
          <button className={BTN_SECONDARY} type="submit">Add</button>
        </form>
      )}
      {msg && <p role="status" className={`text-sm font-medium ${msg.ok ? "text-green-700" : "text-red-700"}`}>{msg.text}</p>}
      <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="pick-lines">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-3 py-2">Bin</th><th>Product</th><th>Order</th><th className="text-right">To pick</th><th className="text-right px-3">Picked</th></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {pl.lines.map(l => (
            <tr key={l.id} className={Number(l.picked) >= Number(l.quantity) ? "bg-green-50" : ""}>
              <td className="px-3 py-2 font-mono font-bold">{l.bin ?? "—"}</td>
              <td><b>{l.sku}</b><div className="text-xs text-gray-500">{l.name}</div></td>
              <td><Link href={`/sale-orders/${l.saleOrderId}`} className="text-brand-700 hover:underline">{l.orderNumber}</Link></td>
              <td className="text-right tabular-nums">{q(l.quantity)}</td>
              <td className="text-right tabular-nums px-3">{q(l.picked)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="flex gap-2">
        <button className={BTN_SECONDARY} onClick={() => openPdf(() => getPickListPdf(id), `${pl.number}.pdf`)}>Print</button>
        {open && <button className={BTN_PRIMARY} onClick={async () => { if (await ask({ title: "Confirm the pick?", message: "The picked quantities are taken from their bins.", action: "Confirm pick" })) confirm.mutate(); }}>Confirm pick</button>}
        {open && <button className={BTN_DANGER} onClick={async () => { if (await ask({ title: "Cancel this pick list?", action: "Cancel pick list", danger: true })) cancel.mutate(); }}>Cancel</button>}
      </div>
    </div>
  );
}
