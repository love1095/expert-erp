"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteSupplier, getInvoices, getPurchaseOrders, getSupplier } from "@/lib/api";
import { Invoice, Page, PurchaseOrder, Supplier } from "@/types";
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
 * Edit happens here (review R2). Statement and supplier deposits come with the purchase page review.
 */
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

  const remove = useMutation({
    mutationFn: () => deleteSupplier(supplierId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["suppliers-page"] }); qc.invalidateQueries({ queryKey: ["suppliers"] }); router.push("/suppliers"); },
    onError: (e: any) => setError(e.response?.data?.error || "Could not delete the supplier"),
  });

  if (isLoading || !s) return <p className="text-gray-400 text-sm">Loading…</p>;

  const myOrders = (orders?.content ?? []).filter(o => o.supplierId === s.id);
  const myBills = (bills?.content ?? []).filter(b => b.partyName === s.name);
  const open = myBills.filter(b => b.kind !== "CREDIT_NOTE" && b.status === "ISSUED");
  const owed = open.reduce((t, b) => t + Number(b.totalAmount) - Number(b.amountPaid), 0);
  const today = new Date().toISOString().slice(0, 10);
  const overdue = open.filter(b => b.dueDate && b.dueDate < today).reduce((t, b) => t + Number(b.totalAmount) - Number(b.amountPaid), 0);

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
        </TrailCard>
        <TrailCard title="Open bills">
          {open.length === 0 && <p className="text-xs text-gray-400">Nothing to pay</p>}
          {open.map(b => (
            <Link key={b.id} href={`/invoices/${b.id}`} className="flex justify-between text-xs hover:underline">
              <span className="font-mono text-gray-700">{b.invoiceNumber}</span>
              <span className={`tabular-nums ${b.dueDate && b.dueDate < today ? "text-red-600" : "text-gray-700"}`}>
                {formatGBP(Number(b.totalAmount) - Number(b.amountPaid))}
              </span>
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
