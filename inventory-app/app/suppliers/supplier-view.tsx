"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteSupplier, getInvoices, getPurchaseOrders, getSupplier, getSupplierStatement } from "@/lib/api";
import { Invoice, Page, PurchaseOrder, Supplier, SupplierStatement } from "@/types";
import SupplierDeposits from "../purchase-orders/supplier-deposits";
import { todayIso } from "@/lib/dates";
import { DateText } from "@/components/ui/DateText";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import MoreActions from "@/components/MoreActions";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field, BTN_PRIMARY, TH, TD,
} from "@/components/DetailParts";
import { PO_STATUS_COLOR } from "../purchase-orders/purchase-order-view";
import SupplierForm from "./supplier-form";
import { useConfirm } from "@/components/ConfirmDialog";

/**
 * One supplier on its own page (review S6, like the customer page): details, what is owed, recent purchase orders and bills.
 * Edit happens here (review R2). What is owed, the statement (open bills, unused credit notes and deposits on a day,
 * with a CSV) and the supplier deposits come from the purchase ledger, by the supplier's id (not its name).
 */
const KIND: Record<string, string> = { INVOICE: "Bill", CREDIT_NOTE: "Credit note", DEPOSIT: "Deposit" };

export default function SupplierView({ supplierId }: { supplierId: number }) {
  const ask = useConfirm();
  const router = useRouter();
  const qc = useQueryClient();
  const [error, setError] = useState("");
  // ?edit=1 opens Edit straight away (the list's Edit)
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("edit")) setEditing(true);
  }, []);
  const stopEditing = () => { setEditing(false); router.replace(`/suppliers/${supplierId}`); };

  const { data: s, isLoading } = useQuery<Supplier>({ queryKey: ["supplier", supplierId], queryFn: () => getSupplier(supplierId) });
  // Orders and bills are found by the supplier's name, then kept to this supplier
  const { data: orders } = useQuery<Page<PurchaseOrder>>({
    queryKey: ["purchase-orders", "supplier", supplierId, s?.name],
    queryFn: () => getPurchaseOrders({ q: s!.name, size: 20 }),
    enabled: !!s,
  });
  const { data: bills } = useQuery<Page<Invoice>>({
    queryKey: ["invoices", "supplier", s?.name],
    queryFn: () => getInvoices({ type: "PURCHASE", q: s!.name, size: 20 }),
    enabled: !!s,
  });

  // The statement: what is open with the supplier on a day (today to start with)
  const [asAt, setAsAt] = useState(todayIso());
  const { data: statement } = useQuery<SupplierStatement>({
    queryKey: ["supplier-statement", supplierId, asAt], queryFn: () => getSupplierStatement(supplierId, asAt || undefined),
  });
  const { data: now } = useQuery<SupplierStatement>({ queryKey: ["supplier-statement", supplierId, "today"], queryFn: () => getSupplierStatement(supplierId) });
  const downloadStatement = () => {
    if (!statement) return;
    const cell = (v: unknown) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const rows = [["Type", "Number", "Date", "Due", "Total", "Open", "Days overdue"],
      ...statement.items.map(i => [KIND[i.kind] ?? i.kind, i.number, i.date, i.dueDate ?? "", Number(i.total).toFixed(2), Number(i.open).toFixed(2), i.daysOverdue]),
      ["", "", "", "", "Balance", Number(statement.balance).toFixed(2), ""]];
    const blob = new Blob([rows.map(r => r.map(cell).join(",")).join("\r\n")], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `Statement_${statement.supplierName.replace(/[^\w]+/g, "_")}_${statement.asAt}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const remove = useMutation({
    mutationFn: () => deleteSupplier(supplierId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["suppliers-page"] }); qc.invalidateQueries({ queryKey: ["suppliers"] }); router.push("/suppliers"); },
    onError: (e: any) => setError(e.response?.data?.error || "Could not delete the supplier"),
  });

  if (isLoading || !s) return <p className="text-gray-400 text-sm">Loading…</p>;

  const myOrders = (orders?.content ?? []).filter(o => o.supplierId === s.id);
  const myBills = (bills?.content ?? []).filter(b => b.partyName === s.name);
  // From the purchase ledger (by supplier id): open bills, and the credit held with the supplier
  const open = (now?.items ?? []).filter(i => Number(i.open) > 0);
  const owed = Number(now?.owed ?? 0);
  const overdue = Number(now?.overdue ?? 0);
  const credit = Number(now?.credit ?? 0);

  return (
    <div data-testid={`supplier-page-${s.id}`}>
      <BackLink href="/suppliers" />
      <RecordHeader
        title={s.name}
        badges={
          <div className="flex items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${s.status === "Active" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
              {s.status}
            </span>
          </div>
        }
        actions={editing ? undefined : <>
          <MoreActions actions={[
            { label: "Delete supplier", danger: true, onClick: async () => { if (await ask({ title: `Delete supplier "${s.name}"?`, action: "Delete", danger: true })) remove.mutate(); } },
          ]} />
          <button type="button" onClick={() => setEditing(true)} className={BTN_PRIMARY}>Edit</button>
        </>}
      />
      {error && <p role="alert" data-testid="supplier-error" className="mb-4 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      {editing ? <SupplierForm editing={s} onSaved={stopEditing} onCancel={stopEditing} /> : (
      <RecordLayout trail={<>
        <TrailCard title="Owed" badge={overdue > 0 ? <span className="text-[11px] font-semibold text-red-600">Overdue</span> : undefined}>
          <div className="flex justify-between"><span className="text-gray-500">To pay</span><span className="tabular-nums font-medium">{formatGBP(owed)}</span></div>
          <div className="flex justify-between">
            <span className="text-gray-500">Overdue</span>
            <span className={`tabular-nums font-medium ${overdue > 0 ? "text-red-600" : ""}`}>{formatGBP(overdue)}</span>
          </div>
          {credit > 0 && <div className="flex justify-between"><span className="text-gray-500">Credit held</span><span className="tabular-nums font-medium text-emerald-700">{formatGBP(credit)}</span></div>}
          <div className="flex justify-between border-t border-gray-100 pt-1"><span className="text-gray-500">Balance</span>
            <span data-testid="supplier-balance" className="tabular-nums font-semibold">{formatGBP(Number(now?.balance ?? 0))}</span></div>
        </TrailCard>
        <SupplierDeposits supplierId={s.id} />
        <TrailCard title="Open bills">
          {open.length === 0 && <p className="text-xs text-gray-400">Nothing to pay</p>}
          {open.map(b => (
            <Link key={b.invoiceId} href={`/invoices/${b.invoiceId}`} className="flex justify-between text-xs hover:underline">
              <span className="font-mono text-gray-700">{b.number}</span>
              <span className={`tabular-nums ${b.daysOverdue > 0 ? "text-red-600" : "text-gray-700"}`}>{formatGBP(b.open)}</span>
            </Link>
          ))}
        </TrailCard>
        <TrailCard title="Supplier">
          <p className="text-[11px] text-gray-400">Supplier since <ClientDate iso={s.createdAt} /></p>
        </TrailCard>
      </>}>
        <DetailColumns columns={[
          { title: "Account", fields: <>
            <Field label="Payment terms" value={s.paymentTerm} />
            <Field label="Currency" value={s.currency} />
            <Field label="VAT number" value={s.taxNumber} />
          </> },
          { title: "Contact", fields: <>
            <Field label="Contact" value={s.contactName} />
            <Field label="Phone" value={s.phone} />
            <Field label="Email" value={s.email ? <a href={`mailto:${s.email}`} className="text-brand-700 hover:underline">{s.email}</a> : undefined} />
          </> },
          { title: "Other", fields: <>
            <Field label="Website" value={s.website} />
            <Field label="Comments" value={s.comments} />
          </> },
        ]} />

        <Card title="Statement" flush testId={`supplier-statement-${s.id}`}
          right={<span className="flex items-center gap-2">
            <label htmlFor="supplier-statement-date" className="text-xs text-gray-500">As at</label>
            <input id="supplier-statement-date" type="date" value={asAt} max={todayIso()} onChange={e => setAsAt(e.target.value)}
              className="border border-gray-300 rounded-md px-2 py-1 text-xs" />
            <button type="button" onClick={downloadStatement} disabled={!statement || statement.items.length === 0}
              className="text-xs text-brand-700 hover:underline disabled:text-gray-300">Download CSV</button>
          </span>}>
          {!statement ? <p className="p-4 text-sm text-gray-400">Loading…</p> : statement.items.length === 0 ? <p className="p-4 text-sm text-gray-400">Nothing open with this supplier on that day</p> : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200"><tr>
                <th className={TH}>Type</th><th className={TH}>Number</th><th className={TH}>Date</th><th className={TH}>Due</th>
                <th className={`${TH} text-right`}>Total</th><th className={`${TH} text-right`}>Open</th>
              </tr></thead>
              <tbody className="divide-y divide-gray-100">
                {statement.items.map((i, n) => (
                  <tr key={`${i.kind}-${i.invoiceId ?? n}`}>
                    <td className={`${TD} text-xs text-gray-600`}>{KIND[i.kind] ?? i.kind}</td>
                    <td className={`${TD} font-mono text-xs`}>{i.invoiceId ? <Link href={`/invoices/${i.invoiceId}`} className="text-brand-700 hover:underline">{i.number}</Link> : i.number}</td>
                    <td className={`${TD} text-gray-600`}><DateText iso={i.date} /></td>
                    <td className={`${TD} text-gray-600`}>{i.dueDate ? <DateText iso={i.dueDate} /> : "—"}
                      {i.daysOverdue > 0 && <span className="ml-1.5 text-[11px] text-red-600">{i.daysOverdue} days late</span>}</td>
                    <td className={`${TD} text-right tabular-nums`}>{formatGBP(i.total)}</td>
                    <td className={`${TD} text-right tabular-nums ${Number(i.open) < 0 ? "text-emerald-700" : ""}`}>{formatGBP(i.open)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t border-gray-200 bg-gray-50 text-sm"><tr>
                <td colSpan={5} className={`${TD} text-right font-medium`}>Balance owed to {statement.supplierName}</td>
                <td data-testid="supplier-statement-balance" className={`${TD} text-right tabular-nums font-semibold`}>{formatGBP(statement.balance)}</td>
              </tr></tfoot>
            </table>
          )}
        </Card>

        <Card title="Recent purchase orders" flush testId={`supplier-orders-${s.id}`}
          right={<Link href={`/purchase-orders?q=${encodeURIComponent(s.name)}`} className="text-xs text-brand-700 hover:underline">All orders</Link>}>
          {myOrders.length === 0 ? <p className="p-4 text-sm text-gray-400">No purchase orders yet</p> : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200"><tr>
                <th className={TH}>PO #</th><th className={TH}>Status</th><th className={TH}>Deliver to</th><th className={TH}>Created</th>
              </tr></thead>
              <tbody className="divide-y divide-gray-100">
                {myOrders.map(o => (
                  <tr key={o.id}>
                    <td className={`${TD} font-mono text-xs`}><Link href={`/purchase-orders/${o.id}`} className="text-brand-700 hover:underline">{o.orderNumber}</Link></td>
                    <td className={TD}><span className={`text-xs font-semibold px-2 py-0.5 rounded ${PO_STATUS_COLOR[o.status]}`}>{o.status}</span></td>
                    <td className={`${TD} text-gray-600`}>{o.warehouseName}</td>
                    <td className={`${TD} text-gray-600`}><ClientDate iso={o.createdAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="Recent bills" flush testId={`supplier-bills-${s.id}`}>
          {myBills.length === 0 ? <p className="p-4 text-sm text-gray-400">No bills yet</p> : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200"><tr>
                <th className={TH}>Bill #</th><th className={TH}>Status</th><th className={TH}>Due</th><th className={`${TH} text-right`}>Total</th><th className={`${TH} text-right`}>To pay</th>
              </tr></thead>
              <tbody className="divide-y divide-gray-100">
                {myBills.map(b => (
                  <tr key={b.id}>
                    <td className={`${TD} font-mono text-xs`}>
                      <Link href={`/invoices/${b.id}`} className="text-brand-700 hover:underline">{b.invoiceNumber}</Link>
                      {b.kind === "CREDIT_NOTE" && <span className="ml-1 text-[10px] text-gray-500">credit</span>}
                    </td>
                    <td className={`${TD} text-xs`}>{b.status}</td>
                    <td className={`${TD} text-gray-600`}>{b.dueDate ? <ClientDate iso={b.dueDate} /> : "—"}</td>
                    <td className={`${TD} text-right tabular-nums`}>{formatGBP(b.totalAmount)}</td>
                    <td className={`${TD} text-right tabular-nums`}>{formatGBP(Number(b.totalAmount) - Number(b.amountPaid))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </RecordLayout>
      )}
    </div>
  );
}
