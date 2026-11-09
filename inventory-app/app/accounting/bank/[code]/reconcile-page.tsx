"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createForBankLine, deleteBankLine, getBankAccounts, getBankLines, getBankTransactions, getBankUnmatched, getLedgerAccounts, getTaxRates,
  matchBankLine, transferBankLine, unreconcileBankLine, voidBankTransaction } from "@/lib/api";
import { BankAccountCard, BankItem, BankStatementLine, BankTransaction, LedgerAccount, TaxRate } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { INPUT, SourceLink, errorOf, today } from "../../ledger-parts";
import { useConfirm } from "@/components/ConfirmDialog";

type Tab = "reconcile" | "statement" | "transactions";
const day = (d: string | null) => (d ? new Date(d).toLocaleDateString("en-GB") : "");

/** Phase K3: Xero's Reconcile — each statement line with what it matches (suggested), or create / transfer; reconciled lines; bank transactions. */
export default function ReconcilePage({ code }: { code: string }) {
  const [tab, setTab] = useState<Tab>("reconcile");
  const { data: accounts = [] } = useQuery<BankAccountCard[]>({ queryKey: ["bank-accounts"], queryFn: getBankAccounts });
  const account = accounts.find(a => a.code === code);
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title={account ? `${account.name} (${code})` : `Bank account ${code}`} />
      {account && <p className="text-sm text-gray-600">Statement balance {account.statementBalance != null ? formatGBP(account.statementBalance) : "—"} ·
        Balance in the ERP {formatGBP(account.ledgerBalance)} · <Link href={`/accounting/account-transactions?account=${encodeURIComponent(code)}&to=${today()}`} className="text-brand-700 hover:underline">Account transactions</Link></p>}
      <div className="flex gap-5 border-b border-gray-200 text-sm" role="tablist">
        {([["reconcile", `Reconcile${account ? ` (${account.toReconcile})` : ""}`], ["statement", "Reconciled statement lines"], ["transactions", "Bank transactions"]] as [Tab, string][]).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`pb-2 -mb-px ${tab === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500"}`}>{l}</button>
        ))}
      </div>
      {tab === "reconcile" && <Reconcile code={code} accounts={accounts} />}
      {tab === "statement" && <Reconciled code={code} />}
      {tab === "transactions" && <Transactions code={code} />}
    </div>
  );
}

function Reconcile({ code, accounts }: { code: string; accounts: BankAccountCard[] }) {
  const { data: lines = [], isLoading } = useQuery<BankStatementLine[]>({ queryKey: ["bank-lines", code, "UNRECONCILED"], queryFn: () => getBankLines(code, "UNRECONCILED") });
  if (isLoading) return <p className="text-sm text-gray-500">Loading…</p>;
  if (lines.length === 0) return <p className="text-sm text-gray-500" data-testid="all-reconciled">Nothing to reconcile. Import a statement on the Bank accounts page.</p>;
  return <div className="space-y-3" data-testid="reconcile">{lines.map(l => <LineCard key={l.id} code={code} line={l} accounts={accounts} />)}</div>;
}

function LineCard({ code, line, accounts }: { code: string; line: BankStatementLine; accounts: BankAccountCard[] }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [mode, setMode] = useState<"match" | "find" | "create" | "transfer">(line.suggestions.length > 0 ? "match" : "create");
  const [error, setError] = useState("");
  const done = () => { setError(""); ["bank-lines", "bank-accounts", "bank-unmatched", "bank-transactions"].forEach(k => qc.invalidateQueries({ queryKey: [k] })); };
  const fail = (e: unknown) => setError(errorOf(e));
  const match = useMutation({ mutationFn: (keys: string[]) => matchBankLine(line.id, keys), onSuccess: done, onError: fail });
  const remove = useMutation({ mutationFn: () => deleteBankLine(line.id), onSuccess: done, onError: fail });
  const out = line.amount < 0;
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-0 bg-white border border-gray-200 rounded-xl overflow-hidden" data-testid={`line-${line.id}`}>
      <div className="p-4 bg-gray-50 border-r border-gray-200 text-sm space-y-1">
        <div className="flex justify-between"><span className="text-gray-500">{day(line.date)}</span>
          <span className={`font-semibold tabular-nums ${out ? "text-gray-900" : "text-green-700"}`}>{out ? "Spent " : "Received "}{formatGBP(Math.abs(line.amount))}</span></div>
        <div className="font-medium">{line.payee}</div>
        {line.reference && <div className="text-gray-500">Ref {line.reference}</div>}
        {line.description && line.description !== line.payee && <div className="text-gray-500 text-xs">{line.description}</div>}
        <button className="text-xs text-gray-400 hover:text-red-600" onClick={async () => { if (await ask({ title: "Delete this statement line?", action: "Delete", danger: true })) remove.mutate(); }}>Delete this line</button>
      </div>
      <div className="p-4 text-sm space-y-2">
        <div className="flex gap-3 text-xs" role="tablist">
          {([["match", "Match"], ["find", "Find & match"], ["create", "Create"], ["transfer", "Transfer"]] as const).map(([k, l]) => (
            <button key={k} role="tab" aria-selected={mode === k} onClick={() => setMode(k)} className={mode === k ? "font-semibold text-brand-700 underline" : "text-gray-500"}>{l}</button>
          ))}
        </div>
        {error && <p role="alert" className="text-red-700 text-xs">{error}</p>}
        {mode === "match" && (line.suggestions.length === 0 ? <p className="text-gray-400">No suggestion with this amount: find & match, or create.</p> : (
          <ul className="space-y-1">{line.suggestions.map(s => (
            <li key={s.item.sourceKey} className="flex justify-between items-center gap-2 border border-gray-100 rounded px-2 py-1">
              <span><SourceLink sourceKey={s.item.sourceKey} sourceType={s.item.sourceType} reference={s.item.reference} /> · {day(s.item.date)}
                <span className="block text-xs text-gray-500 truncate max-w-xs">{s.item.contact ?? s.item.narration}</span></span>
              <span className="tabular-nums">{formatGBP(Math.abs(s.item.amount))}</span>
              <button className={BTN_PRIMARY} onClick={() => match.mutate([s.item.sourceKey])}>OK</button>
            </li>))}</ul>))}
        {mode === "find" && <FindAndMatch code={code} amount={line.amount} onMatch={keys => match.mutate(keys)} />}
        {mode === "create" && <CreateForLine line={line} onDone={done} onError={fail} />}
        {mode === "transfer" && <TransferForLine line={line} accounts={accounts.filter(a => a.code !== code)} onDone={done} onError={fail} />}
      </div>
    </div>
  );
}

