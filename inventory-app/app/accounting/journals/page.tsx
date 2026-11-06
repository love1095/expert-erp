"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getJournalReport, getLedgerAccounts } from "@/lib/api";
import { LedgerAccount, LedgerJournal } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { INPUT, SourceLink, money, today } from "../ledger-parts";

/** Phase K1: Xero's Journal Report — every journal posted (newest first), reversals marked, with its lines. */
export default function JournalsPage() {
  const [from, setFrom] = useState(`${today().slice(0, 7)}-01`);
  const [to, setTo] = useState(today());
  const { data: journals = [], isLoading } = useQuery<LedgerJournal[]>({ queryKey: ["journals", from, to], queryFn: () => getJournalReport({ from, to }) });
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", true], queryFn: () => getLedgerAccounts(true) });
  const name = (code: string) => accounts.find(a => a.code.toLowerCase() === code.toLowerCase())?.name ?? "";

  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Journal report" />
      <div className="flex gap-2 text-sm">
        <input aria-label="From" type="date" className={INPUT} value={from} onChange={e => setFrom(e.target.value)} />
        <input aria-label="To" type="date" className={INPUT} value={to} onChange={e => setTo(e.target.value)} />
      </div>
      {isLoading ? <p className="text-sm text-gray-500">Loading…</p> : journals.length === 0 ? <p className="text-sm text-gray-400">No journals in this period</p> : (
        <div className="space-y-3" data-testid="journal-report">
          {journals.length >= 500 && <p className="text-xs text-gray-400">The newest 500 journals are shown: narrow the period</p>}
          {journals.map(j => (
            <section key={j.number} className="bg-white border border-gray-200 rounded-xl">
              <header className="px-4 py-2 bg-gray-50 rounded-t-xl text-sm flex flex-wrap gap-x-4">
                <span className="font-semibold">#{j.number}</span>
                <span>{new Date(j.date).toLocaleDateString("en-GB")}</span>
                <SourceLink sourceKey={j.sourceKey} sourceType={j.sourceType} reference={j.reference} />
                <span className="text-gray-600 truncate max-w-xl">{j.narration}</span>
                {j.reversal && <span className="text-xs px-2 rounded bg-gray-200 text-gray-600">Reversal</span>}
              </header>
              <table className="w-full text-sm">
                <tbody className="divide-y divide-gray-100">
                  {j.lines.map((l, i) => (
                    <tr key={i}>
                      <td className="px-4 py-1 w-72">{l.account} {name(l.account)}</td>
                      <td className="text-gray-500">{l.description ?? ""}{l.taxName ? ` · ${l.taxName}` : ""}</td>
                      <td className="text-right tabular-nums w-32">{money(l.amount > 0 ? l.amount : null)}</td>
                      <td className="text-right tabular-nums w-32 px-4">{money(l.amount < 0 ? -l.amount : null)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
