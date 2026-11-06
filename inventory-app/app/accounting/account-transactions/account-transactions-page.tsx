"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getAccountTransactions, getLedgerAccounts } from "@/lib/api";
import { AccountTransactions, LedgerAccount } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { ExportButton, INPUT, SourceLink, errorOf, money, today } from "../ledger-parts";

/** Phase K1: Xero's Account Transactions — one account's lines in a period with the balance before, running and after. */
export default function AccountTransactionsPage({ initialAccount, initialFrom, initialTo }: { initialAccount: string; initialFrom: string; initialTo: string }) {
  const [account, setAccount] = useState(initialAccount);
  const [from, setFrom] = useState(initialFrom || `${today().slice(0, 4)}-01-01`);
  const [to, setTo] = useState(initialTo || today());
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", true], queryFn: () => getLedgerAccounts(true) });
  const { data, isLoading, error } = useQuery<AccountTransactions>({
    queryKey: ["account-transactions", account, from, to], queryFn: () => getAccountTransactions(account, from, to), enabled: !!account && !!from && !!to,
  });

  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Account transactions" />
      <div className="flex flex-wrap gap-2 items-center text-sm">
        <select aria-label="Account" className={INPUT} value={account} onChange={e => setAccount(e.target.value)}>
          <option value="">Choose an account…</option>
          {accounts.map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}
        </select>
        <input aria-label="From" type="date" className={INPUT} value={from} onChange={e => setFrom(e.target.value)} />
        <input aria-label="To" type="date" className={INPUT} value={to} onChange={e => setTo(e.target.value)} />
        {account && <ExportButton name="account-transactions.csv" params={{ account, from, to }} filename={`Account ${account} ${from} to ${to}.csv`} />}
      </div>
      {error && <p role="alert" className="text-sm text-red-700">{errorOf(error)}</p>}
      {account && (isLoading || !data ? <p className="text-sm text-gray-500">Loading…</p> : (
        <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="account-transactions">
          <thead className="text-xs text-gray-500 text-left bg-gray-50">
            <tr><th className="px-4 py-2">Date</th><th>Source</th><th>Description</th><th>Contact</th><th className="text-right">Debit</th><th className="text-right">Credit</th><th className="text-right px-4">Balance</th></tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            <tr className="text-gray-600"><td className="px-4 py-1.5" colSpan={6}>Opening balance</td><td className="text-right tabular-nums px-4" data-testid="opening">{formatGBP(data.opening)}</td></tr>
            {data.rows.map((r, i) => (
              <tr key={i}>
                <td className="px-4 py-1.5 whitespace-nowrap">{new Date(r.date).toLocaleDateString("en-GB")}</td>
                <td><SourceLink sourceKey={r.sourceKey} sourceType={r.sourceType} reference={r.reference} /></td>
                <td className="text-gray-700">{r.description ?? r.narration}</td>
                <td className="text-gray-500">{r.contact ?? ""}</td>
                <td className="text-right tabular-nums">{money(r.debit)}</td>
                <td className="text-right tabular-nums">{money(r.credit)}</td>
                <td className="text-right tabular-nums px-4">{formatGBP(r.balance)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot className="font-semibold border-t-2 border-gray-300">
            <tr><td className="px-4 py-2" colSpan={4}>Total {data.code} {data.name}</td>
              <td className="text-right tabular-nums">{formatGBP(data.debits)}</td><td className="text-right tabular-nums">{formatGBP(data.credits)}</td>
              <td className="text-right tabular-nums px-4" data-testid="closing">{formatGBP(data.closing)}</td></tr>
          </tfoot>
        </table>
      ))}
    </div>
  );
}
