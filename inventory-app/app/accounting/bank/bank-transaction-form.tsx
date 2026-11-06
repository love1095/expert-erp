"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createBankTransaction, getLedgerAccounts, getTaxRates } from "@/lib/api";
import { BankAccountCard, LedgerAccount, TaxRate } from "@/types";
import { formatGBP } from "@/lib/currency";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { INPUT, errorOf, today } from "../ledger-parts";

interface Line { accountCode: string; description: string; amount: string; taxRule: string }
const blank = (): Line => ({ accountCode: "", description: "", amount: "", taxRule: "" });

/** Xero's Spend money / Receive money (amounts including VAT) and Transfer money. */
export function BankTransactionForm({ kind, accounts, onDone }: { kind: "SPEND" | "RECEIVE" | "TRANSFER"; accounts: BankAccountCard[]; onDone: () => void }) {
  const qc = useQueryClient();
  const { data: chart = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", kind], queryFn: () => getTaxRates(kind === "RECEIVE" ? "SALE" : "PURCHASE"), enabled: kind !== "TRANSFER" });
  const [bank, setBank] = useState(accounts[0]?.code ?? "");
  const [to, setTo] = useState("");
  const [date, setDate] = useState(today());
  const [contact, setContact] = useState("");
  const [reference, setReference] = useState("");
  const [lines, setLines] = useState<Line[]>([blank()]);
  const [error, setError] = useState("");
  const save = useMutation({
    mutationFn: () => createBankTransaction({ kind, date, accountCode: bank, toAccountCode: kind === "TRANSFER" ? to : null, contact, reference, taxInclusive: true,
      lines: lines.filter(l => l.amount).map(l => ({ accountCode: kind === "TRANSFER" ? bank : l.accountCode, description: l.description, amount: Number(l.amount), taxRule: l.taxRule || null })) }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["bank-accounts"] }); qc.invalidateQueries({ queryKey: ["bank-transactions"] }); onDone(); },
    onError: e => setError(errorOf(e)),
  });
  const set = (i: number, p: Partial<Line>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...p } : l)));
  const total = lines.reduce((t, l) => t + (Number(l.amount) || 0), 0);
  const others = chart.filter(a => a.type !== "BANK");
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="bank-transaction-form">
      <h2 className="font-semibold">{kind === "SPEND" ? "Spend money" : kind === "RECEIVE" ? "Receive money" : "Transfer money"}</h2>
      {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}
      <div className="flex flex-wrap gap-3 text-sm">
        <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">{kind === "TRANSFER" ? "From" : "Bank account"}</span>
          <select aria-label={kind === "TRANSFER" ? "From" : "Bank account"} className={INPUT} value={bank} onChange={e => setBank(e.target.value)}>
            {accounts.map(a => <option key={a.code} value={a.code}>{a.code} - {a.name}</option>)}
          </select></label>
        {kind === "TRANSFER" && <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">To</span>
          <select aria-label="To" className={INPUT} value={to} onChange={e => setTo(e.target.value)}>
            <option value="">Choose…</option>
            {accounts.filter(a => a.code !== bank).map(a => <option key={a.code} value={a.code}>{a.code} - {a.name}</option>)}
          </select></label>}
        <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Date</span>
          <input aria-label="Date" type="date" className={INPUT} value={date} onChange={e => setDate(e.target.value)} /></label>
        {kind !== "TRANSFER" && <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">{kind === "SPEND" ? "To" : "From"}</span>
          <input aria-label="Contact" className={INPUT} value={contact} onChange={e => setContact(e.target.value)} /></label>}
        <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Reference</span>
          <input aria-label="Reference" className={INPUT} value={reference} onChange={e => setReference(e.target.value)} /></label>
      </div>
      {kind === "TRANSFER" ? (
        <label className="flex flex-col gap-1 text-sm w-40"><span className="text-xs text-gray-600">Amount</span>
          <input aria-label="Amount" inputMode="decimal" className={`${INPUT} text-right`} value={lines[0].amount} onChange={e => set(0, { amount: e.target.value })} /></label>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-500 text-left"><tr><th>Description</th><th className="w-64">Account</th><th className="w-56">VAT rate</th><th className="w-32 text-right">Amount (inc. VAT)</th></tr></thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={i}>
                <td className="pr-2 py-1"><input aria-label={`Line ${i + 1} description`} className={`${INPUT} w-full`} value={l.description} onChange={e => set(i, { description: e.target.value })} /></td>
                <td className="pr-2"><select aria-label={`Line ${i + 1} account`} className={`${INPUT} w-full`} value={l.accountCode} onChange={e => set(i, { accountCode: e.target.value })}>
                  <option value="">Choose…</option>{others.map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}</select></td>
                <td className="pr-2"><select aria-label={`Line ${i + 1} VAT rate`} className={`${INPUT} w-full`} value={l.taxRule} onChange={e => set(i, { taxRule: e.target.value })}>
                  <option value="">No VAT</option>{rates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}</select></td>
                <td><input aria-label={`Line ${i + 1} amount`} inputMode="decimal" className={`${INPUT} w-full text-right`} value={l.amount} onChange={e => set(i, { amount: e.target.value })} /></td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td><button className="text-brand-700 text-sm hover:underline" onClick={() => setLines([...lines, blank()])}>+ Add a line</button></td>
            <td colSpan={2} className="text-right pr-2 font-medium">Total</td><td className="text-right tabular-nums font-medium">{formatGBP(total)}</td></tr></tfoot>
        </table>
      )}
      <div className="flex gap-2">
        <button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save</button>
        <button className={BTN_SECONDARY} onClick={onDone}>Cancel</button>
      </div>
    </section>
  );
}
