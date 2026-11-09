"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { saveLandedCost, createAdjustment, decideAdjustment, getAdjustments, getAdjustmentSettings, getProductBins, getWarehouses, lookupProduct, saveAdjustmentSettings } from "@/lib/api";
import { FoundProduct, StockAdjustment } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const gbp = (n: number | null | undefined) => n == null ? "" : Number(n).toLocaleString("en-GB", { style: "currency", currency: "GBP" });
const q = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });
const REASON_LABEL: Record<string, string> = { DAMAGE: "Damaged", WRITE_OFF: "Write-off", SAMPLE: "Sample", FOUND: "Found", THEFT: "Theft / lost", COUNT: "Stock take", OTHER: "Other" };
const STATUS_LABEL: Record<string, string> = { PENDING: "Waiting for approval", POSTED: "Posted", REJECTED: "Rejected" };

/**
 * Phase N5: Inventory → Adjustments. Add or take away stock with a reason, in a bin if the warehouse uses them; valued
 * at cost. One worth more than the approval value waits here for a manager to approve or reject.
 */
export default function StockAdjustmentsPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { user, can } = useAuth();
  const manager = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || user?.role === "MANAGER";
  const { data: settings } = useQuery<{ approvalValue: number | null; reasons: string[]; landedCost: string }>({ queryKey: ["adjustment-settings"], queryFn: getAdjustmentSettings });
  const { data: warehouses = [] } = useQuery<{ id: number; name: string }[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: list = [] } = useQuery<StockAdjustment[]>({ queryKey: ["adjustments"], queryFn: () => getAdjustments() });

  const [warehouseId, setWarehouseId] = useState<number | "">("");
  const [code, setCode] = useState("");
  const [product, setProduct] = useState<FoundProduct | null>(null);
  const [binId, setBinId] = useState<number | "">("");
  const [direction, setDirection] = useState<"OUT" | "IN">("OUT");
  const [qty, setQty] = useState("");
  const [reason, setReason] = useState("DAMAGE");
  const [note, setNote] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [limit, setLimit] = useState<string | null>(null);
  const wh = warehouseId === "" ? (warehouses[0]?.id ?? null) : warehouseId;
  const { data: bins } = useQuery<{ warehouseStock: number; unbinned: number; bins: { binId: number; code: string; quantity: number }[] }>({
    queryKey: ["product-bins", product?.id, wh], queryFn: () => getProductBins(product!.id, wh!), enabled: !!product && !!wh });

  const refresh = () => { qc.invalidateQueries({ queryKey: ["adjustments"] }); qc.invalidateQueries({ queryKey: ["product-bins"] }); };
  const find = useMutation({ mutationFn: () => lookupProduct(code), onSuccess: (p: FoundProduct) => { setProduct(p); setMsg(null); }, onError: e => { setProduct(null); setMsg({ ok: false, text: errorOf(e) }); } });
  const save = useMutation({
    mutationFn: () => createAdjustment({ productId: product!.id, warehouseId: wh!, binId: binId === "" ? null : binId,
      quantity: (direction === "OUT" ? -1 : 1) * Number(qty), reasonCode: reason, note: note || undefined }),
    onSuccess: (a: StockAdjustment) => {
      setMsg({ ok: true, text: a.status === "PENDING" ? `Worth ${gbp(a.value)}: waiting for a manager to approve` : `Posted: ${gbp(a.value)}` });
      setQty(""); setNote(""); refresh();
    },
    onError: e => setMsg({ ok: false, text: errorOf(e) }),
  });
  const decide = useMutation({ mutationFn: ({ id, ok }: { id: number; ok: boolean }) => decideAdjustment(id, ok), onSuccess: refresh, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const saveLanded = useMutation({ mutationFn: (mode: string) => saveLandedCost(mode),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["adjustment-settings"] }), onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const saveLimit = useMutation({ mutationFn: () => saveAdjustmentSettings(limit === "" || limit == null ? null : Number(limit)),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["adjustment-settings"] }); setLimit(null); }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });

  const pending = list.filter(a => a.status === "PENDING");
  return (
    <div className="p-6 max-w-6xl space-y-5">
      <PageHeader title="Stock adjustments" />
      {can("STOCK_ADJUST") && (
        <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="adjust-form">
          <h2 className="font-semibold">New adjustment</h2>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-3 text-sm">
            <label className="flex flex-col gap-1">Warehouse
              <select className="border rounded px-2 py-1.5" aria-label="Warehouse" value={wh ?? ""} onChange={e => setWarehouseId(Number(e.target.value))}>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select></label>
            <label className="flex flex-col gap-1 md:col-span-2">Product (SKU or barcode)
              <span className="flex gap-2">
                <input className="border rounded px-2 py-1.5 flex-1" aria-label="Product code" value={code} onChange={e => setCode(e.target.value)}
                       onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); find.mutate(); } }} />
                <button type="button" className={BTN_SECONDARY} onClick={() => find.mutate()}>Find</button>
              </span></label>
            <div className="flex flex-col gap-1">In stock
              <span className="py-1.5 text-gray-700" data-testid="adjust-stock">{product ? `${product.name}: ${q(bins?.warehouseStock ?? 0)}` : "—"}</span></div>
            <label className="flex flex-col gap-1">Bin
              <select className="border rounded px-2 py-1.5" aria-label="Bin" value={binId} onChange={e => setBinId(e.target.value === "" ? "" : Number(e.target.value))}>
                <option value="">{direction === "IN" ? "Default bin" : "No bin / any"}</option>
                {(bins?.bins ?? []).map(b => <option key={b.binId} value={b.binId}>{b.code} ({q(b.quantity)})</option>)}
              </select></label>
            <label className="flex flex-col gap-1">Add or take away
              <select className="border rounded px-2 py-1.5" aria-label="Direction" value={direction} onChange={e => setDirection(e.target.value as "IN" | "OUT")}>
                <option value="OUT">Take away</option><option value="IN">Add</option>
              </select></label>
            <label className="flex flex-col gap-1">Quantity
              <input type="number" min="0" step="any" className="border rounded px-2 py-1.5" aria-label="Quantity" value={qty} onChange={e => setQty(e.target.value)} /></label>
            <label className="flex flex-col gap-1">Reason
              <select className="border rounded px-2 py-1.5" aria-label="Reason" value={reason} onChange={e => setReason(e.target.value)}>
                {(settings?.reasons ?? []).filter(r => r !== "COUNT").map(r => <option key={r} value={r}>{REASON_LABEL[r] ?? r}</option>)}
              </select></label>
            <label className="flex flex-col gap-1 md:col-span-3">Note
              <input className="border rounded px-2 py-1.5" aria-label="Note" value={note} onChange={e => setNote(e.target.value)} /></label>
            <div className="flex items-end">
              <button className={BTN_PRIMARY} disabled={!product || !wh || !(Number(qty) > 0) || save.isPending} onClick={async () => { if (await ask({ title: "Save this stock adjustment?", message: "Stock changes at once, or waits for a manager above the approval value.", action: "Save adjustment" })) save.mutate(); }}>Save adjustment</button>
            </div>
          </div>
          {msg && <p role="alert" className={`text-sm ${msg.ok ? "text-green-700" : "text-red-700"}`}>{msg.text}</p>}
          <p className="text-xs text-gray-500">
            {settings?.approvalValue != null ? <>Adjustments worth more than {gbp(settings.approvalValue)} wait for a manager.</> : <>No approval needed: every adjustment is posted at once.</>}
            {manager && limit == null && <button className="ml-2 text-brand-700 hover:underline" onClick={() => setLimit(settings?.approvalValue == null ? "" : String(settings.approvalValue))}>Change</button>}
          </p>
          {manager && (
            <label className="flex items-center gap-2 text-xs text-gray-600" data-testid="landed-cost">
              Freight and duty on supplier bills (landed cost):
              <select aria-label="Landed cost" className="border rounded px-2 py-1" value={settings?.landedCost ?? "OFF"} onChange={e => saveLanded.mutate(e.target.value)}>
                <option value="OFF">An expense (off)</option>
                <option value="VALUE">Added to the stock cost, by value</option>
                <option value="QUANTITY">Added to the stock cost, by quantity</option>
              </select>
            </label>
          )}
          {manager && limit != null && (
            <div className="flex items-center gap-2 text-sm">
              <label>Approval needed over £<input type="number" min="0" step="0.01" aria-label="Approval value" className="border rounded px-2 py-1 w-28 ml-1" value={limit} onChange={e => setLimit(e.target.value)} /></label>
              <button className={BTN_SECONDARY} onClick={() => saveLimit.mutate()}>Save</button>
              <span className="text-xs text-gray-500">Empty: no approval</span>
            </div>
          )}
        </section>
      )}

      {pending.length > 0 && (
        <section className="bg-amber-50 border border-amber-200 rounded-xl" data-testid="adjust-pending">
          <h2 className="font-semibold px-5 pt-4">Waiting for approval</h2>
          <AdjustTable rows={pending} actions={manager ? a => (
            <span className="flex gap-3 justify-end">
              <button className="text-green-700 hover:underline" onClick={async () => { if (await ask({ title: "Approve this adjustment?", message: "The stock changes.", action: "Approve" })) decide.mutate({ id: a.id, ok: true }); }}>Approve</button>
              <button className="text-red-700 hover:underline" onClick={async () => { if (await ask({ title: "Reject this adjustment?", action: "Reject", danger: true })) decide.mutate({ id: a.id, ok: false }); }}>Reject</button>
            </span>) : undefined} />
        </section>
      )}

      <section className="bg-white border border-gray-200 rounded-xl" data-testid="adjust-list">
        <h2 className="font-semibold px-5 pt-4">Adjustments</h2>
        <AdjustTable rows={list.filter(a => a.status !== "PENDING")} />
      </section>
    </div>
  );
}

