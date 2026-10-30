"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSaleOrders, getWarehouses, getCustomers, confirmSaleOrder, undoSaleOrder, cancelSaleOrder } from "@/lib/api";
import { SaleOrder, Warehouse, Customer, Page } from "@/types";
import { useEffect, useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT } from "@/components/ListToolbar";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { STATUS_COLOR, FULFILMENT_LABEL } from "./sale-order-view";
import SaleStages, { OrderDocuments } from "./sale-stages";
import SaleOrderForm from "./sale-order-form";
import { ShopifyImportButton, ShopifyImportPanel } from "./shopify-import";
import { useAuth } from "@/contexts/AuthContext";
import { BulkActionBar, BulkAction, TickBox, useSelection, downloadCsv, UNDO_WARNING, NOT_YET_PRINT, NOT_YET_EMAIL } from "@/components/BulkActions";

// Cin7's actions on ticked sales
const SALE_ACTIONS: BulkAction<SaleOrder>[] = [
  { label: "Undo", confirm: UNDO_WARNING, each: o => undoSaleOrder(o.id) },
  { label: "Fill and authorise", each: o => confirmSaleOrder(o.id) },
  { label: "Print", unavailable: NOT_YET_PRINT },
  { label: "Email", unavailable: NOT_YET_EMAIL },
  { label: "Export", all: rows => downloadCsv(`sales-${new Date().toISOString().slice(0, 10)}.csv`,
      ["Order #", "Status", "Customer", "Reference", "Documents", "Sales rep", "Total inc VAT", "Created"],
      rows.map(o => [o.orderNumber, o.status, o.customerName, o.customerReference, (o.invoices ?? []).map(i => i.invoiceNumber).join(" "),
        o.salesRep, o.total, o.createdAt?.slice(0, 10)])) },
  { label: "Void", danger: true, confirm: "Void the selected sales? Quantities not shipped yet are cancelled.", each: o => cancelSaleOrder(o.id) },
];

