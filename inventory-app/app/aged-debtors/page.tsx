"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getAgedDebtors, getAgedDebtorsCsv } from "@/lib/api";
import { AgedDebtor, AgedDebtors } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";
import { isoDay } from "../customers/statement-card";

const COLS: [keyof AgedDebtor, string][] = [
  ["current", "Current"], ["days1to30", "1–30 days"], ["days31to60", "31–60 days"], ["days61to90", "61–90 days"],
  ["over90", "Over 90 days"], ["credit", "Credit"], ["total", "Total"],
];

/**
 * Phase H1 (spec §6): what each customer owes on a day, aged by days past the due date, with their unused credit,
 * most owed first. Adds up to the sales ledger: each row is the customer's statement balance on that day.
 */
export default function AgedDebtorsPage() {
  const [asAt, setAsAt] = useState(() => isoDay(new Date()));
  const [error, setError] = useState("");
  const { data, isLoading } = useQuery<AgedDebtors>({ queryKey: ["aged-debtors", asAt], queryFn: () => getAgedDebtors(asAt) });
  const rows = data?.customers ?? [];

  const exportCsv = async () => {
    try {
      const blob = await getAgedDebtorsCsv(asAt);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `AgedDebtors_${asAt}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch { setError("Could not export"); }
  };

  const cell = (r: AgedDebtor, k: keyof AgedDebtor) => {
    const v = Number(r[k] ?? 0);
    const late = ["days1to30", "days31to60", "days61to90", "over90"].includes(k as string) && v > 0;
    return <td key={k as string} className={`px-3 py-2 text-right tabular-nums ${late ? "text-red-700" : ""} ${k === "total" ? "font-semibold" : ""}`}>
      {v === 0 ? <span className="text-gray-300">—</span> : formatGBP(v)}</td>;
  };

  return (
    <div>
      <PageHeader title="Aged debtors" subtitle={data ? `${rows.length} customers owe ${formatGBP(data.totals.total)} on ${new Date(data.asAt + "T00:00:00").toLocaleDateString("en-GB")}` : undefined}>
        <label className="text-sm text-gray-600 flex items-center gap-2">As at
          <input type="date" value={asAt} max={isoDay(new Date())} onChange={e => setAsAt(e.target.value)} aria-label="As at"
            className="border border-gray-300 rounded-md px-2 py-1.5 text-sm" /></label>
        <button type="button" className={BTN_SECONDARY} onClick={exportCsv}>Export CSV</button>
      </PageHeader>
      {error && <p role="alert" className="mb-3 text-sm text-red-600">{error}</p>}
      <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
        <table className="w-full text-sm" data-testid="aged-debtors">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-200 bg-gray-50">
              <th className="px-3 py-2 font-medium">Customer</th>
              {COLS.map(([k, label]) => <th key={k as string} className="px-3 py-2 font-medium text-right">{label}</th>)}
              <th className="px-3 py-2 font-medium text-right">Credit limit</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.customerId} className="border-b border-gray-100" data-testid={`aged-row-${r.customerId}`}>
                <td className="px-3 py-2">
                  <Link href={`/customers/${r.customerId}`} className="text-brand-700 hover:underline">{r.customerName}</Link>
                  {r.onHold && <span className="ml-2 text-xs bg-red-100 text-red-700 rounded px-1.5 py-0.5">On hold</span>}
                </td>
                {COLS.map(([k]) => cell(r, k))}
                <td className="px-3 py-2 text-right tabular-nums text-gray-500">{r.creditLimit ? formatGBP(r.creditLimit) : ""}</td>
              </tr>
            ))}
            {!isLoading && rows.length === 0 && <tr><td colSpan={9} className="px-3 py-6 text-center text-gray-500">No customer owes anything</td></tr>}
            {data && rows.length > 0 && (
              <tr className="bg-gray-50 font-semibold" data-testid="aged-totals">
                <td className="px-3 py-2">Total</td>
                {COLS.map(([k]) => cell(data.totals, k))}
                <td />
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
