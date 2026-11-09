"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { getCategories, getStockTakes, getWarehouses, lookupProduct, startStockTake } from "@/lib/api";
import { FoundProduct, StockTake } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const gbp = (n: number) => Number(n).toLocaleString("en-GB", { style: "currency", currency: "GBP" });
const TAKE_STATUS: Record<string, string> = { COUNTING: "Counting", POSTED: "Posted", CANCELLED: "Cancelled" };

/**
 * Phase N5: Inventory → Stock take (Cin7: Stocktake). Start one for a whole warehouse or for chosen products or a
 * category: what each bin should hold is frozen, then counted on the stock take's page or the phone scanner page.
 */
export default function StockTakesPage() {
  const ask = useConfirm();
  const router = useRouter();
  const { can } = useAuth();
  const { data: warehouses = [] } = useQuery<{ id: number; name: string }[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: categories = [] } = useQuery<{ id: number; name: string }[]>({ queryKey: ["categories"], queryFn: getCategories });
  const { data: takes = [] } = useQuery<StockTake[]>({ queryKey: ["stock-takes"], queryFn: () => getStockTakes() });
  const [warehouseId, setWarehouseId] = useState<number | "">("");
  const [scope, setScope] = useState<"FULL" | "CATEGORY" | "PRODUCTS">("FULL");
  const [categoryId, setCategoryId] = useState<number | "">("");
  const [code, setCode] = useState("");
  const [chosen, setChosen] = useState<FoundProduct[]>([]);
  const [note, setNote] = useState("");
  const [msg, setMsg] = useState("");
  const wh = warehouseId === "" ? (warehouses[0]?.id ?? null) : warehouseId;
  const add = useMutation({ mutationFn: () => lookupProduct(code), onSuccess: (p: FoundProduct) => { setChosen(c => c.some(x => x.id === p.id) ? c : [...c, p]); setCode(""); setMsg(""); },
    onError: e => setMsg(errorOf(e)) });
  const start = useMutation({
    mutationFn: () => startStockTake({ warehouseId: wh!, note: note || undefined,
      categoryId: scope === "CATEGORY" && categoryId !== "" ? categoryId : null, productIds: scope === "PRODUCTS" ? chosen.map(p => p.id) : undefined }),
    onSuccess: (t: StockTake) => router.push(`/stock-takes/${t.id}`),
    onError: e => setMsg(errorOf(e)),
  });
  const ready = !!wh && (scope === "FULL" || (scope === "CATEGORY" && categoryId !== "") || (scope === "PRODUCTS" && chosen.length > 0));
  return (
    <div className="p-6 max-w-6xl space-y-5">
      <PageHeader title="Stock take" />
      {can("STOCK_ADJUST") && (
        <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3 text-sm" data-testid="start-stock-take">
          <h2 className="font-semibold text-base">New stock take</h2>
          <div className="flex flex-wrap gap-4 items-end">
            <label className="flex flex-col gap-1">Warehouse
              <select className="border rounded px-2 py-1.5" aria-label="Warehouse" value={wh ?? ""} onChange={e => setWarehouseId(Number(e.target.value))}>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select></label>
            <label className="flex flex-col gap-1">Count
              <select className="border rounded px-2 py-1.5" aria-label="Scope" value={scope} onChange={e => setScope(e.target.value as typeof scope)}>
                <option value="FULL">Everything in the warehouse</option>
                <option value="CATEGORY">A category</option>
                <option value="PRODUCTS">Chosen products</option>
              </select></label>
            {scope === "CATEGORY" && (
              <label className="flex flex-col gap-1">Category
                <select className="border rounded px-2 py-1.5" aria-label="Category" value={categoryId} onChange={e => setCategoryId(e.target.value === "" ? "" : Number(e.target.value))}>
                  <option value="">Choose…</option>
                  {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select></label>
            )}
            {scope === "PRODUCTS" && (
              <label className="flex flex-col gap-1">Add a product (SKU or barcode)
                <span className="flex gap-2">
                  <input className="border rounded px-2 py-1.5" aria-label="Product code" value={code} onChange={e => setCode(e.target.value)}
                         onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); add.mutate(); } }} />
                  <button type="button" className={BTN_SECONDARY} onClick={() => add.mutate()}>Add</button>
                </span></label>
            )}
            <label className="flex flex-col gap-1 flex-1 min-w-48">Note
              <input className="border rounded px-2 py-1.5" aria-label="Note" value={note} onChange={e => setNote(e.target.value)} /></label>
            <button className={BTN_PRIMARY} disabled={!ready || start.isPending} onClick={async () => { if (await ask({ title: "Start the stock take?", action: "Start counting" })) start.mutate(); }}>Start counting</button>
          </div>
          {scope === "PRODUCTS" && chosen.length > 0 && (
            <p className="text-gray-600">{chosen.map(p => <span key={p.id} className="inline-block mr-2 px-2 py-0.5 bg-gray-100 rounded">{p.sku}
              <button className="ml-1 text-gray-400" aria-label={`Remove ${p.sku}`} onClick={() => setChosen(c => c.filter(x => x.id !== p.id))}>×</button></span>)}</p>
          )}
          <p className="text-xs text-gray-500">Starting freezes what each bin should hold. Sales and receipts can carry on; posting warns about anything that moved while counting.</p>
          {msg && <p role="alert" className="text-red-700">{msg}</p>}
        </section>
      )}
      <section className="bg-white border border-gray-200 rounded-xl" data-testid="stock-takes">
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">Ref</th><th>Warehouse</th><th>Count</th><th>Status</th>
            <th className="text-right">Counted</th><th className="text-right">Variance</th><th>Started</th><th className="px-5">Note</th></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {takes.map(t => (
              <tr key={t.id}>
                <td className="px-5 py-1.5"><Link href={`/stock-takes/${t.id}`} className="text-brand-700 hover:underline">{t.reference}</Link></td>
                <td>{t.warehouse}</td><td>{t.scope === "FULL" ? "Full" : "Partial"}</td><td>{TAKE_STATUS[t.status]}</td>
                <td className="text-right">{t.countedLines} of {t.lineCount}</td>
                <td className={`text-right ${t.varianceValue < 0 ? "text-red-700" : ""}`}>{gbp(t.varianceValue)}</td>
                <td className="text-gray-500">{new Date(t.createdAt).toLocaleDateString("en-GB")} {t.createdBy}</td>
                <td className="px-5 text-gray-500">{t.note}</td>
              </tr>
            ))}
            {takes.length === 0 && <tr><td colSpan={8} className="px-5 py-4 text-gray-400">No stock takes yet</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
