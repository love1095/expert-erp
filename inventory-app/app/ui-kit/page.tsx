"use client";

import { useState } from "react";
import { Button, LinkButton } from "@/components/ui/Button";
import { Checkbox, FormField, Input, Select, Textarea } from "@/components/ui/Field";
import { StatusTabs } from "@/components/ui/StatusTabs";
import { Money } from "@/components/ui/Money";
import { DateText } from "@/components/ui/DateText";
import { Section, StickyActions, SubHeading } from "@/components/ui/Section";

/**
 * Review R8, phase 1: every shared building block on one page, with sample data only (nothing is read from or sent to
 * the server), so the owner can approve the look once before the pages are rebuilt with them. Not in the menu: /ui-kit
 */
const ORDERS = [
  { no: "SO-00265", customer: "Tina Tinh Safety Ltd", date: "2026-11-09", total: 1240, due: 1240, status: "CONFIRMED" },
  { no: "SO-00264", customer: "Northgate Scaffolding", date: "2026-11-07", total: 318.5, due: 0, status: "COMPLETED" },
  { no: "SO-00263", customer: "Harbour Build Co", date: "2026-10-30", total: 86.4, due: 0, status: "DRAFT" },
  { no: "CN-00012", customer: "Northgate Scaffolding", date: "2026-10-28", total: -45, due: 0, status: "COMPLETED" },
];
type Status = "" | "DRAFT" | "CONFIRMED" | "COMPLETED" | "OVERDUE";

export default function UiKitPage() {
  const [status, setStatus] = useState<Status>("");
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const rows = ORDERS.filter(o => !status || o.status === status);

  return (
    <div className="w-full min-h-full overflow-auto bg-gray-50">
      <div className="max-w-5xl mx-auto p-4 sm:p-6 space-y-5" data-testid="ui-kit">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Building blocks</h1>
          <p className="text-sm text-gray-500 mt-0.5">The shared parts every page will be built from. Sample data only.</p>
        </div>

        <Section title="Buttons" hint="One main (dark) button per page; the rest are quieter." testId="kit-buttons">
          <SubHeading>Kinds</SubHeading>
          <div className="flex flex-wrap items-center gap-2">
            <Button kind="primary">Save order</Button>
            <Button>Print</Button>
            <Button kind="danger">Void invoice</Button>
            <Button kind="ghost">+ Add line</Button>
            <LinkButton kind="primary" href="/ui-kit">+ New order</LinkButton>
          </div>
          <SubHeading>Working and switched off</SubHeading>
          <div className="flex flex-wrap items-center gap-2">
            <Button kind="primary" busy={busy} onClick={() => { setBusy(true); setTimeout(() => setBusy(false), 1500); }}>
              {busy ? "Saving…" : "Click: shows saving"}
            </Button>
            <Button kind="primary" busy>Saving…</Button>
            <Button kind="primary" disabled>Save order</Button>
            <Button disabled>Print</Button>
          </div>
          <SubHeading>Sizes</SubHeading>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm">Small (in tables)</Button>
            <Button size="md">Normal</Button>
            <Button size="lg" kind="primary">Large (phone scanner)</Button>
          </div>
        </Section>

        <Section title="Form fields" hint="Label above, hint or error below. A red star marks a field that must be filled." testId="kit-fields">
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            <FormField label="Customer name" required error={name ? null : "Enter the customer name"}>
              {p => <Input {...p} value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Northgate Scaffolding" />}
            </FormField>
            <FormField label="Customer reference" hint="Their purchase order number">
              {p => <Input {...p} defaultValue="PO-7781" />}
            </FormField>
            <FormField label="Price tier">
              {p => <Select {...p} defaultValue="RETAIL"><option value="RETAIL">Retail</option><option value="CONTRACT">Contract</option></Select>}
            </FormField>
            <FormField label="Required by">
              {p => <Input {...p} type="date" defaultValue="2026-11-20" />}
            </FormField>
            <FormField label="Account number" hint="Set by the system">
              {p => <Input {...p} disabled defaultValue="CUS-00412" />}
            </FormField>
            <FormField label="Delivery note" className="sm:col-span-2 lg:col-span-1">
              {p => <Textarea {...p} rows={2} defaultValue="Deliver to the site office, gate 2." />}
            </FormField>
          </div>
          <div className="mt-4 space-y-2">
            <Checkbox label="Tax inclusive prices" defaultChecked />
            <Checkbox label="On credit hold" hint="New orders cannot be confirmed while this is ticked" />
          </div>
        </Section>

        <Section title="Status tabs, money and dates" flush testId="kit-list"
          hint="Tabs with counts on top of a list; money lined up on the right; UK dates on every computer.">
          <div className="px-5 pt-3">
            <StatusTabs<Status> label="Order status" value={status} onChange={setStatus} className="!mb-0"
              tabs={[
                { key: "", label: "All", count: 209 },
                { key: "DRAFT", label: "Draft", count: 4 },
                { key: "CONFIRMED", label: "Confirmed", count: 37 },
                { key: "COMPLETED", label: "Completed", count: 1168 },
                { key: "OVERDUE", label: "Overdue", count: 3, tone: "warn" },
              ]} />
          </div>
          <div className="overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Number", "Customer", "Date"].map(h => <th key={h} className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>)}
                  {["Total", "Still to pay"].map(h => <th key={h} className="text-right text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>)}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {rows.map(o => (
                  <tr key={o.no} className="hover:bg-gray-50">
                    <td className="font-medium text-brand-700">{o.no}</td>
                    <td>{o.customer}</td>
                    <td className="text-gray-600"><DateText iso={o.date} /></td>
                    <td className="num"><Money value={o.total} /></td>
                    <td className="num"><Money value={o.due} tone="due" zero="dash" /></td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={5} className="py-8 text-center text-gray-400">No orders with this status</td></tr>}
              </tbody>
              <tfoot className="border-t border-gray-200 bg-gray-50">
                <tr>
                  <td colSpan={3} className="text-gray-500">{rows.length} shown</td>
                  <td className="num"><Money strong value={rows.reduce((s, o) => s + o.total, 0)} /></td>
                  <td className="num"><Money strong tone="due" value={rows.reduce((s, o) => s + o.due, 0)} /></td>
                </tr>
              </tfoot>
            </table>
          </div>
          <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4 px-5 py-4 border-t border-gray-100 text-sm">
            <div><dt className="text-xs text-gray-400">Plain date</dt><dd><DateText iso="2026-11-09" /></dd></div>
            <div><dt className="text-xs text-gray-400">Date and time</dt><dd><DateText iso="2026-11-09T14:05:00Z" showTime /></dd></div>
            <div><dt className="text-xs text-gray-400">No date</dt><dd><DateText iso={null} /></dd></div>
            <div><dt className="text-xs text-gray-400">Money received</dt><dd><Money value={318.5} tone="good" /></dd></div>
          </dl>
        </Section>

        <StickyActions summary={<>Total <Money strong value={1240} /> <span className="text-gray-400">· 3 lines</span></>}>
          <Button kind="ghost">Cancel</Button>
          <Button>Save as draft</Button>
          <Button kind="primary">Save and confirm</Button>
        </StickyActions>
      </div>
    </div>
  );
}
