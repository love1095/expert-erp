"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLedgerAccounts, getLedgerSettings, getOpeningBalances, saveLedgerSettings, saveOpeningBalances } from "@/lib/api";
import { LedgerAccount, LedgerSettings, OpeningBalances } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY } from "@/components/DetailParts";
import { INPUT, errorOf } from "../ledger-parts";

// Which account each kind of posting goes to (Xero export mapping of phase H5, plus the ledger's own)
const MAPPING: [keyof LedgerSettings, string][] = [
  ["revenue", "Sales revenue"], ["receivable", "Accounts receivable"], ["payable", "Accounts payable"], ["vat", "VAT"],
  ["inventory", "Inventory"], ["cogs", "Cost of goods sold"], ["discrepancy", "Stock adjustments (inventory discrepancy)"],
  ["purchaseCharges", "Purchase charges (bill carriage, duty)"], ["customerCredit", "Customer credits"], ["supplierDeposits", "Supplier deposits"],
  ["retainedEarnings", "Retained earnings"], ["suspense", "Suspense (payments without an account)"], ["rounding", "Rounding"],
  ["historical", "Historical adjustment (conversion balances)"],
];

/**
 * Phase K1: the ledger's settings — Xero's conversion date and conversion balances (Advanced → Conversion balances)
 * and the accounts each posting goes to.
 */
export default function LedgerSettingsPage() {
  const { data: settings } = useQuery<LedgerSettings>({ queryKey: ["ledger-settings"], queryFn: getLedgerSettings });
  const { data: opening } = useQuery<OpeningBalances>({ queryKey: ["opening-balances"], queryFn: getOpeningBalances });
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });
  if (!settings || !opening) return <div className="p-6 text-sm text-gray-500">Loading…</div>;
  return (
    <div className="p-6 max-w-5xl space-y-6">
      <PageHeader title="Ledger settings" />
      <SettingsForm key={JSON.stringify(settings)} initial={settings} accounts={accounts} />
      <BalancesForm key={JSON.stringify(opening)} initial={opening} accounts={accounts} />
    </div>
  );
}

function SettingsForm({ initial, accounts }: { initial: LedgerSettings; accounts: LedgerAccount[] }) {
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const qc = useQueryClient();
  const [s, setS] = useState<LedgerSettings>(initial);
  const [msg, setMsg] = useState("");
  const save = useMutation({
    mutationFn: () => saveLedgerSettings({ ...s, conversionDate: s.conversionDate || null }),
    onSuccess: () => { setMsg("Saved"); qc.invalidateQueries({ queryKey: ["ledger-settings"] }); qc.invalidateQueries({ queryKey: ["opening-balances"] }); },
    onError: e => setMsg(errorOf(e)),
  });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-4" data-testid="ledger-settings">
      <div>
        <h2 className="font-semibold">Conversion date</h2>
        <p className="text-xs text-gray-500">The first day the ERP keeps the books. Nothing dated before it is posted: the conversion balances below stand for it (Xero&apos;s conversion date).</p>
        <input aria-label="Conversion date" type="date" className={`${INPUT} mt-2`} disabled={!isAdmin} value={s.conversionDate ?? ""} onChange={e => setS({ ...s, conversionDate: e.target.value })} />
      </div>
      <div>
        <h2 className="font-semibold">Accounts the ERP posts to</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 mt-2 text-sm">
          {MAPPING.map(([k, label]) => (
            <label key={k} className="flex items-center justify-between gap-2">
              <span className="text-gray-700">{label}</span>
              <select aria-label={label} className={`${INPUT} w-64`} disabled={!isAdmin} value={String(s[k] ?? "")} onChange={e => setS({ ...s, [k]: e.target.value })}>
                {!accounts.some(a => a.code === s[k]) && <option value={String(s[k] ?? "")}>{s[k]} (not in the chart)</option>}
                {accounts.map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}
              </select>
            </label>
          ))}
        </div>
      </div>
      {isAdmin && <div className="flex items-center gap-3"><button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save settings</button>
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}</div>}
    </section>
  );
}

function BalancesForm({ initial, accounts }: { initial: OpeningBalances; accounts: LedgerAccount[] }) {
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const qc = useQueryClient();
  const [rows, setRows] = useState(() => [...initial.balances.map(b => ({ code: b.accountCode, debit: b.debit ? String(b.debit) : "", credit: b.credit ? String(b.credit) : "" })),
    { code: "", debit: "", credit: "" }]);
  const [msg, setMsg] = useState("");
  const save = useMutation({
    mutationFn: () => saveOpeningBalances(rows.filter(r => r.code).map(r => ({ accountCode: r.code, debit: r.debit ? Number(r.debit) : null, credit: r.credit ? Number(r.credit) : null }))),
    onSuccess: () => { setMsg("Saved"); qc.invalidateQueries({ queryKey: ["opening-balances"] }); },
    onError: e => setMsg(errorOf(e)),
  });
  const debits = rows.reduce((t, r) => t + (Number(r.debit) || 0), 0), credits = rows.reduce((t, r) => t + (Number(r.credit) || 0), 0);
  const set = (i: number, patch: Partial<typeof rows[number]>) => {
    const next = rows.map((r, j) => (j === i ? { ...r, ...patch } : r));
    if (i === rows.length - 1 && next[i].code) next.push({ code: "", debit: "", credit: "" });
    setRows(next);
  };
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="conversion-balances">
      <h2 className="font-semibold">Conversion balances</h2>
      {!initial.conversionDate ? <p className="text-sm text-gray-500">Set the conversion date first.</p> : (<>
        <p className="text-xs text-gray-500">Each account&apos;s balance on {new Date(new Date(initial.conversionDate).getTime() - 86400000).toLocaleDateString("en-GB")}, from the old system&apos;s trial balance.
          What does not balance goes to Historical Adjustment, as in Xero.</p>
        <table className="w-full text-sm">
          <thead className="text-xs text-gray-500 text-left"><tr><th>Account</th><th className="text-right w-36">Debit</th><th className="text-right w-36">Credit</th></tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td className="py-1 pr-2">
                  <select aria-label={`Balance ${i + 1} account`} className={`${INPUT} w-full`} disabled={!isAdmin} value={r.code} onChange={e => set(i, { code: e.target.value })}>
                    <option value="">Choose…</option>
                    {accounts.map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}
                  </select>
                </td>
                <td className="pr-2"><input aria-label={`Balance ${i + 1} debit`} disabled={!isAdmin} inputMode="decimal" className={`${INPUT} w-full text-right`} value={r.debit} onChange={e => set(i, { debit: e.target.value, credit: e.target.value ? "" : r.credit })} /></td>
                <td><input aria-label={`Balance ${i + 1} credit`} disabled={!isAdmin} inputMode="decimal" className={`${INPUT} w-full text-right`} value={r.credit} onChange={e => set(i, { credit: e.target.value, debit: e.target.value ? "" : r.debit })} /></td>
              </tr>
            ))}
          </tbody>
          <tfoot className="font-medium">
            <tr><td className="text-right pr-2">Total</td><td className="text-right pr-2 tabular-nums">{formatGBP(debits)}</td><td className="text-right tabular-nums">{formatGBP(credits)}</td></tr>
            {Math.abs(debits - credits) > 0.004 && <tr><td className="text-right pr-2 text-gray-500">Historical adjustment</td><td colSpan={2} className="text-right tabular-nums text-gray-600">{formatGBP(credits - debits)}</td></tr>}
          </tfoot>
        </table>
        {isAdmin && <div className="flex items-center gap-3"><button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save balances</button>
          {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}</div>}
      </>)}
    </section>
  );
}
