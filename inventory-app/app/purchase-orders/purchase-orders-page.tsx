"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getPurchaseOrders, receivePurchaseOrder, cancelPurchaseOrder, getWarehouses } from "@/lib/api";
import { PurchaseOrder, Warehouse, Page } from "@/types";
import { PO_STATUS_COLOR as STATUS_COLOR } from "./purchase-order-view";
import PurchaseOrderForm from "./purchase-order-form";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";

export default function PurchaseOrdersPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState("");
  const [search, setSearch] = useState("");
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
      <PageHeader title="Purchase Orders" subtitle="Orders to suppliers, newest first">
        <button onClick={() => setShowForm(!showForm)}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New PO"}
        </button>
      </PageHeader>

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

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["#", "Supplier", "Warehouse", "Lines", "Required by", "Total (£, inc VAT)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={9} className="px-4 py-8 text-center text-gray-400">{search || statusFilter ? "No purchase orders match" : "No purchase orders yet"}</td></tr>}
                {orders.map(o => (
                  <tr key={o.id} data-testid={`po-row-${o.id}`} onClick={() => router.push(`/purchase-orders/${o.id}`)}
                    className="hover:bg-gray-50 cursor-pointer">
                    <td className="px-4 py-3 font-mono text-xs">
                      <Link href={`/purchase-orders/${o.id}`} onClick={e => e.stopPropagation()} className="font-medium text-gray-800 hover:underline">{o.orderNumber}</Link>
                    </td>
                    <td className="px-4 py-3 font-medium">{o.supplierName}</td>
                    <td className="px-4 py-3 text-gray-600">{o.warehouseName}</td>
                    <td className="px-4 py-3 text-gray-500">{o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{o.requiredBy ?? "—"}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">{formatGBP(o.total)}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right space-x-3 whitespace-nowrap" onClick={e => e.stopPropagation()}>
                      {(o.status === "DRAFT" || o.status === "ORDERED") && (
                        <>
                          <button onClick={() => { if (confirm("Mark as received? This will add stock.")) receiveMutation.mutate(o.id); }}
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
    </div>
  );
}