export default function SaleOrdersPage() {
  const { user } = useAuth();
  // The Shopify import is admin-only (like the Shopify settings)
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const [showShopifyImport, setShowShopifyImport] = useState(false);
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState("");
  const [search, setSearch] = useState("");
  // Opened from a customer page with ?customer=ID
  const [customerId, setCustomerId] = useState<number | undefined>();
  useEffect(() => {
    const id = Number(new URLSearchParams(window.location.search).get("customer"));
    if (id) setCustomerId(id);
  }, []);

  const { data, isLoading, isFetching, refetch } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", page, size, statusFilter, search, customerId],
    queryFn: () => getSaleOrders({ page, size, customerId, status: statusFilter || undefined, q: search || undefined }),
  });
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers });

  const orders: SaleOrder[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const router = useRouter();
  const selection = useSelection(orders);

  const onCreated = (so: SaleOrder) => {
    qc.invalidateQueries({ queryKey: ["sale-orders"] });
    qc.invalidateQueries({ queryKey: ["customers"] });
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    setShowForm(false);
    router.push(`/sale-orders/${so.id}`);
  };

  return (
    <div>
      <PageHeader title="Sale Orders" subtitle="Quotes and orders, newest first">
        {isAdmin && <ShopifyImportButton onOpen={() => setShowShopifyImport(true)} />}
        <button onClick={() => setShowForm(!showForm)}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New SO"}
        </button>
      </PageHeader>

      {isAdmin && showShopifyImport && <ShopifyImportPanel onClose={() => setShowShopifyImport(false)} />}

      {showForm && (
        <SaleOrderForm customers={customers} warehouses={warehouses} onCreated={onCreated} />
      )}

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        placeholder="Order #, customer, reference, Shopify #"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
        {customerId && (
          <button type="button" onClick={() => { setCustomerId(undefined); setPage(0); router.replace("/sale-orders"); }}
            className="text-xs font-medium px-2.5 py-1.5 rounded-full bg-gray-100 text-gray-700 hover:bg-gray-200">
            One customer: {customers.find(c => c.id === customerId)?.name ?? `#${customerId}`} ×
          </button>
        )}
        <label htmlFor="sale-orders-status-filter" className="sr-only">Show</label>
        <select id="sale-orders-status-filter" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }}
          className={FILTER_SELECT}>
          <option value="">All quotes and orders</option>
          <option value="QUOTE">Quotes</option>
          <option value="DRAFT">Draft orders</option>
          <option value="CONFIRMED">Confirmed</option>
          <option value="COMPLETED">Completed</option>
          <option value="CANCELLED">Cancelled</option>
          <option value="REJECTED">Rejected quotes</option>
        </select>
        </>} />

      <BulkActionBar selected={selection.selected} name={o => o.orderNumber} actions={SALE_ACTIONS}
        onClear={selection.clear} onDone={() => qc.invalidateQueries({ queryKey: ["sale-orders"] })} />

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="pl-4 py-3 w-8"><TickBox checked={selection.allTicked} onChange={selection.toggleAll} label="Select all on this page" testId="so-select-all" /></th>
                  {["Progress", "#", "Customer", "Reference", "Documents", "Sales rep", "Total (£, inc VAT)", "Status", "Created", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders.length === 0 && <tr><td colSpan={11} className="px-4 py-8 text-center text-gray-400">{search || statusFilter ? "No quotes or orders match" : "No sale orders yet"}</td></tr>}
                {orders.map(o => (
                  <tr key={o.id} data-testid={`so-row-${o.id}`} onClick={() => router.push(`/sale-orders/${o.id}`)}
                    className={`cursor-pointer ${selection.isTicked(o.id) ? "bg-brand-50" : "hover:bg-gray-50"}`}>
                    <td className="pl-4 py-3"><TickBox checked={selection.isTicked(o.id)} onChange={() => selection.toggle(o.id)} label={`Select ${o.orderNumber}`} testId={`so-select-${o.id}`} /></td>
                    <td className="px-4 py-3"><SaleStages order={o} /></td>
                    <td className="px-4 py-3 font-mono text-xs">
                      <Link href={`/sale-orders/${o.id}`} onClick={e => e.stopPropagation()} className="font-medium text-gray-800 hover:underline">{o.orderNumber}</Link>
                      {o.source === "SHOPIFY" && (
                        <span className="block mt-1 font-sans text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 w-fit">
                          Shopify {o.externalReference}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-medium">
                      {o.customerName}
                      <span className="block text-[11px] font-normal text-gray-400">{o.warehouseName} · {o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{o.customerReference ?? "—"}</td>
                    <td className="px-4 py-3 max-w-56"><OrderDocuments order={o} /></td>
                    <td className="px-4 py-3 text-gray-600">{o.salesRep ?? "—"}</td>
                    <td className="px-4 py-3 tabular-nums text-gray-700">{formatGBP(o.total)}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                      {o.quoteExpired && <span className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-red-50 text-red-700">Expired</span>}
                      {o.backordered && (
                        <span className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-orange-100 text-orange-800">Backordered</span>
                      )}
                      {o.needsReview && (
                        <span title={o.reviewReason} className="ml-1 text-xs font-semibold px-2 py-0.5 rounded bg-amber-100 text-amber-800">Needs review</span>
                      )}
                      {(o.status === "CONFIRMED" || o.status === "COMPLETED") && (
                        <span className={`block mt-1 text-[11px] ${FULFILMENT_LABEL[o.fulfilmentStatus].color}`}>
                          {FULFILMENT_LABEL[o.fulfilmentStatus].label}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></td>
                    <td className="px-4 py-3 text-right">
                      <Link href={`/sale-orders/${o.id}`} onClick={e => e.stopPropagation()}
                        className="text-xs text-brand-700 hover:underline font-medium">Open ›</Link>
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