function FindAndMatch({ code, amount, onMatch }: { code: string; amount: number; onMatch: (keys: string[]) => void }) {
  const { data: items = [] } = useQuery<BankItem[]>({ queryKey: ["bank-unmatched", code], queryFn: () => getBankUnmatched(code) });
  const [picked, setPicked] = useState<string[]>([]);
  const [q, setQ] = useState("");
  const total = items.filter(i => picked.includes(i.sourceKey)).reduce((t, i) => t + Number(i.amount), 0);
  const shown = items.filter(i => !q || `${i.reference ?? ""} ${i.contact ?? ""} ${i.narration} ${Math.abs(i.amount).toFixed(2)}`.toLowerCase().includes(q.toLowerCase())).slice(0, 50);
  return (
    <div className="space-y-2">
      <input aria-label="Search transactions" placeholder="Search by name, reference or amount" className={`${INPUT} w-full`} value={q} onChange={e => setQ(e.target.value)} />
      <ul className="max-h-48 overflow-y-auto divide-y divide-gray-100">
        {shown.map(i => (
          <li key={i.sourceKey} className="flex items-center gap-2 py-1">
            <input type="checkbox" aria-label={`Pick ${i.sourceKey}`} checked={picked.includes(i.sourceKey)}
              onChange={e => setPicked(e.target.checked ? [...picked, i.sourceKey] : picked.filter(k => k !== i.sourceKey))} />
            <span className="flex-1 truncate">{day(i.date)} · {i.contact ?? i.narration} {i.reference && `· ${i.reference}`}</span>
            <span className="tabular-nums">{formatGBP(Number(i.amount))}</span>
          </li>
        ))}
        {shown.length === 0 && <li className="text-gray-400 py-1">Nothing to match on this account</li>}
      </ul>
      <div className="flex items-center justify-between">
        <span className={Math.abs(total - amount) < 0.005 ? "text-green-700" : "text-gray-600"}>Selected {formatGBP(total)} of {formatGBP(amount)}</span>
        <button className={BTN_PRIMARY} disabled={picked.length === 0 || Math.abs(total - amount) >= 0.005} onClick={() => onMatch(picked)}>Reconcile</button>
      </div>
    </div>
  );
}

