"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getVatReturn, getVatReturnCsv } from "@/lib/api";
import { VatReturn, VatType } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";
import { isoDay } from "../customers/statement-card";

const BOXES: [keyof VatReturn["boxes"], string, boolean][] = [
  ["box1", "VAT due on sales and other outputs", false],
  ["box2", "VAT due on acquisitions from EU member states (NI)", false],
  ["box3", "Total VAT due (box 1 + box 2)", true],
  ["box4", "VAT reclaimed on purchases and other inputs", false],
  ["box5", "Net VAT to pay to HMRC or reclaim", true],
  ["box6", "Total value of sales, excluding VAT", false],
  ["box7", "Total value of purchases, excluding VAT", false],
  ["box8", "Total value of supplies to EU member states (NI)", false],
  ["box9", "Total value of acquisitions from EU member states (NI)", false],
];
const TYPE: Record<VatType, string> = {
  STANDARD: "Standard", REDUCED: "Reduced", ZERO: "Zero rated", EXEMPT: "Exempt", NO_VAT: "Outside the scope",
  REVERSE_CHARGE: "Reverse charge", EC_ACQUISITION: "EC acquisition", FULL_VAT: "All VAT (import)",
};
// VAT quarters end in one of three month groups (HMRC's "stagger")
const STAGGERS = [{ value: 2, label: "Mar, Jun, Sep, Dec" }, { value: 0, label: "Jan, Apr, Jul, Oct" }, { value: 1, label: "Feb, May, Aug, Nov" }];

/** The last few VAT quarters for a stagger, newest first: [from, to, label]. */
function quarters(stagger: number, count = 6): [string, string, string][] {
  const now = new Date();
  const out: [string, string, string][] = [];
  // The quarter-end month on or before this month
  let end = new Date(now.getFullYear(), now.getMonth(), 1);
  while (end.getMonth() % 3 !== stagger) end = new Date(end.getFullYear(), end.getMonth() - 1, 1);
  for (let i = 0; i < count; i++) {
    const last = new Date(end.getFullYear(), end.getMonth() + 1, 0);
    const first = new Date(end.getFullYear(), end.getMonth() - 2, 1);
    const label = `${first.toLocaleString("en-GB", { month: "short" })}–${last.toLocaleString("en-GB", { month: "short", year: "numeric" })}`;
    out.push([isoDay(first), isoDay(last), label]);
    end = new Date(end.getFullYear(), end.getMonth() - 3, 1);
  }
  return out;
}

/**
 * Phase H3: the VAT return for a period in HMRC's nine boxes (standard scheme: by invoice and bill date), with the
 * rates and documents behind each figure. It is filed from Xero (Making Tax Digital); this is the figure to check.
 */