function AdjustTable({ rows, actions }: { rows: StockAdjustment[]; actions?: (a: StockAdjustment) => React.ReactNode }) {
  return (
    <table className="w-full text-sm mt-2">
      <thead className="text-xs text-gray-500 text-left"><tr><th className="px-5 py-2">Ref</th><th>Product</th><th>Warehouse / bin</th><th className="text-right">Qty</th>
        <th>Reason</th><th className="text-right">Value</th><th>Status</th><th>By</th><th className="px-5" /></tr></thead>
      <tbody className="divide-y divide-gray-100">
        {rows.map(a => (
          <tr key={a.id}>
            <td className="px-5 py-1.5">ADJ-{a.id}</td>
            <td>{a.sku} <span className="text-gray-500">{a.productName}</span></td>
            <td>{a.warehouse}{a.bin ? ` · ${a.bin}` : ""}</td>
            <td className={`text-right ${a.quantity < 0 ? "text-red-700" : "text-green-700"}`}>{a.quantity > 0 ? "+" : ""}{q(a.quantity)}</td>
            <td>{REASON_LABEL[a.reasonCode] ?? a.reasonCode}{a.note ? <span className="text-gray-500">: {a.note}</span> : null}</td>
            <td className="text-right">{gbp(a.value)}</td>
            <td>{STATUS_LABEL[a.status]}</td>
            <td className="text-gray-500">{a.decidedBy ?? a.requestedBy} · {new Date(a.decidedAt ?? a.requestedAt).toLocaleDateString("en-GB")}</td>
            <td className="px-5">{actions?.(a)}</td>
          </tr>
        ))}
        {rows.length === 0 && <tr><td colSpan={9} className="px-5 py-4 text-gray-400">None</td></tr>}
      </tbody>
    </table>
  );
}