function CreateForLine({ line, onDone, onError }: { line: BankStatementLine; onDone: () => void; onError: (e: unknown) => void }) {
  const ask = useConfirm();
  const out = line.amount < 0;
  const { data: chart = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", out ? "PURCHASE" : "SALE"], queryFn: () => getTaxRates(out ? "PURCHASE" : "SALE") });
  const [account, setAccount] = useState("");
  const [tax, setTax] = useState("");
  const [description, setDescription] = useState(line.description ?? line.payee ?? "");
  const [contact, setContact] = useState(line.payee ?? "");
  const create = useMutation({
    mutationFn: () => createForBankLine(line.id, { contact, reference: line.reference, lines: [{ accountCode: account, description, amount: Math.abs(line.amount), taxRule: tax || null }] }),
    onSuccess: onDone, onError,
  });
  return (
    <div className="grid grid-cols-2 gap-2">
      <input aria-label="Who" placeholder={out ? "Paid to" : "Received from"} className={INPUT} value={contact} onChange={e => setContact(e.target.value)} />
      <input aria-label="What for" placeholder="Description" className={INPUT} value={description} onChange={e => setDescription(e.target.value)} />
      <select aria-label="Account" className={INPUT} value={account} onChange={e => setAccount(e.target.value)}>
        <option value="">Account…</option>{chart.filter(a => a.type !== "BANK").map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}
      </select>
      <select aria-label="VAT rate" className={INPUT} value={tax} onChange={e => setTax(e.target.value)}>
        <option value="">No VAT</option>{rates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}
      </select>
      <button className={`${BTN_PRIMARY} col-span-2`} disabled={!account || create.isPending} onClick={async () => { if (await ask({ title: "Create this transaction and match it?", message: "It is posted to the accounts.", action: "Create" })) create.mutate(); }}>
        Create {out ? "spend" : "receive"} money and reconcile
      </button>
    </div>
  );
}

function TransferForLine({ line, accounts, onDone, onError }: { line: BankStatementLine; accounts: BankAccountCard[]; onDone: () => void; onError: (e: unknown) => void }) {
  const ask = useConfirm();
  const [to, setTo] = useState("");
  const t = useMutation({ mutationFn: () => transferBankLine(line.id, to), onSuccess: onDone, onError });
  return (
    <div className="flex gap-2">
      <select aria-label="Other bank account" className={`${INPUT} flex-1`} value={to} onChange={e => setTo(e.target.value)}>
        <option value="">{line.amount < 0 ? "Transferred to…" : "Transferred from…"}</option>
        {accounts.map(a => <option key={a.code} value={a.code}>{a.code} - {a.name}</option>)}
      </select>
      <button className={BTN_PRIMARY} disabled={!to || t.isPending} onClick={async () => { if (await ask({ title: "Record this transfer?", action: "Reconcile" })) t.mutate(); }}>Reconcile</button>
    </div>
  );
}

function Reconciled({ code }: { code: string }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data: lines = [] } = useQuery<BankStatementLine[]>({ queryKey: ["bank-lines", code, "RECONCILED"], queryFn: () => getBankLines(code, "RECONCILED") });
  const [error, setError] = useState("");
  const undo = useMutation({
    mutationFn: (id: number) => unreconcileBankLine(id),
    onSuccess: () => ["bank-lines", "bank-accounts"].forEach(k => qc.invalidateQueries({ queryKey: [k] })),
    onError: e => setError(errorOf(e)),
  });
  return (
    <>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="reconciled-lines">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">Date</th><th>Payee / reference</th><th>Matched with</th><th className="text-right">Amount</th><th className="px-4" /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {lines.map(l => (
            <tr key={l.id}>
              <td className="px-4 py-1.5">{day(l.date)}</td>
              <td>{l.payee}{l.reference && <span className="text-gray-400"> · {l.reference}</span>}</td>
              <td className="space-x-2">{l.matched.map(m => <SourceLink key={m.sourceKey} sourceKey={m.sourceKey} sourceType={m.sourceType} reference={m.reference} />)}</td>
              <td className="text-right tabular-nums">{formatGBP(l.amount)}</td>
              <td className="px-4 text-right"><button className={BTN_SECONDARY} onClick={async () => { if (await ask({ title: "Unreconcile this line?", action: "Unreconcile" })) undo.mutate(l.id); }}>Unreconcile</button></td>
            </tr>
          ))}
          {lines.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-gray-400">No reconciled lines</td></tr>}
        </tbody>
      </table>
    </>
  );
}

function Transactions({ code }: { code: string }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data: txns = [] } = useQuery<BankTransaction[]>({ queryKey: ["bank-transactions", code], queryFn: () => getBankTransactions(code) });
  const [error, setError] = useState("");
  const v = useMutation({
    mutationFn: (id: number) => voidBankTransaction(id),
    onSuccess: () => ["bank-transactions", "bank-accounts"].forEach(k => qc.invalidateQueries({ queryKey: [k] })),
    onError: e => setError(errorOf(e)),
  });
  return (
    <>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="bank-transactions">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">Date</th><th>Type</th><th>Contact / reference</th><th>Accounts</th><th className="text-right">Total</th><th>Status</th><th className="px-4" /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {txns.map(t => (
            <tr key={t.id} className={t.status === "VOIDED" ? "text-gray-400" : ""}>
              <td className="px-4 py-1.5">{day(t.date)}</td>
              <td>{t.kind === "SPEND" ? "Spend" : t.kind === "RECEIVE" ? "Receive" : `Transfer ${t.accountCode} → ${t.toAccountCode}`}</td>
              <td>{t.contact}{t.reference && <span className="text-gray-400"> · {t.reference}</span>}</td>
              <td className="text-xs">{t.lines.map(l => `${l.accountCode}${l.tax ? ` (VAT ${formatGBP(l.tax)})` : ""}`).join(", ")}</td>
              <td className="text-right tabular-nums">{formatGBP(t.total)}</td>
              <td>{t.status === "VOIDED" ? "Voided" : t.reconciled ? "Reconciled" : ""}</td>
              <td className="px-4 text-right">{t.status === "ACTIVE" && <button className={BTN_DANGER} onClick={async () => { if (await ask({ title: "Void this transaction?", message: "A reversal is posted.", action: "Void", danger: true })) v.mutate(t.id); }}>Void</button>}</td>
            </tr>
          ))}
          {txns.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-gray-400">No spend / receive money or transfers</td></tr>}
        </tbody>
      </table>
    </>
  );
}
