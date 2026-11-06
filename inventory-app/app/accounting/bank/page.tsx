"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getBankAccounts, importBankStatement } from "@/lib/api";
import { BankAccountCard, BankImportResult } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { errorOf } from "../ledger-parts";
import { BankTransactionForm } from "./bank-transaction-form";

/** Phase K3: Xero's bank accounts — balance in the ERP, statement balance, items to reconcile, statement import, spend / receive money. */
export default function BankAccountsPage() {
  const { data: accounts = [], isLoading } = useQuery<BankAccountCard[]>({ queryKey: ["bank-accounts"], queryFn: getBankAccounts });
  const [form, setForm] = useState<"SPEND" | "RECEIVE" | "TRANSFER" | null>(null);
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Bank accounts" />
      <div className="flex gap-2">
        <button className={BTN_SECONDARY} onClick={() => setForm("SPEND")}>Spend money</button>
        <button className={BTN_SECONDARY} onClick={() => setForm("RECEIVE")}>Receive money</button>
        <button className={BTN_SECONDARY} onClick={() => setForm("TRANSFER")}>Transfer money</button>
      </div>
      {form && <BankTransactionForm kind={form} accounts={accounts} onDone={() => setForm(null)} />}
      {isLoading ? <p className="text-sm text-gray-500">Loading…</p> : accounts.length === 0 ? (
        <p className="text-sm text-gray-500">No bank accounts. Add one in the chart of accounts (type Bank) or as a payment account.</p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4" data-testid="bank-accounts">
          {accounts.map(a => <BankCard key={a.code} account={a} />)}
        </div>
      )}
    </div>
  );
}

function BankCard({ account: a }: { account: BankAccountCard }) {
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<BankImportResult | null>(null);
  const [error, setError] = useState("");
  const run = useMutation({
    mutationFn: (apply: boolean) => importBankStatement(a.code, file!, apply),
    onSuccess: (r: BankImportResult) => { setResult(r); setError(""); if (r.applied) qc.invalidateQueries({ queryKey: ["bank-accounts"] }); },
    onError: e => { setResult(null); setError(errorOf(e, "Import failed")); },
  });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid={`bank-${a.code}`}>
      <div className="flex justify-between items-start">
        <div><h2 className="font-semibold">{a.name}</h2><p className="text-xs text-gray-400">{a.code}</p></div>
        {a.toReconcile > 0
          ? <Link href={`/accounting/bank/${encodeURIComponent(a.code)}`} className={BTN_PRIMARY}>Reconcile {a.toReconcile} {a.toReconcile === 1 ? "item" : "items"}</Link>
          : <Link href={`/accounting/bank/${encodeURIComponent(a.code)}`} className="text-sm text-brand-700 hover:underline">Open</Link>}
      </div>
      <dl className="grid grid-cols-2 gap-2 text-sm">
        <div><dt className="text-xs text-gray-500">Statement balance</dt>
          <dd className="font-medium tabular-nums">{a.statementBalance != null ? formatGBP(a.statementBalance) : "—"}
            {a.statementDate && <span className="text-xs text-gray-400"> on {new Date(a.statementDate).toLocaleDateString("en-GB")}</span>}</dd></div>
        <div><dt className="text-xs text-gray-500">Balance in the ERP</dt><dd className="font-medium tabular-nums" data-testid="ledger-balance">{formatGBP(a.ledgerBalance)}</dd></div>
      </dl>
      <div className="flex items-center gap-2 text-sm border-t border-gray-100 pt-3">
        <input aria-label={`Statement file for ${a.code}`} type="file" accept=".csv,.ofx,.qfx,text/csv" className="text-xs w-56"
          onChange={e => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
        <button className={BTN_SECONDARY} disabled={!file || run.isPending} onClick={() => run.mutate(false)}>Import a statement</button>
      </div>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      {result && (
        <div className="text-sm bg-gray-50 rounded px-3 py-2 space-y-1" data-testid="statement-import">
          <p>{result.applied ? "✓ Imported" : "Preview"} ({result.format}): <b>{result.lines} lines</b>{result.from && <> from {new Date(result.from).toLocaleDateString("en-GB")} to {new Date(result.to!).toLocaleDateString("en-GB")}</>},
            money in {formatGBP(result.moneyIn)}, out {formatGBP(result.moneyOut)}</p>
          {result.warnings.map((w, i) => <p key={i} className="text-xs text-amber-800">{w}</p>)}
          {!result.applied && result.lines > 0 && <button className={BTN_PRIMARY} onClick={() => run.mutate(true)}>Import {result.lines} lines</button>}
        </div>
      )}
    </section>
  );
}
