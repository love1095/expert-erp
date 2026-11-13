"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getKitBuildable, getKitComponents, lookupProduct, saveKitComponents } from "@/lib/api";
import { FoundProduct, KitBuildable, KitComponent } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, Card, TD, TH } from "@/components/DetailParts";
import { useAuth } from "@/contexts/AuthContext";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const qty = (n: number) => Number(n).toLocaleString("en-GB", { maximumFractionDigits: 4 });

type Row = { componentId: number; sku: string; name: string; quantity: string };

/**
 * Phase P2: a kit's components (Cin7: Bill of materials) — what one kit takes of each — and what the stock of the
 * components makes per warehouse. Selling the kit reserves, picks and ships the components.
 */
export function ComponentsSection({ productId }: { productId: number }) {
  const qc = useQueryClient();
  const { can } = useAuth();
  const edit = can("PRODUCT_EDIT");
  const { data: components } = useQuery<KitComponent[]>({ queryKey: ["kit-components", productId], queryFn: () => getKitComponents(productId) });
  const { data: buildable = [] } = useQuery<KitBuildable[]>({ queryKey: ["kit-buildable", productId], queryFn: () => getKitBuildable(productId) });
  const [rows, setRows] = useState<Row[]>([]);
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => {
    if (components) setRows(components.map(c => ({ componentId: c.componentId, sku: c.sku, name: c.name, quantity: String(Number(c.quantity)) })));
  }, [components]);
  const add = useMutation({ mutationFn: () => lookupProduct(code.trim()), onSuccess: (p: FoundProduct) => {
    setRows(r => r.some(x => x.componentId === p.id) ? r : [...r, { componentId: p.id, sku: p.sku, name: p.name, quantity: "1" }]);
    setCode(""); setMsg(null);
  }, onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const save = useMutation({ mutationFn: () => saveKitComponents(productId, rows.map(r => ({ componentId: r.componentId, quantity: Number(r.quantity) }))),
    onSuccess: () => { setMsg({ ok: true, text: "Saved" }); qc.invalidateQueries({ queryKey: ["kit-components", productId] }); qc.invalidateQueries({ queryKey: ["kit-buildable", productId] }); },
    onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  return (
    <>
      <Card title="Components" testId="kit-components">
        <table className="w-full text-sm">
          <thead><tr><th className={TH}>Component</th><th className={TH}>Description</th><th className={`${TH} text-right`}>Quantity in one kit</th><th className={TH} /></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {rows.map((r, i) => (
              <tr key={r.componentId}>
                <td className={TD}><Link href={`/products/${r.componentId}`} className="text-brand-700 hover:underline font-mono">{r.sku}</Link></td>
                <td className={TD}>{r.name}</td>
                <td className={`${TD} text-right`}>{edit
                  ? <input type="number" min="0" step="any" className="border border-gray-300 rounded px-2 py-1 w-24 text-right" aria-label={`Quantity of ${r.sku}`}
                           value={r.quantity} onChange={e => setRows(rows.map((x, j) => j === i ? { ...x, quantity: e.target.value } : x))} />
                  : qty(Number(r.quantity))}</td>
                <td className={`${TD} text-right`}>{edit && <button className="text-red-700 hover:underline" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</button>}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={4} className={`${TD} text-gray-400`}>No components yet: add the products one kit is made of</td></tr>}
          </tbody>
        </table>
        {edit && <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
          <input className="border border-gray-300 rounded-md px-2 py-1.5" placeholder="Component SKU or barcode" aria-label="Component code" value={code}
                 onChange={e => setCode(e.target.value)} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); if (code.trim()) add.mutate(); } }} />
          <button className={BTN_SECONDARY} disabled={!code.trim()} onClick={() => add.mutate()}>Add component</button>
          <button data-confirm-save className={BTN_PRIMARY} disabled={save.isPending || rows.length === 0} onClick={() => save.mutate()}>Save components</button>
          {msg && <span role="status" className={msg.ok ? "text-green-700" : "text-red-700"}>{msg.text}</span>}
        </div>}
      </Card>
      <Card title="What the stock makes" testId="kit-buildable">
        <table className="w-full text-sm">
          <thead><tr><th className={TH}>Warehouse</th><th className={`${TH} text-right`}>On hand</th><th className={`${TH} text-right`}>Available</th></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {buildable.map(b => (
              <tr key={b.warehouseId}><td className={TD}>{b.warehouse}</td><td className={`${TD} text-right tabular-nums`}>{qty(b.onHand)}</td>
                <td className={`${TD} text-right tabular-nums font-medium`}>{qty(b.available)}</td></tr>
            ))}
            {buildable.length === 0 && <tr><td colSpan={3} className={`${TD} text-gray-400`}>None of the components is in stock</td></tr>}
          </tbody>
        </table>
        <p className="mt-2 text-xs text-gray-500">Whole kits the components make: on hand from their stock, available after what open orders reserve. Shopify shows the available kits.</p>
      </Card>
    </>
  );
}
