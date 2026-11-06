"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLedgerAccounts, getLedgerPeriods, getLedgerSettings, getOpeningBalances, saveLedgerPeriods, saveLedgerSettings, saveOpeningBalances } from "@/lib/api";
import { LedgerAccount, LedgerPeriods, LedgerSettings, OpeningBalances } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY } from "@/components/DetailParts";
import { ExportButton, INPUT, errorOf } from "../ledger-parts";

// Which account each kind of posting goes to (Xero export mapping of phase H5, plus the ledger's own)
const MAPPING: [keyof LedgerSettings, string][] = [
  ["revenue", "Sales revenue"], ["receivable", "Accounts receivable"], ["payable", "Accounts payable"], ["vat", "VAT"],
  ["inventory", "Inventory"], ["cogs", "Cost of goods sold"], ["discrepancy", "Stock adjustments (inventory discrepancy)"],
  ["purchaseCharges", "Purchase charges (bill carriage, duty)"], ["customerCredit", "Customer credits"], ["supplierDeposits", "Supplier deposits"],
  ["retainedEarnings", "Retained earnings"], ["suspense", "Suspense (payments without an account)"], ["rounding", "Rounding"],
  ["historical", "Historical adjustment (conversion balances)"],
];

// The forms take the saved values once (they are loaded before the forms show) and keep their own state after a
// save, so the "Saved" message stays

/**
 * Phase K1: the ledger's settings — Xero's conversion date and conversion balances (Advanced → Conversion balances)
 * and the accounts each posting goes to.
 */
export default function LedgerSettingsPage() {
  const { data: settings } = useQuery<LedgerSettings>({ queryKey: ["ledger-settings"], queryFn: getLedgerSettings });
  const { data: opening } = useQuery<OpeningBalances>({ queryKey: ["opening-balances"], queryFn: getOpeningBalances });
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });
  const { data: periods } = useQuery<LedgerPeriods>({ queryKey: ["ledger-periods"], queryFn: getLedgerPeriods });
  if (!settings || !opening || !periods) return <div className="p-6 text-sm text-gray-500">Loading…</div>;
  return (
    <div className="p-6 max-w-5xl space-y-6">
      <PageHeader title="Ledger settings" />
      <PeriodsForm initial={periods} />
      <YearEndPack periods={periods} />
      <SettingsForm initial={settings} accounts={accounts} />
      <BalancesForm initial={opening} accounts={accounts} />
    </div>
  );
}

function SettingsForm({ initial, accounts }: { initial: LedgerSettings; accounts: LedgerAccount[] }) {
  // Xero's Adviser (K5): the accountant, and admins
  const { can } = useAuth();
  const isAdmin = can("ADVISER");
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
  // Xero's Adviser (K5): the accountant, and admins
  const { can } = useAuth();
  const isAdmin = can("ADVISER");
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

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** Phase K2: Xero's Financial settings — financial year end and the two lock dates. */
function PeriodsForm({ initial }: { initial: LedgerPeriods }) {
  // Xero's Adviser (K5): the accountant, and admins
  const { can } = useAuth();
  const isAdmin = can("ADVISER");
  const qc = useQueryClient();
  const [p, setP] = useState<LedgerPeriods>(initial);
  const [msg, setMsg] = useState("");
  const save = useMutation({
    mutationFn: () => saveLedgerPeriods({ ...p, lockDate: p.lockDate || null, hardLockDate: p.hardLockDate || null }),
    onSuccess: () => { setMsg("Saved"); qc.invalidateQueries({ queryKey: ["ledger-periods"] }); },
    onError: e => setMsg(errorOf(e)),
  });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-4" data-testid="ledger-periods">
      <div>
        <h2 className="font-semibold">Financial year end</h2>
        <p className="text-xs text-gray-500">At each year end the year&apos;s profit moves to retained earnings by itself on the balance sheet (no closing journal, as in Xero).</p>
        <div className="flex gap-2 mt-2">
          <select aria-label="Year end day" className={INPUT} disabled={!isAdmin} value={p.yearEndDay} onChange={e => setP({ ...p, yearEndDay: Number(e.target.value) })}>
            {Array.from({ length: 31 }, (_, i) => i + 1).map(d => <option key={d} value={d}>{d}</option>)}
          </select>
          <select aria-label="Year end month" className={INPUT} disabled={!isAdmin} value={p.yearEndMonth} onChange={e => setP({ ...p, yearEndMonth: Number(e.target.value) })}>
            {MONTHS.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
          </select>
        </div>
      </div>
      <div>
        <h2 className="font-semibold">Lock dates</h2>
        <p className="text-xs text-gray-500">Nothing dated on or before a lock date can be entered, changed or voided — for example once a VAT return or the year&apos;s accounts are filed.</p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-2 text-sm">
          <label className="flex flex-col gap-1"><span className="text-gray-700">Lock date for all users except advisers (admins)</span>
            <input aria-label="Lock date for all users except advisers" type="date" className={INPUT} disabled={!isAdmin} value={p.lockDate ?? ""} onChange={e => setP({ ...p, lockDate: e.target.value })} /></label>
          <label className="flex flex-col gap-1"><span className="text-gray-700">Lock date for all users</span>
            <input aria-label="Lock date for all users" type="date" className={INPUT} disabled={!isAdmin} value={p.hardLockDate ?? ""} onChange={e => setP({ ...p, hardLockDate: e.target.value })} /></label>
        </div>
      </div>
      {isAdmin && <div className="flex items-center gap-3"><button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate()}>Save financial settings</button>
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}</div>}
    </section>
  );
}

/** Phase K5: everything the accountant asks for at the year end, in one zip. */
function YearEndPack({ periods }: { periods: LedgerPeriods }) {
  const now = new Date();
  // The last year end passed
  let end = new Date(Date.UTC(now.getFullYear(), periods.yearEndMonth - 1, 1));
  end = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), Math.min(periods.yearEndDay, new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() + 1, 0)).getUTCDate())));
  if (end >= now) end = new Date(Date.UTC(end.getUTCFullYear() - 1, end.getUTCMonth(), Math.min(periods.yearEndDay, new Date(Date.UTC(end.getUTCFullYear() - 1, end.getUTCMonth() + 1, 0)).getUTCDate())));
  const [yearEnd, setYearEnd] = useState(end.toISOString().slice(0, 10));
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-2" data-testid="year-end-pack">
      <h2 className="font-semibold">Year-end pack for the accountant</h2>
      <p className="text-xs text-gray-500">Profit and loss and balance sheet with the year before, trial balance, every journal line of the year, aged debtors and
        creditors, the VAT returns filed and the chart of accounts, as CSV files in one zip. Give your accountant a login with the <b>Accountant</b> role
        (Admin → Users) to work in the books themselves.</p>
      <div className="flex items-center gap-2 text-sm">
        <label className="flex items-center gap-1">Year ending <input aria-label="Year ending" type="date" className={INPUT} value={yearEnd} onChange={e => setYearEnd(e.target.value)} /></label>
        <ExportButton name="year-end" params={{ yearEnd }} filename={`Year-end pack ${yearEnd}.zip`} label="Download the year-end pack" />
      </div>
    </section>
  );
}
