"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrders, receivePurchaseOrder, cancelPurchaseOrder, getWarehouses, orderPurchaseOrder, undoPurchaseOrder } from "@/lib/api";
import { PurchaseOrder, Warehouse, Page } from "@/types";
import { PO_STATUS_COLOR as STATUS_COLOR, RECEIVING_LABEL, BILLING_LABEL } from "./purchase-order-view";
import PurchaseOrderForm from "./purchase-order-form";
import PurchaseStages from "./purchase-stages";
import BillsList from "./bills-list";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";
import { BulkActionBar, BulkAction, TickBox, useSelection, downloadCsv, UNDO_WARNING, NOT_YET_PRINT, NOT_YET_EMAIL } from "@/components/BulkActions";

// Cin7's actions on ticked purchases
const PURCHASE_ACTIONS: BulkAction<PurchaseOrder>[] = [
  { label: "Undo", confirm: UNDO_WARNING, each: o => undoPurchaseOrder(o.id) },
  { label: "Fill and authorise", each: o => orderPurchaseOrder(o.id) },
  { label: "Print", unavailable: NOT_YET_PRINT },
  { label: "Email", unavailable: NOT_YET_EMAIL },
  { label: "Export", all: rows => downloadCsv(`purchases-${new Date().toISOString().slice(0, 10)}.csv`,
      ["PO #", "Status", "Supplier", "Supplier reference", "Bills", "Required by", "Total inc VAT", "Created"],
      rows.map(o => [o.orderNumber, o.status, o.supplierName, o.supplierReference, (o.bills ?? []).map(b => b.invoiceNumber).join(" "),
        o.requiredBy, o.total, o.createdAt?.slice(0, 10)])) },
  { label: "Void", danger: true, confirm: "Void the selected purchase orders?", each: o => cancelPurchaseOrder(o.id) },
];

