"use client";

import { useState } from "react";
import Link from "next/link";
import { formatGBP } from "@/lib/currency";
import { ledgerExport } from "@/lib/api";
import { download } from "@/app/reports/report-parts";
import { todayIso } from "@/lib/dates";

export const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white";
export const CLASS_LABEL: Record<string, string> = { ASSET: "Assets", LIABILITY: "Liabilities", EQUITY: "Equity", REVENUE: "Revenue", EXPENSE: "Expenses" };

export const money = (v: number | null | undefined) => (v == null || Number(v) === 0 ? "" : formatGBP(Number(v)));
export const today = todayIso;
export const errorOf = (e: unknown, fallback = "Something went wrong") =>
  (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;

/** Where a journal comes from, linked to the document when there is one (Xero's "Source"). */
export function SourceLink({ sourceKey, sourceType, reference }: { sourceKey: string; sourceType: string; reference: string | null }) {
  const label = ({ INVOICE: "Invoice", CREDIT_NOTE: "Credit note", BILL: "Bill", SUPPLIER_CREDIT: "Supplier credit", PAYMENT: "Payment",
    CREDIT: "Credit / deposit", BANK: "Bank transaction", STOCK: "Stock", MANUAL: "Manual journal", OPENING: "Conversion balances" } as Record<string, string>)[sourceType] ?? sourceType;
  const [kind, id] = sourceKey.split(":");
  const href = kind === "DOC" ? `/invoices/${id}` : kind === "MJ" ? "/accounting/manual-journals" : kind === "OPENING" ? "/accounting/settings"
    : kind === "BT" ? "/accounting/bank" : null;
  const text = reference ? `${label} ${reference}` : label;
  return href ? <Link href={href} className="text-brand-600 hover:underline">{text}</Link> : <span>{text}</span>;
}

/** A Xero-style statement: sections of accounts (linked to their transactions) with a total, and total lines. */
export function StatementSectionRows({ section, from, to, totalLabel }: {
  section: { title: string; rows: { code: string | null; name: string; values: number[] }[]; totals: number[] };
  from: string; to: string; totalLabel?: string;
}) {
  if (section.rows.length === 0) return null;
  return (
    <tbody>
      <tr><td colSpan={section.totals.length + 1} className="px-4 pt-4 pb-1 font-semibold text-gray-800">{section.title}</td></tr>
      {section.rows.map(r => (
        <tr key={(r.code ?? "") + r.name} className="border-t border-gray-100">
          <td className="px-4 py-1">
            {r.code ? <Link className="text-brand-700 hover:underline" href={`/accounting/account-transactions?account=${encodeURIComponent(r.code)}&from=${from}&to=${to}`}>{r.name}</Link> : r.name}
          </td>
          {r.values.map((v, i) => <td key={i} className="text-right tabular-nums px-4">{formatGBP(v)}</td>)}
        </tr>
      ))}
      <tr className="border-t border-gray-300 font-medium">
        <td className="px-4 py-1">{totalLabel ?? `Total ${section.title}`}</td>
        {section.totals.map((v, i) => <td key={i} className="text-right tabular-nums px-4">{formatGBP(v)}</td>)}
      </tr>
    </tbody>
  );
}

export function StatementTotalRow({ total, strong }: { total: { title: string; values: number[] }; strong?: boolean }) {
  return (
    <tbody>
      <tr className={`border-t-2 border-gray-300 ${strong ? "font-bold" : "font-semibold"}`} data-testid={`total-${total.title.replace(/\s+/g, "-").toLowerCase()}`}>
        <td className="px-4 py-2">{total.title}</td>
        {total.values.map((v, i) => <td key={i} className="text-right tabular-nums px-4">{formatGBP(v)}</td>)}
      </tr>
    </tbody>
  );
}

export function CompareControls({ compare, step, onCompare, onStep }: { compare: number; step: string; onCompare: (n: number) => void; onStep: (s: string) => void }) {
  return (
    <>
      <label className="flex items-center gap-1">Compare with
        <select aria-label="Compare with" className={INPUT} value={compare} onChange={e => onCompare(Number(e.target.value))}>
          {[0, 1, 2, 3, 4, 5, 11].map(n => <option key={n} value={n}>{n === 0 ? "None" : n}</option>)}
        </select>
      </label>
      <select aria-label="Period" className={INPUT} value={step} disabled={compare === 0} onChange={e => onStep(e.target.value)}>
        <option value="MONTH">Months</option><option value="QUARTER">Quarters</option><option value="YEAR">Years</option>
      </select>
    </>
  );
}

/** Phase K5: a report's CSV export (opens in Excel). */
export function ExportButton({ name, params, filename, label = "Export CSV" }: { name: string; params: object; filename: string; label?: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <span className="inline-flex items-center gap-2">
      <button type="button" disabled={busy} className="border border-gray-300 bg-white text-gray-700 px-3 py-1.5 rounded-md text-sm hover:bg-gray-50 disabled:opacity-50"
        onClick={async () => {
          setBusy(true); setError("");
          try { await download(ledgerExport(name, params), filename); } catch (e) { setError(errorOf(e, "Export failed")); } finally { setBusy(false); }
        }}>{busy ? "Exporting…" : label}</button>
      {error && <span role="alert" className="text-xs text-red-700">{error}</span>}
    </span>
  );
}
