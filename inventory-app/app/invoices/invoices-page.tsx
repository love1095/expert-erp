"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getInvoices, issueInvoice, cancelInvoice } from "@/lib/api";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Invoice, Page } from "@/types";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import { formatGBP } from "@/lib/currency";
import { INVOICE_STATUS_COLOR as STATUS_COLOR } from "./invoice-view";
import { useConfirm } from "@/components/ConfirmDialog";

// ─── main component ──────────────────────────────────────────────────────────

export default function InvoicesPage() {
  const ask = useConfirm();
  const qc = useQueryClient();

  // list state
  const [page, setPage]         = useState(0);
  const [typeFilter, setTypeFilter] = useState<string>("");
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [search, setSearch] = useState("");
  // Opened from an order with ?q=INV-1
  useEffect(() => {
    const q = new URLSearchParams(window.location.search).get("q");
    if (q) setSearch(q);
  }, []);
  const [size, setSize] = useState(20);

  const router = useRouter();

  // ── queries ────────────────────────────────────────────────────────────────

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Invoice>>({
    queryKey: ["invoices", page, size, typeFilter, statusFilter, search],
    queryFn:  () => getInvoices({ page, size, type: typeFilter || undefined, status: statusFilter || undefined, q: search || undefined }),
  });

  // ── mutations ──────────────────────────────────────────────────────────────

  const issueMut = useMutation({
    mutationFn: (id: number) => issueInvoice(id),
    onSuccess:  () => qc.invalidateQueries({ queryKey: ["invoices"] }),
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to issue invoice"),
  });

  const cancelMut = useMutation({
    mutationFn: (id: number) => cancelInvoice(id),
    onSuccess:  () => qc.invalidateQueries({ queryKey: ["invoices"] }),
    onError:    (e: any) => alert(e.response?.data?.error || "Failed to cancel invoice"),
  });

  const invoiceList = data?.content ?? [];

  // ── render ─────────────────────────────────────────────────────────────────

  return (
    <div>
      {/* header */}
      <PageHeader title="Invoices" subtitle="Sales and purchase invoices and credit notes">
        <Link href="/invoices/new"
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          + New Invoice
        </Link>
      </PageHeader>

      {/* filter bar */}
      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        placeholder="Number, customer or supplier"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
          <label htmlFor="invoices-type-filter" className="sr-only">Type</label>
          <select id="invoices-type-filter" value={typeFilter} onChange={e => { setTypeFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">Sales and purchase</option>
            <option value="SALES">Sales</option>
            <option value="PURCHASE">Purchase</option>
          </select>
          <label htmlFor="invoices-status-filter" className="sr-only">Status</label>
          <select id="invoices-status-filter" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="ISSUED">Issued (awaiting payment)</option>
            <option value="PAID">Paid</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </>} />

      <div>
        <div>
          {isLoading ? (
            <p className="text-gray-400 text-sm">Loading…</p>
          ) : (
            <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
              <table className="list-table">
                <ListHead cols={["Number", "Type", "Party", "Status", "Due", "Total", "Paid", ""]} right={["Total", "Paid"]} />
                <tbody className="divide-y divide-gray-100">
                  {invoiceList.length === 0 && (
                    <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400 text-sm">{search || statusFilter || typeFilter ? "No invoices match" : "No invoices yet"}</td></tr>
                  )}
                  {invoiceList.map(inv => (
                    <tr key={inv.id}
                      data-testid={`invoice-row-${inv.id}`}
                      onClick={() => router.push(`/invoices/${inv.id}`)}
                      className="hover:bg-gray-50 cursor-pointer">
                      <td className="font-mono text-xs text-gray-900 font-semibold">
                        {inv.invoiceNumber}
                        {inv.kind === "CREDIT_NOTE" && <span className="block font-sans text-[10px] font-semibold text-gray-500">Credit note</span>}
                      </td>
                      <td>
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${
                          inv.type === "SALES" ? "bg-sky-100 text-sky-700" : "bg-purple-100 text-purple-700"
                        }`}>{inv.type}</span>
                      </td>
                      <td className="text-gray-700"><Clip text={inv.partyName} width="16rem" /></td>
                      <td>
                        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[inv.status]}`}>
                          {inv.status}
                        </span>
                      </td>
                      <td className="text-gray-500">
                        {inv.dueDate ? new Date(inv.dueDate).toLocaleDateString("en-GB") : "—"}
                      </td>
                      <td className="num text-gray-800">{formatGBP(inv.totalAmount)}</td>
                      <td className="num text-gray-500">{inv.amountPaid > 0 ? formatGBP(inv.amountPaid) : "—"}</td>
                      <td className="text-right space-x-2">
                        {inv.status === "DRAFT" && (
                          <button onClick={async e => { e.stopPropagation(); if (!(await ask({ title: `Issue ${inv.invoiceNumber}?`, message: "It can no longer be edited and is posted to the accounts.", action: "Issue" }))) return; issueMut.mutate(inv.id); }}
                            className="text-xs text-brand-700 hover:underline font-medium">Issue</button>
                        )}
                        {(inv.status === "DRAFT" || inv.status === "ISSUED") && inv.kind !== "CREDIT_NOTE" && (
                          <button onClick={async e => { e.stopPropagation(); if (!(await ask({ title: `Cancel ${inv.invoiceNumber}?`, action: "Cancel invoice", danger: true }))) return; cancelMut.mutate(inv.id); }}
                            className="text-xs text-gray-400 hover:text-red-500 font-medium">Cancel</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && (
            <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage}
              size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
          )}
        </div>

      </div>
    </div>
  );
}