export default function PurchaseOrdersPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState("");
  const [search, setSearch] = useState("");
  // Cin7's two views of purchases: General (one row per order) and Accounting (one row per supplier bill)
  const [tab, setTab] = useState<"general" | "accounting">("general");
  const router = useRouter();
  // Opened with ?q=… (e.g. "other orders from this supplier")
  useEffect(() => {
    const q = new URLSearchParams(window.location.search).get("q");
    if (q) setSearch(q);
  }, []);

  const { data, isLoading, isFetching, refetch } = useQuery<Page<PurchaseOrder>>({
    queryKey: ["purchase-orders", page, size, statusFilter, search],
    queryFn: () => getPurchaseOrders({ page, size, status: statusFilter || undefined, q: search || undefined }),
  });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const orders: PurchaseOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const selection = useSelection(orders);

  const receiveMutation = useMutation({
    mutationFn: receivePurchaseOrder,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["purchase-orders"] });
      qc.invalidateQueries({ queryKey: ["stock-overview"] });
      qc.invalidateQueries({ queryKey: ["products"] });
      qc.invalidateQueries({ queryKey: ["products-page"] });
    },
    onError: (e: any) => alert(e.response?.data?.error || "Failed to receive"),
  });

  const cancelMutation = useMutation({
    mutationFn: cancelPurchaseOrder,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["purchase-orders"] }),
  });

  return (
    <div>
      <PageHeader title="Purchase Orders" subtitle={tab === "general" ? "Orders to suppliers, newest first" : "Supplier bills: what is owed and when"}>
        <button onClick={() => { setShowForm(!showForm); setTab("general"); }}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New PO"}
        </button>
      </PageHeader>

      <div role="tablist" aria-label="Purchases view" className="flex gap-6 border-b border-gray-200 mb-4">
        {([["general", "General"], ["accounting", "Accounting"]] as const).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}
            className={`-mb-px pb-2 text-sm font-medium border-b-2 ${tab === key ? "border-brand-600 text-gray-900" : "border-transparent text-gray-500 hover:text-gray-800"}`}>
            {label}
          </button>
        ))}
      </div>

      {tab === "accounting" ? <BillsList /> : <>
      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        placeholder="PO #, supplier or their reference"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
          <label htmlFor="po-status-filter" className="sr-only">Status</label>
          <select id="po-status-filter" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="ORDERED">Ordered, not received</option>
            <option value="RECEIVED">Received</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </>} />

      {showForm && (
        <PurchaseOrderForm warehouses={warehouses} onCancel={() => setShowForm(false)}
          onSaved={po => { qc.invalidateQueries({ queryKey: ["purchase-orders"] }); setShowForm(false); router.push(`/purchase-orders/${po.id}`); }} />
      )}

      <BulkActionBar selected={selection.selected} name={o => o.orderNumber} actions={PURCHASE_ACTIONS}
        onClear={selection.clear} onDone={() => qc.invalidateQueries({ queryKey: ["purchase-orders"] })} />

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="pl-4 py-3 w-8"><TickBox checked={selection.allTicked} onChange={selection.toggleAll} label="Select all on this page" testId="po-select-all" /></th>
                  {["Progress", "#", "Supplier", "Documents", "Required by", "Total (£, inc VAT)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">{search || statusFilter ? "No purchase orders match" : "No purchase orders yet"}</td></tr>}
                {orders.map(o => (
                  <tr key={o.id} data-testid={`po-row-${o.id}`} onClick={() => router.push(`/purchase-orders/${o.id}`)}
                    className={`cursor-pointer ${selection.isTicked(o.id) ? "bg-brand-50" : "hover:bg-gray-50"}`}>
                    <td className="pl-4 py-3"><TickBox checked={selection.isTicked(o.id)} onChange={() => selection.toggle(o.id)} label={`Select ${o.orderNumber}`} testId={`po-select-${o.id}`} /></td>
                    <td className="px-4 py-3"><PurchaseStages po={o} /></td>
                    <td className="px-4 py-3 font-mono text-xs">
                      <Link href={`/purchase-orders/${o.id}`} onClick={e => e.stopPropagation()} className="font-medium text-gray-800 hover:underline">{o.orderNumber}</Link>
                    </td>
                    <td className="px-4 py-3 font-medium">
                      {o.supplierName}
                      <span className="block text-[11px] font-normal text-gray-400">{o.warehouseName} · {o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</span>
                    </td>
                    <td className="px-4 py-3 max-w-48 text-xs font-mono">
                      {(o.bills?.length ?? 0) === 0 ? <span className="text-gray-300">—</span> : o.bills!.map((b, i) => (
                        <span key={b.id}>{i > 0 && ", "}<span className={b.status === "CANCELLED" ? "line-through text-gray-400" : "text-gray-800"}>{b.invoiceNumber}</span></span>
                      ))}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">{o.requiredBy ?? "—"}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">{formatGBP(o.total)}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                      {o.overDelivered && <span className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-orange-100 text-orange-800">Over</span>}
                      {o.status !== "DRAFT" && o.status !== "CANCELLED" && (
                        <span className={`block mt-1 text-[11px] ${RECEIVING_LABEL[o.receivingStatus].color}`}>
                          {RECEIVING_LABEL[o.receivingStatus].label}{o.closedShort ? " · closed short" : ""}
                          <span className={BILLING_LABEL[o.billingStatus].color}> · {BILLING_LABEL[o.billingStatus].label}</span>
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right space-x-3 whitespace-nowrap" onClick={e => e.stopPropagation()}>
                      {(o.status === "DRAFT" || o.status === "ORDERED") && (
                        <>
                          <button onClick={() => { if (confirm("Receive everything still outstanding? This adds the stock.")) receiveMutation.mutate(o.id); }}
                            className="text-xs text-gray-800 hover:underline font-medium">Receive</button>
                          <button onClick={() => { if (confirm("Cancel this PO?")) cancelMutation.mutate(o.id); }}
                            className="text-xs text-red-500 hover:text-red-700">Cancel</button>
                        </>
                      )}
                      <Link href={`/purchase-orders/${o.id}`} className="text-xs text-brand-700 font-medium hover:underline">Open ›</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage}
            size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
        </>
      )}
      </>}
    </div>
  );
}