export default function VatReturnPage() {
  const [stagger, setStagger] = useState(() => (typeof window === "undefined" ? 2 : Number(localStorage.getItem("vat-stagger") ?? 2)));
  const options = quarters(stagger);
  // The last finished quarter by default
  const [period, setPeriod] = useState<{ from: string; to: string }>(() => {
    const q = quarters(stagger);
    const done = q.find(([, to]) => to < isoDay(new Date())) ?? q[0];
    return { from: done[0], to: done[1] };
  });
  const [error, setError] = useState("");
  const { data, isLoading, isError } = useQuery<VatReturn>({ queryKey: ["vat-return", period], queryFn: () => getVatReturn(period.from, period.to) });

  const exportCsv = async () => {
    try {
      const blob = await getVatReturnCsv(period.from, period.to);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `VAT_${period.from}_${period.to}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch { setError("Could not export"); }
  };

  const b = data?.boxes;
  return (
    <div>
      <PageHeader title="VAT return" subtitle="Standard scheme: invoices and bills by their date, paid or not. Filed from Xero; check it against this.">
        <select aria-label="VAT quarters end in" value={stagger} className="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white"
          onChange={e => { const v = Number(e.target.value); setStagger(v); localStorage.setItem("vat-stagger", String(v)); }}>
          {STAGGERS.map(s => <option key={s.value} value={s.value}>Quarters end {s.label}</option>)}
        </select>
        <select aria-label="VAT quarter" value={options.find(([f, t]) => f === period.from && t === period.to) ? `${period.from}|${period.to}` : ""}
          onChange={e => { if (e.target.value) { const [from, to] = e.target.value.split("|"); setPeriod({ from, to }); } }}
          className="border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white">
          <option value="">Custom</option>
          {options.map(([f, t, label]) => <option key={f} value={`${f}|${t}`}>{label}</option>)}
        </select>
        <input type="date" aria-label="Period from" value={period.from} onChange={e => setPeriod({ ...period, from: e.target.value })}
          className="border border-gray-300 rounded-md px-2 py-1.5 text-sm" />
        <input type="date" aria-label="Period to" value={period.to} onChange={e => setPeriod({ ...period, to: e.target.value })}
          className="border border-gray-300 rounded-md px-2 py-1.5 text-sm" />
        <button type="button" className={BTN_SECONDARY} onClick={exportCsv}>Export CSV</button>
      </PageHeader>
      {(error || isError) && <p role="alert" className="mb-3 text-sm text-red-600">{error || "Could not work out the VAT return for these dates"}</p>}
      {isLoading && <p className="text-sm text-gray-400">Working it out…</p>}

      {b && data && (
        <div className="grid lg:grid-cols-5 gap-4">
          <section className="lg:col-span-2 bg-white border border-gray-200 rounded-lg overflow-hidden self-start" data-testid="vat-boxes">
            <table className="w-full text-sm">
              <tbody>
                {BOXES.map(([k, label, strong]) => (
                  <tr key={k} className={`border-b border-gray-100 ${strong ? "bg-gray-50 font-semibold" : ""}`} data-testid={`vat-${k}`}>
                    <td className="px-3 py-2 text-xs text-gray-500 w-14">Box {k.slice(3)}</td>
                    <td className="py-2 pr-2">{label}</td>
                    <td className="px-3 py-2 text-right tabular-nums whitespace-nowrap">
                      {["box6", "box7", "box8", "box9"].includes(k) ? `£${Number(b[k]).toLocaleString("en-GB")}` : formatGBP(b[k])}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className={`px-3 py-2 text-sm font-semibold ${data.refund ? "text-emerald-700" : "text-gray-800"}`} data-testid="vat-result">
              {data.refund ? `HMRC owes you ${formatGBP(b.box5)}` : `To pay HMRC: ${formatGBP(b.box5)}`}
            </p>
          </section>

          <div className="lg:col-span-3 space-y-4">
            <section className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
              <h3 className="px-3 py-2 text-sm font-semibold text-gray-800 border-b border-gray-200">By tax rate</h3>
              <table className="w-full text-sm" data-testid="vat-rates">
                <thead><tr className="text-left text-xs text-gray-500 border-b border-gray-200">
                  <th className="px-3 py-1.5 font-medium">Side</th><th className="px-3 font-medium">Tax rule</th><th className="px-3 font-medium">Type</th>
                  <th className="px-3 font-medium text-right">Lines</th><th className="px-3 font-medium text-right">Net</th><th className="px-3 font-medium text-right">VAT</th></tr></thead>
                <tbody className="tabular-nums">
                  {data.rates.map(r => (
                    <tr key={`${r.side}-${r.taxName}-${r.vatType}`} className="border-b border-gray-100">
                      <td className="px-3 py-1.5">{r.side === "SALES" ? "Sales" : "Purchases"}</td>
                      <td className="px-3">{r.taxName}</td>
                      <td className="px-3 text-gray-600">{TYPE[r.vatType]}</td>
                      <td className="px-3 text-right">{r.lines}</td>
                      <td className="px-3 text-right">{formatGBP(r.net)}</td>
                      <td className="px-3 text-right">{formatGBP(r.vat)}</td>
                    </tr>
                  ))}
                  {data.rates.length === 0 && <tr><td colSpan={6} className="px-3 py-4 text-center text-gray-500">No invoices or bills in this period</td></tr>}
                </tbody>
              </table>
            </section>
            <section className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
              <h3 className="px-3 py-2 text-sm font-semibold text-gray-800 border-b border-gray-200">Documents ({data.documents.length})</h3>
              <table className="w-full text-sm" data-testid="vat-documents">
                <thead><tr className="text-left text-xs text-gray-500 border-b border-gray-200">
                  <th className="px-3 py-1.5 font-medium">Date</th><th className="px-3 font-medium">Document</th><th className="px-3 font-medium">Party</th>
                  <th className="px-3 font-medium text-right">Net</th><th className="px-3 font-medium text-right">VAT</th></tr></thead>
                <tbody className="tabular-nums">
                  {data.documents.map(d => (
                    <tr key={d.sourceKey} className="border-b border-gray-100">
                      <td className="px-3 py-1.5">{new Date(d.date + "T00:00:00").toLocaleDateString("en-GB")}</td>
                      <td className="px-3">{d.invoiceId ? <Link href={`/invoices/${d.invoiceId}`} className="text-brand-700 hover:underline">
                        {d.kind === "CREDIT_NOTE" ? "Credit note" : d.side === "SALES" ? "Invoice" : "Bill"} {d.number}</Link>
                        : <Link href="/accounting/bank" className="text-brand-700 hover:underline">{d.number}</Link>}</td>
                      <td className="px-3 text-gray-600">{d.party}</td>
                      <td className="px-3 text-right">{formatGBP(d.net)}</td>
                      <td className="px-3 text-right">{formatGBP(d.vat)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
