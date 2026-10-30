"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { getInvoices } from "@/lib/api";
import { Invoice, Page } from "@/types";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { ListToolbar, FILTER_SELECT, ListHead, Clip } from "@/components/ListToolbar";
import { TD } from "@/components/DetailParts";

const STATUS: Record<string, { label: string; dot: string }> = {
  DRAFT:     { label: "Draft", dot: "bg-gray-300" },
  ISSUED:    { label: "Awaiting payment", dot: "bg-amber-400" },
  PAID:      { label: "Paid", dot: "bg-emerald-500" },
  CANCELLED: { label: "Cancelled", dot: "bg-gray-300" },
};

const daysLate = (due?: string) => {
  if (!due) return 0;
  const ms = new Date(new Date().toISOString().slice(0, 10)).getTime() - new Date(due).getTime();
  return Math.max(Math.floor(ms / 86_400_000), 0);
};

/**
 * Cin7's Purchases → Accounting tab: one row per supplier bill (what is owed), with the supplier's invoice number,
 * the order it is for, due date, days overdue, and paid / due amounts.
 */
export default function BillsList() {
  const router = useRouter();
  const [filter, setFilter] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Invoice>>({
    queryKey: ["invoices", "bills", filter, search, page, size],
    queryFn: () => getInvoices({
      type: "PURCHASE", page, size, q: search || undefined,
      overdue: filter === "OVERDUE" || undefined, status: filter && filter !== "OVERDUE" ? filter : undefined,
    }),
  });
  const bills = data?.content ?? [];

  return (
    <div data-testid="bills-list">
      <ListToolbar search={search} onSearch={q => { setSearch(q); setPage(0); }} placeholder="Supplier's invoice number or supplier"
        onRefresh={() => refetch()} refreshing={isFetching}
        filters={<>
          <label htmlFor="bills-filter" className="sr-only">Bills to show</label>
          <select id="bills-filter" value={filter} onChange={e => { setFilter(e.target.value); setPage(0); }} className={FILTER_SELECT}>
            <option value="">All bills</option>
            <option value="ISSUED">Awaiting payment</option>
            <option value="OVERDUE">Overdue</option>
            <option value="PAID">Paid</option>
            <option value="DRAFT">Draft</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </>} />

      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
        <table className="list-table">
          <ListHead cols={["Document", "Order", "Status", "From", "Date", "Due date", "Overdue by", "Total", "Paid", "Due"]} right={["Total", "Paid", "Due"]} />
          <tbody className="divide-y divide-gray-100">
            {isLoading && <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">Loading…</td></tr>}
            {!isLoading && bills.length === 0 && <tr><td colSpan={10} className="px-4 py-8 text-center text-gray-400">No supplier bills</td></tr>}
            {bills.map(b => {
              const late = b.status === "ISSUED" ? daysLate(b.dueDate) : 0;
              const due = b.status === "CANCELLED" ? 0 : Number(b.totalAmount) - Number(b.amountPaid);
              return (
                <tr key={b.id} data-testid={`bill-row-${b.id}`} onClick={() => router.push(`/invoices/${b.id}`)} className="hover:bg-gray-50 cursor-pointer">
                  <td className={`${TD} font-mono text-xs font-semibold text-gray-900`}>
                    {b.invoiceNumber}
                    {b.matchIssueCount > 0 && b.status !== "CANCELLED" && <span className="block font-sans text-[10px] font-semibold text-amber-700">⚠ doesn&apos;t match the PO</span>}
                  </td>
                  <td className={`${TD} font-mono text-xs`}>
                    {b.purchaseOrderId
                      ? <Link href={`/purchase-orders/${b.purchaseOrderId}`} onClick={e => e.stopPropagation()} className="text-gray-700 hover:underline">{b.purchaseOrderNumber}</Link>
                      : "—"}
                  </td>
                  <td className={TD}>
                    <span className="inline-flex items-center gap-1.5 text-xs text-gray-700">
                      <span className={`w-2 h-2 rounded-full ${STATUS[b.status].dot}`} />{STATUS[b.status].label}
                    </span>
                  </td>
                  <td className={`${TD} text-gray-800`}><Clip text={b.partyName} width="16rem" /></td>
                  <td className={`${TD} text-xs text-gray-500`}>{b.issueDate ? new Date(b.issueDate).toLocaleDateString("en-GB") : "—"}</td>
                  <td className={`${TD} text-xs text-gray-500`}>{b.dueDate ? new Date(b.dueDate).toLocaleDateString("en-GB") : "—"}</td>
                  <td className={`${TD} text-xs ${late > 0 ? "text-red-600 font-semibold" : "text-gray-400"}`}>{late} day{late !== 1 ? "s" : ""}</td>
                  <td className={`${TD} text-right tabular-nums`}>{formatGBP(b.totalAmount)}</td>
                  <td className={`${TD} text-right tabular-nums text-gray-600`}>{formatGBP(b.amountPaid)}</td>
                  <td className={`${TD} text-right tabular-nums font-medium ${late > 0 ? "text-red-600" : ""}`}>{formatGBP(due)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {data && <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage}
        size={size} onSizeChange={n => { setSize(n); setPage(0); }} />}
    </div>
  );
}
