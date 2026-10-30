"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getReorder, getReorderSuppliers, getWarehouses, raiseReorderPurchaseOrders } from "@/lib/api";
import { Page, PurchaseOrder, ReorderLine, ReorderSupplier, Warehouse } from "@/types";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";
import { BTN_PRIMARY, TH, TD } from "@/components/DetailParts";

// Which suppliers' products are shown: all, one supplier, or products without a supplier
type Scope = { kind: "all" } | { kind: "supplier"; id: number } | { kind: "none" };
type Chosen = { sku: string; supplierName?: string; quantity: string; unitCost?: number; hasSupplier: boolean };

const qty = (n: number) => Number(n).toLocaleString();

/**
 * Cin7's Purchase → Reorder: products whose available stock + what is on order is at or below their minimum, by
 * supplier, with Cin7's suggested quantity (the reorder quantity, or what brings it back to the minimum). Ticked
 * products become one draft purchase order per supplier, with the supplier's codes, prices and VAT.
 */
export default function ReorderPage() {
  const qc = useQueryClient();
  const [scope, setScope] = useState<Scope>({ kind: "all" });
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  // Ticked products, kept across pages and suppliers
  const [chosen, setChosen] = useState<Record<number, Chosen>>({});
  const [warehouseId, setWarehouseId] = useState("");
  const [created, setCreated] = useState<PurchaseOrder[]>([]);
  const [error, setError] = useState("");

  const { data: suppliers = [], refetch: refetchSuppliers } = useQuery<ReorderSupplier[]>({ queryKey: ["reorder-suppliers"], queryFn: getReorderSuppliers });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data, isLoading, isFetching, refetch } = useQuery<Page<ReorderLine>>({
    queryKey: ["reorder", scope, search, page, size],
    queryFn: () => getReorder({
      search: search || undefined, page, size,
      supplierId: scope.kind === "supplier" ? scope.id : undefined, noSupplier: scope.kind === "none" || undefined,
    }),
  });
  const lines = data?.content ?? [];
  const total = suppliers.reduce((t, s) => t + s.products, 0);
  const deliverTo = warehouseId || (warehouses.length === 1 ? String(warehouses[0].id) : "");

  const raise = useMutation({
    mutationFn: () => raiseReorderPurchaseOrders({
      warehouseId: Number(deliverTo),
      lines: Object.entries(chosen).map(([id, c]) => ({ productId: Number(id), quantity: Number(c.quantity) })),
    }),
    onSuccess: (pos: PurchaseOrder[]) => {
      setCreated(pos);
      setChosen({});
      setError("");
      qc.invalidateQueries({ queryKey: ["reorder"] });
      qc.invalidateQueries({ queryKey: ["reorder-suppliers"] });
      qc.invalidateQueries({ queryKey: ["purchase-orders"] });
    },
    onError: (e: any) => setError(e.response?.data?.error || "Could not create the purchase orders"),
  });

  const toggle = (l: ReorderLine, on: boolean) => {
    const next = { ...chosen };
    if (on) next[l.productId] = { sku: l.sku, supplierName: l.supplierName, quantity: String(l.suggestedQuantity > 0 ? l.suggestedQuantity : 1),
      unitCost: l.unitCost, hasSupplier: l.supplierId != null };
    else delete next[l.productId];
    setChosen(next);
  };
  const pageAllChosen = lines.length > 0 && lines.every(l => chosen[l.productId]);
  const chosenList = Object.values(chosen);
  const chosenSuppliers = new Set(chosenList.filter(c => c.hasSupplier).map(c => c.supplierName)).size;
  const withoutSupplier = chosenList.filter(c => !c.hasSupplier);
  const chosenValue = chosenList.reduce((t, c) => t + Number(c.quantity || 0) * Number(c.unitCost ?? 0), 0);
  const pick = (s: Scope) => { setScope(s); setPage(0); };
  const isScope = (s: Scope) => JSON.stringify(s) === JSON.stringify(scope);

  return (
    <div>
      <PageHeader title="Reorder" subtitle="Products whose available stock plus what is on order is at or below their minimum" />

      {created.length > 0 && (
        <div role="status" data-testid="reorder-created" className="mb-4 bg-emerald-50 border border-emerald-200 text-emerald-900 text-sm px-4 py-3 rounded-lg">
          <p className="font-semibold">{created.length} draft purchase order{created.length > 1 ? "s" : ""} created — check and mark them ordered:</p>
          <ul className="mt-1 flex flex-wrap gap-x-4">
            {created.map(po => (
              <li key={po.id}><Link href={`/purchase-orders/${po.id}`} className="underline">{po.orderNumber}</Link> {po.supplierName} · {formatGBP(po.total)}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-[16rem_minmax(0,1fr)] gap-5 items-start">
        <aside data-testid="reorder-suppliers" className="bg-white border border-gray-200 rounded-lg overflow-hidden">
          <h3 className="px-4 py-3 border-b border-gray-200 text-sm font-semibold text-gray-800">Suppliers</h3>
          <nav aria-label="Suppliers to reorder from" className="max-h-[70vh] overflow-y-auto py-1 text-sm">
            <button type="button" onClick={() => pick({ kind: "all" })}
              className={`w-full flex justify-between px-4 py-1.5 text-left ${isScope({ kind: "all" }) ? "bg-gray-100 font-semibold" : "hover:bg-gray-50"}`}>
              <span>All suppliers</span><span className="tabular-nums text-gray-500">{total}</span>
            </button>
            {suppliers.map(s => {
              const sc: Scope = s.supplierId != null ? { kind: "supplier", id: s.supplierId } : { kind: "none" };
              return (
                <button key={s.supplierId ?? "none"} type="button" onClick={() => pick(sc)}
                  className={`w-full flex justify-between gap-2 px-4 py-1.5 text-left ${isScope(sc) ? "bg-gray-100 font-semibold" : "hover:bg-gray-50"}`}>
                  <span className={`truncate ${s.supplierId == null ? "italic text-gray-500" : ""}`}>{s.supplierName ?? "No supplier"}</span>
                  <span className="tabular-nums text-gray-500">{s.products}</span>
                </button>
              );
            })}
          </nav>
        </aside>

        <div className="min-w-0">
          <ListToolbar search={search} onSearch={q => { setSearch(q); setPage(0); }} placeholder="SKU, product or supplier"
            onRefresh={() => { refetch(); refetchSuppliers(); }} refreshing={isFetching} />

          <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className={TH}>
                    <input type="checkbox" aria-label="Choose every product on this page" checked={pageAllChosen}
                      onChange={e => { const next = { ...chosen }; lines.forEach(l => {
                        if (e.target.checked) next[l.productId] = next[l.productId] ?? { sku: l.sku, supplierName: l.supplierName,
                          quantity: String(l.suggestedQuantity > 0 ? l.suggestedQuantity : 1), unitCost: l.unitCost, hasSupplier: l.supplierId != null };
                        else delete next[l.productId];
                      }); setChosen(next); }} />
                  </th>
                  {["Product", "Supplier", "Supplier SKU", "Available", "On order", "Minimum", "Order", "Cost", "Total"].map(h => <th key={h} className={TH}>{h}</th>)}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {isLoading && <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">Loading…</td></tr>}
                {!isLoading && lines.length === 0 && <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">Nothing to reorder</td></tr>}
                {lines.map(l => {
                  const c = chosen[l.productId];
                  const q = Number(c?.quantity ?? l.suggestedQuantity);
                  return (
                    <tr key={l.productId} data-testid={`reorder-row-${l.productId}`} className={c ? "bg-gray-50" : ""}>
                      <td className={TD}>
                        <input type="checkbox" aria-label={`Choose ${l.sku}`} checked={!!c} onChange={e => toggle(l, e.target.checked)} />
                      </td>
                      <td className={TD}><span className="font-mono text-xs text-gray-500">{l.sku}</span><span className="block text-gray-800">{l.name}</span></td>
                      <td className={`${TD} text-gray-700`}>{l.supplierName ?? <span className="italic text-amber-700">No supplier</span>}</td>
                      <td className={`${TD} font-mono text-xs text-gray-600`}>{l.supplierSku ?? "—"}</td>
                      <td className={`${TD} tabular-nums ${Number(l.stock) <= 0 ? "text-red-600 font-semibold" : ""}`}>{qty(l.stock)}</td>
                      <td className={`${TD} tabular-nums text-gray-600`}>{qty(l.onOrder)}</td>
                      <td className={`${TD} tabular-nums text-gray-600`}>{qty(l.minStock)}</td>
                      <td className={TD}>
                        <input type="number" min="0.0001" step="any" aria-label={`Order quantity for ${l.sku}`}
                          value={c?.quantity ?? String(l.suggestedQuantity)} disabled={!c}
                          onChange={e => setChosen({ ...chosen, [l.productId]: { ...c!, quantity: e.target.value } })}
                          className="w-20 border border-gray-300 rounded-md px-2 py-1 text-sm disabled:bg-transparent disabled:border-transparent" />
                      </td>
                      <td className={`${TD} tabular-nums`}>{l.unitCost != null ? formatGBP(l.unitCost) : "—"}</td>
                      <td className={`${TD} tabular-nums`}>{l.unitCost != null ? formatGBP(q * Number(l.unitCost)) : "—"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {data && <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage}
            size={size} onSizeChange={n => { setSize(n); setPage(0); }} />}

          {/* What gets ordered */}
          <div className="sticky bottom-0 mt-4 bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-center gap-3 shadow-sm">
            <span className="text-sm text-gray-700">
              <b>{chosenList.length}</b> product{chosenList.length !== 1 ? "s" : ""} chosen
              {chosenSuppliers > 0 && <> · <b>{chosenSuppliers}</b> supplier{chosenSuppliers !== 1 ? "s" : ""}</>}
              {chosenValue > 0 && <> · about {formatGBP(chosenValue)} before VAT</>}
            </span>
            {withoutSupplier.length > 0 && (
              <span className="text-xs text-amber-700">No supplier: {withoutSupplier.map(c => c.sku).join(", ")} — untick or give them one</span>
            )}
            <div className="ml-auto flex items-center gap-2">
              <label htmlFor="reorder-warehouse" className="text-xs text-gray-500">Deliver to</label>
              <select id="reorder-warehouse" value={deliverTo} onChange={e => setWarehouseId(e.target.value)} className={FILTER_SELECT}>
                <option value="">Choose…</option>
                {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
              <button type="button" disabled={chosenList.length === 0 || !deliverTo || withoutSupplier.length > 0 || raise.isPending}
                onClick={() => { setError(""); raise.mutate(); }} className={BTN_PRIMARY}>
                {raise.isPending ? "Creating..." : `Create draft PO${chosenSuppliers > 1 ? "s" : ""}`}
              </button>
            </div>
            {error && <p role="alert" className="w-full text-sm text-red-600">{error}</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
