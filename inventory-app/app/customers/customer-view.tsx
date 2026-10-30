"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { getCustomer, getSaleOrders, getInvoices } from "@/lib/api";
import { Customer, Invoice, Page, SaleOrder } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import {
  BackLink, RecordHeader, RecordLayout, TrailCard, Card, DetailColumns, Field, BTN_SECONDARY, TH, TD,
} from "@/components/DetailParts";
import SaleStages from "../sale-orders/sale-stages";
import { STATUS_COLOR } from "../sale-orders/sale-order-view";

// One customer on its own page, like Cin7's customer screen: account details, contacts and their sales
export default function CustomerView({ customerId }: { customerId: number }) {
  const router = useRouter();
  const [allContacts, setAllContacts] = useState(false);
  const { data: c, isLoading } = useQuery<Customer>({ queryKey: ["customer", customerId], queryFn: () => getCustomer(customerId) });
  const { data: orders } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", "customer", customerId],
    queryFn: () => getSaleOrders({ customerId, size: 10 }),
  });
  // Invoices are matched on the customer's name (the invoice's party)
  const { data: invoices } = useQuery<Page<Invoice>>({
    queryKey: ["invoices", "customer", c?.name],
    queryFn: () => getInvoices({ type: "SALES", q: c!.name, size: 10 }),
    enabled: !!c,
  });

  if (isLoading || !c) return <p className="text-gray-400 text-sm">Loading…</p>;

  const open = (invoices?.content ?? []).filter(i => i.partyName === c.name && i.kind !== "CREDIT_NOTE" && i.status === "ISSUED");
  const due = open.reduce((t, i) => t + Number(i.totalAmount) - Number(i.amountPaid), 0);
  const today = new Date().toISOString().slice(0, 10);
  const overdue = open.filter(i => i.dueDate && i.dueDate < today).reduce((t, i) => t + Number(i.totalAmount) - Number(i.amountPaid), 0);

  return (
    <div data-testid={`customer-page-${c.id}`}>
      <BackLink href="/customers" />
      <RecordHeader
        title={c.name}
        badges={
          <div className="flex items-center gap-1.5 mt-1.5">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded ${c.status === "ACTIVE" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
              {c.status === "ACTIVE" ? "Active" : "Deprecated"}
            </span>
            {c.source === "SHOPIFY" && <span className="text-xs font-semibold px-2 py-0.5 rounded bg-emerald-100 text-emerald-700">Shopify</span>}
          </div>
        }
        actions={<>
          <Link href={`/customers?edit=${c.id}`} className={BTN_SECONDARY}>Edit</Link>
          <button onClick={() => router.push(`/sale-orders?customer=${c.id}`)} className="bg-brand-600 text-white px-3 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
            All orders
          </button>
        </>}
      />

      <RecordLayout trail={<>
        <TrailCard title="Balance" badge={overdue > 0 ? <span className="text-[11px] font-semibold text-red-600">Overdue</span> : undefined}>
          <div className="flex justify-between"><span className="text-gray-500">Due</span><span className="tabular-nums font-medium">{formatGBP(due)}</span></div>
          <div className="flex justify-between">
            <span className="text-gray-500">Overdue</span>
            <span className={`tabular-nums font-medium ${overdue > 0 ? "text-red-600" : ""}`}>{formatGBP(overdue)}</span>
          </div>
          <div className="flex justify-between"><span className="text-gray-500">Credit limit</span><span className="tabular-nums">{c.creditLimit != null ? formatGBP(c.creditLimit) : "—"}</span></div>
        </TrailCard>

        <TrailCard title="Open invoices">
          {open.length === 0 && <p className="text-xs text-gray-400">Nothing to pay</p>}
          {open.map(i => (
            <Link key={i.id} href={`/invoices/${i.id}`} className="flex justify-between text-xs hover:underline">
              <span className="font-mono text-gray-700">{i.invoiceNumber}</span>
              <span className={`tabular-nums ${i.dueDate && i.dueDate < today ? "text-red-600" : "text-gray-700"}`}>
                {formatGBP(Number(i.totalAmount) - Number(i.amountPaid))}
              </span>
            </Link>
          ))}
        </TrailCard>

        <TrailCard title="Sales">
          <div className="flex justify-between"><span className="text-gray-500">Orders</span><span className="tabular-nums">{c.orderCount}</span></div>
          <p className="text-[11px] text-gray-400">Customer since <ClientDate iso={c.createdAt} /></p>
        </TrailCard>
      </>}>
        <DetailColumns columns={[
          { title: "Account", fields: <>
            <Field label="Payment terms" value={c.paymentTerm} />
            <Field label="Price tier" value={c.priceTier} />
            <Field label="Currency" value={c.currency} />
          </> },
          { title: "Sales and tax", fields: <>
            <Field label="Sales rep" value={c.salesRepresentative} />
            <Field label="Tax rule" value={c.taxRule} />
            <Field label="VAT number" value={c.taxNumber} />
          </> },
          { title: "Shipping", fields: <>
            <Field label="Address" value={c.address && <span className="whitespace-pre-line">{c.address}</span>} />
            <Field label="Carrier" value={c.carrier} />
            <Field label="Location" value={c.location} />
          </> },
        ]} />

        <Card title="Recent sale orders" flush testId={`customer-orders-${c.id}`}
          right={orders && orders.totalElements > (orders.content.length) ? <span className="text-xs text-gray-400">latest {orders.content.length} of {orders.totalElements}</span> : undefined}>
          {(orders?.content.length ?? 0) === 0 ? <p className="p-4 text-sm text-gray-400">No orders yet</p> : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{["Progress", "#", "Status", "Items", "Total", "Created"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {orders!.content.map(o => (
                  <tr key={o.id} onClick={() => router.push(`/sale-orders/${o.id}`)} className="hover:bg-gray-50 cursor-pointer">
                    <td className={TD}><SaleStages order={o} /></td>
                    <td className={`${TD} font-mono text-xs`}>
                      <Link href={`/sale-orders/${o.id}`} onClick={e => e.stopPropagation()} className="font-medium text-gray-800 hover:underline">{o.orderNumber}</Link>
                    </td>
                    <td className={TD}><span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span></td>
                    <td className={`${TD} text-gray-600`}>{o.lines.length}</td>
                    <td className={`${TD} tabular-nums`}>{formatGBP(o.total)}</td>
                    <td className={`${TD} text-xs text-gray-400`}><ClientDate iso={o.createdAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title={`Contacts (${c.contacts.length})`} flush
          right={c.contacts.length > 10 ? (
            <button type="button" onClick={() => setAllContacts(!allContacts)} className="text-xs font-medium text-brand-700 hover:underline">
              {allContacts ? "Show 10" : `Show all ${c.contacts.length}`}
            </button>
          ) : undefined}>
          {c.contacts.length === 0 ? <p className="p-4 text-sm text-gray-400">No contacts</p> : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>{["Name", "Job title", "Email", "Phone", "Mobile"].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {(allContacts ? c.contacts : c.contacts.slice(0, 10)).map((ct, i) => (
                  <tr key={ct.id ?? i}>
                    <td className={TD}>{ct.name || "—"}{ct.defaultContact && <span className="ml-1.5 text-[10px] font-semibold text-gray-500">Default</span>}</td>
                    <td className={`${TD} text-gray-600`}>{ct.jobTitle || "—"}</td>
                    <td className={TD}>{ct.email ? <a href={`mailto:${ct.email}`} className="text-brand-700 hover:underline">{ct.email}</a> : "—"}</td>
                    <td className={`${TD} text-gray-600`}>{ct.phone || "—"}</td>
                    <td className={`${TD} text-gray-600`}>{ct.mobilePhone || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <div className="bg-white border border-gray-200 rounded-lg p-4">
          <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Notes</h3>
          <p className="text-sm text-gray-700 whitespace-pre-line">{c.notes || <span className="text-gray-400">No notes</span>}</p>
        </div>
      </RecordLayout>
    </div>
  );
}
