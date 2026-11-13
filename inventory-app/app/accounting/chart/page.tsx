"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteLedgerAccount, getLedgerAccounts, getLedgerAccountTypes, importLedgerAccounts, saveLedgerAccount } from "@/lib/api";
import { LedgerAccount, LedgerAccountType } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { CLASS_LABEL, INPUT, errorOf, today } from "../ledger-parts";
import { useConfirm } from "@/components/ConfirmDialog";

type Tab = "ALL" | "ASSET" | "LIABILITY" | "EQUITY" | "EXPENSE" | "REVENUE" | "ARCHIVED";
interface Form { id: number | null; code: string; name: string; type: string; taxType: string; description: string; archived: boolean }

/**
 * Phase K1: Xero's Chart of accounts — tabs by class (All, Assets, Liabilities, Equity, Expenses, Revenue, Archive),
 * add / edit / archive / delete, and import of Xero's chart export.
 */
export default function ChartPage() {
  const ask = useConfirm();
  // Xero's Adviser (K5): the accountant, and admins
  const { can } = useAuth();
  const isAdmin = can("ADVISER");
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("ALL");
  const [search, setSearch] = useState("");
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState("");
  const { data: accounts = [], isLoading } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", true], queryFn: () => getLedgerAccounts(true) });
  const { data: types = [] } = useQuery<LedgerAccountType[]>({ queryKey: ["ledger-account-types"], queryFn: getLedgerAccountTypes });

  const save = useMutation({
    mutationFn: (f: Form) => saveLedgerAccount(f.id, { code: f.code, name: f.name, type: f.type, taxType: f.taxType, description: f.description, archived: f.archived }),
    onSuccess: () => { setForm(null); setError(""); qc.invalidateQueries({ queryKey: ["ledger-accounts"] }); },
    onError: e => setError(errorOf(e)),
  });
  const remove = useMutation({
    mutationFn: (id: number) => deleteLedgerAccount(id),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ["ledger-accounts"] }); },
    onError: e => setError(errorOf(e)),
  });

  const q = search.trim().toLowerCase();
  const shown = accounts.filter(a => (tab === "ARCHIVED" ? a.archived : !a.archived && (tab === "ALL" || a.accountClass === tab)))
    .filter(a => !q || a.code.toLowerCase().includes(q) || a.name.toLowerCase().includes(q));
  const tabs: [Tab, string][] = [["ALL", "All accounts"], ["ASSET", "Assets"], ["LIABILITY", "Liabilities"], ["EQUITY", "Equity"],
    ["EXPENSE", "Expenses"], ["REVENUE", "Revenue"], ["ARCHIVED", "Archive"]];

  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Chart of accounts" />
      <div className="flex flex-wrap items-center gap-3">
        <input aria-label="Search accounts" placeholder="Search by code or name" value={search} onChange={e => setSearch(e.target.value)} className={`${INPUT} w-64`} />
        {isAdmin && <button className={BTN_PRIMARY} onClick={() => { setError(""); setForm({ id: null, code: "", name: "", type: "OVERHEADS", taxType: "", description: "", archived: false }); }}>+ Add account</button>}
        {isAdmin && <ChartImport onDone={() => qc.invalidateQueries({ queryKey: ["ledger-accounts"] })} />}
      </div>
      <div className="flex gap-5 border-b border-gray-200 text-sm" role="tablist">
        {tabs.map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`pb-2 -mb-px ${tab === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500 hover:text-gray-700"}`}>{label}</button>
        ))}
      </div>

      {form && (
        <section data-testid="account-form" className="bg-white border border-gray-200 rounded-xl p-5 space-y-3 max-w-3xl">
          <h2 className="font-semibold">{form.id ? `Edit account ${form.code}` : "Add account"}</h2>
          {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}
          <div className="grid grid-cols-2 gap-3 text-sm">
            <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Account type</span>
              <select aria-label="Account type" className={INPUT} value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}>
                {Object.keys(CLASS_LABEL).map(c => (
                  <optgroup key={c} label={CLASS_LABEL[c]}>
                    {types.filter(t => t.accountClass === c).map(t => <option key={t.type} value={t.type}>{t.label}</option>)}
                  </optgroup>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Code</span>
              <input aria-label="Code" className={INPUT} value={form.code} maxLength={10} onChange={e => setForm({ ...form, code: e.target.value })} /></label>
            <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Name</span>
              <input aria-label="Name" className={INPUT} value={form.name} maxLength={150} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
            <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Tax (default VAT rate)</span>
              <input aria-label="Tax" className={INPUT} value={form.taxType} placeholder="e.g. 20% (VAT on Expenses)" onChange={e => setForm({ ...form, taxType: e.target.value })} /></label>
            <label className="flex flex-col gap-1 col-span-2"><span className="text-xs text-gray-600">Description</span>
              <input aria-label="Description" className={INPUT} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
            {form.id && <label className="flex items-center gap-2"><input type="checkbox" checked={form.archived} onChange={e => setForm({ ...form, archived: e.target.checked })} /> Archived</label>}
          </div>
          <div className="flex gap-2">
            <button data-confirm-save className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate(form)}>Save</button>
            <button className={BTN_SECONDARY} onClick={() => setForm(null)}>Cancel</button>
            {form.id && <button className={`${BTN_DANGER} ml-auto`} onClick={async () => { if (await ask({ title: "Delete this account?", action: "Delete", danger: true })) remove.mutate(form.id!); }}>Delete</button>}
          </div>
        </section>
      )}

      {isLoading ? <p className="text-sm text-gray-500">Loading…</p> : (
        <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="chart-of-accounts">
          <thead className="text-xs text-gray-500 text-left bg-gray-50">
            <tr><th className="px-4 py-2 w-20">Code</th><th>Name</th><th>Type</th><th>Tax rate</th><th className="px-4" /></tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {shown.map(a => (
              <tr key={a.id} className="hover:bg-gray-50">
                <td className="px-4 py-2 font-mono">{a.code}</td>
                <td>
                  {isAdmin ? <button className="text-brand-700 hover:underline text-left" onClick={() => { setError(""); setForm({ id: a.id, code: a.code, name: a.name, type: a.type, taxType: a.taxType ?? "", description: a.description ?? "", archived: a.archived }); }}>{a.name}</button> : a.name}
                  {a.description && <div className="text-xs text-gray-400 truncate max-w-md">{a.description}</div>}
                </td>
                <td className="text-gray-600">{a.typeLabel}</td>
                <td className="text-gray-600">{a.taxType ?? ""}</td>
                <td className="px-4 text-right">
                  {a.used && <Link className="text-xs text-brand-600 hover:underline" href={`/accounting/account-transactions?account=${encodeURIComponent(a.code)}&to=${today()}`}>Transactions</Link>}
                </td>
              </tr>
            ))}
            {shown.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-gray-400">No accounts</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** Xero's Chart of accounts → Import: its own export file, previewed first. */
function ChartImport({ onDone }: { onDone: () => void }) {
  const ask = useConfirm();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<{ added: number; updated: number; unchanged: number; warnings: string[]; applied?: boolean } | null>(null);
  const [error, setError] = useState("");
  const run = useMutation({
    mutationFn: (apply: boolean) => importLedgerAccounts(file!, apply).then(r => ({ ...r, applied: apply })),
    onSuccess: r => { setResult(r); setError(""); if (r.applied) onDone(); },
    onError: e => { setResult(null); setError(errorOf(e)); },
  });
  return (
    <span className="flex items-center gap-2 text-sm">
      <input aria-label="Xero chart file" type="file" accept=".csv,text/csv" className="text-xs w-56" onChange={e => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
      <button className={BTN_SECONDARY} disabled={!file || run.isPending} onClick={() => run.mutate(false)}>Import from Xero file</button>
      {error && <span role="alert" className="text-red-700">{error}</span>}
      {result && (
        <span data-testid="chart-import-result" className="text-gray-700">
          {result.applied ? "✓ Imported" : "Preview"}: {result.added} new, {result.updated} changed, {result.unchanged} the same
          {result.warnings.length > 0 && <span className="text-amber-700"> · {result.warnings.join("; ")}</span>}
          {!result.applied && result.added + result.updated > 0 &&
            <button className="ml-2 text-brand-700 underline" onClick={async () => { if (await ask({ title: "Import the chart of accounts?", message: "Accounts in the file are created or changed.", action: "Import" })) run.mutate(true); }}>Import</button>}
        </span>
      )}
    </span>
  );
}
