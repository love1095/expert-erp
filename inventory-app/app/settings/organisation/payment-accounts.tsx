"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getPaymentAccounts, savePaymentAccounts } from "@/lib/api";
import { PaymentAccount, PaymentAccountKind } from "@/types";

const INPUT = "border border-gray-300 rounded-md px-2 py-1 text-sm";
const KINDS: { value: PaymentAccountKind; label: string }[] = [
  { value: "BANK", label: "Bank" }, { value: "CARD", label: "Card" }, { value: "CASH", label: "Cash" },
  { value: "PAYPAL", label: "PayPal" }, { value: "SHOPIFY", label: "Shopify" }, { value: "OTHER", label: "Other" },
];

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Could not save the payment accounts";
}

/**
 * Phase H1: where money is paid in, like Cin7's payment accounts from the Xero chart (e.g. 650 Current Account - RBS,
 * 643 Card Payments). Each payment picks one; the default is chosen first. The codes go to the accounting package (H5).
 */
export default function PaymentAccountsCard({ isAdmin }: { isAdmin: boolean }) {
  const qc = useQueryClient();
  const { data: accounts } = useQuery<PaymentAccount[]>({ queryKey: ["payment-accounts"], queryFn: getPaymentAccounts });
  const [rows, setRows] = useState<PaymentAccount[] | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const shown = rows ?? accounts ?? [];

  const save = useMutation({
    mutationFn: () => savePaymentAccounts(shown.map(r => ({ id: r.id, code: r.code, name: r.name, kind: r.kind, active: r.active, defaultAccount: r.defaultAccount }))),
    onSuccess: (saved: PaymentAccount[]) => { setRows(null); qc.setQueryData(["payment-accounts"], saved); setMessage({ ok: true, text: "Saved" }); },
    onError: e => setMessage({ ok: false, text: errorText(e) }),
  });
  const set = (i: number, patch: Partial<PaymentAccount>) => {
    setMessage(null);
    setRows(shown.map((r, j) => (j === i ? { ...r, ...patch } : patch.defaultAccount ? { ...r, defaultAccount: false } : r)));
  };

  return (
    <section data-testid="payment-accounts" className="bg-white border border-gray-200 rounded-xl p-6">
      <h2 className="font-semibold text-gray-800">Payment accounts</h2>
      <p className="text-xs text-gray-500 mt-0.5 mb-3">
        Where payments go, with the codes of your accounting chart (in Cin7: 650 Current Account - RBS, 643 Card Payments…). A payment
        picks one; the default comes first. An account that payments use can be switched off but not removed.
      </p>
      {shown.length === 0 && <p className="text-sm text-gray-400 mb-2">None yet: payments only record how they were paid</p>}
      {shown.length > 0 && (
        <table className="text-sm">
          <thead><tr className="text-xs text-gray-500 text-left"><th className="pr-3 py-1">Code</th><th className="pr-3">Name</th><th className="pr-3">Kind</th>
            <th className="pr-3">Active</th><th className="pr-3">Default</th><th></th></tr></thead>
          <tbody>
            {shown.map((r, i) => (
              <tr key={r.id ?? `new-${i}`}>
                <td className="pr-3 py-1"><input aria-label={`Account ${i + 1} code`} value={r.code} disabled={!isAdmin} maxLength={20}
                  onChange={e => set(i, { code: e.target.value })} className={`${INPUT} w-20`} /></td>
                <td className="pr-3"><input aria-label={`Account ${i + 1} name`} value={r.name} disabled={!isAdmin} maxLength={150}
                  onChange={e => set(i, { name: e.target.value })} className={`${INPUT} w-64`} /></td>
                <td className="pr-3"><select aria-label={`Account ${i + 1} kind`} value={r.kind} disabled={!isAdmin}
                  onChange={e => set(i, { kind: e.target.value as PaymentAccountKind })} className={INPUT}>
                  {KINDS.map(k => <option key={k.value} value={k.value}>{k.label}</option>)}</select></td>
                <td className="pr-3 text-center"><input type="checkbox" aria-label={`Account ${i + 1} active`} checked={r.active} disabled={!isAdmin}
                  onChange={e => set(i, { active: e.target.checked, ...(e.target.checked ? {} : { defaultAccount: false }) })} /></td>
                <td className="pr-3 text-center"><input type="radio" name="default-payment-account" aria-label={`Account ${i + 1} default`}
                  checked={r.defaultAccount} disabled={!isAdmin || !r.active} onChange={() => set(i, { defaultAccount: true })} /></td>
                <td>{isAdmin && !r.used && (
                  <button type="button" onClick={() => setRows(shown.filter((_, j) => j !== i))} className="text-xs text-red-600 hover:underline">Remove</button>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {isAdmin && (
        <div className="mt-3 flex items-center gap-3">
          <button type="button" onClick={() => setRows([...shown, { code: "", name: "", kind: "BANK", active: true, defaultAccount: shown.length === 0 }])}
            className="text-sm text-brand-700 hover:underline">+ Add account</button>
          <button data-confirm-save="Save payment accounts" type="button" onClick={() => save.mutate()} disabled={save.isPending || rows == null}
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {save.isPending ? "Saving..." : "Save payment accounts"}
          </button>
          {message && <span role="status" className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.ok ? "✓ " : "✕ "}{message.text}</span>}
        </div>
      )}
    </section>
  );
}
