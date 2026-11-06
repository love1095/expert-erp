"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getTrialBalance } from "@/lib/api";
import { TrialBalance } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { CLASS_LABEL, INPUT, money, errorOf, today } from "../ledger-parts";

/** Phase K1: Xero's Trial Balance — each account's balance on a day, debits and credits, grouped by class. */
export default function TrialBalancePage() {
  const [date, setDate] = useState(today());
  const { data, isLoading, error } = useQuery<TrialBalance>({ queryKey: ["trial-balance", date], queryFn: () => getTrialBalance(date), enabled: !!date });
  const yearStart = `${date.slice(0, 4)}-01-01`;

  return (
    <div className="p-6 max-w-5xl space-y-4">
      <PageHeader title="Trial balance" />
      <label className="flex items-center gap-2 text-sm">As at <input aria-label="As at" type="date" className={INPUT} value={date} onChange={e => setDate(e.target.value)} /></label>
      {error && <p role="alert" className="text-sm text-red-700">{errorOf(error)}</p>}
      {isLoading || !data ? <p className="text-sm text-gray-500">Loading…</p> : (
        <>
          {data.warnings.length > 0 && <ul className="text-sm text-amber-800 bg-amber-50 rounded px-4 py-2 list-disc ml-0 pl-8">{data.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
          <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="trial-balance">
            <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">Account</th><th>Type</th><th className="text-right">Debit</th><th className="text-right px-4">Credit</th></tr></thead>
            {Object.keys(CLASS_LABEL).map(c => {
              const rows = data.rows.filter(r => r.accountClass === c);
              if (rows.length === 0) return null;
              return (
                <tbody key={c} className="divide-y divide-gray-100">
                  <tr className="bg-gray-50/60"><td colSpan={4} className="px-4 py-1.5 font-semibold text-gray-700">{CLASS_LABEL[c]}</td></tr>
                  {rows.map(r => (
                    <tr key={r.code} data-testid={`tb-${r.code}`}>
                      <td className="px-4 py-1.5">
                        <Link className="text-brand-700 hover:underline" href={`/accounting/account-transactions?account=${encodeURIComponent(r.code)}&from=${yearStart}&to=${date}`}>{r.name} ({r.code})</Link>
                      </td>
                      <td className="text-gray-500">{r.type}</td>
                      <td className="text-right tabular-nums">{money(r.debit)}</td>
                      <td className="text-right tabular-nums px-4">{money(r.credit)}</td>
                    </tr>
                  ))}
                </tbody>
              );
            })}
            <tfoot className="font-semibold border-t-2 border-gray-300">
              <tr><td className="px-4 py-2">Total</td><td />
                <td className="text-right tabular-nums" data-testid="tb-debits">{formatGBP(data.debits)}</td>
                <td className="text-right tabular-nums px-4" data-testid="tb-credits">{formatGBP(data.credits)}</td></tr>
            </tfoot>
          </table>
        </>
      )}
    </div>
  );
}
