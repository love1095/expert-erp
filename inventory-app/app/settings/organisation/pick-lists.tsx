"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getSettingsList, removeSettingsListEntry, renameSettingsListEntry } from "@/lib/api";
import { PickListEntry } from "@/types";
import { useConfirm } from "@/components/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { PaymentTermDialog, SalesRepDialog } from "@/components/QuickAdd";

const INPUT = "border border-gray-300 rounded-md px-2 py-1 text-sm";

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Could not change the list";
}

type Kind = "payment-terms" | "sales-reps";
const TEXT: Record<Kind, { title: string; intro: string; one: string; usedOn: string; testId: string; keys: string[] }> = {
  "payment-terms": {
    title: "Payment terms", one: "payment term", usedOn: "customers, suppliers and orders", testId: "payment-terms-list", keys: ["payment-terms"],
    intro: "The terms offered on customers, suppliers and orders, each with how it sets an invoice's due date. Renaming a term renames it everywhere it is used and keeps its rule; a term in use cannot be removed.",
  },
  "sales-reps": {
    title: "Sales representatives", one: "sales representative", usedOn: "customers and sale orders", testId: "sales-reps-list", keys: ["sales-reps"],
    intro: "The names offered on customers and sale orders (they do not need a login). Renaming one renames it on its customers and orders; a name in use cannot be removed.",
  },
};

/**
 * Settings → Organisation: the lists behind the "+" buttons (payment terms, sales representatives). Add, rename
 * (everything using the entry follows) and remove (only what nothing uses).
 */
export default function PickListCard({ kind, isAdmin }: { kind: Kind; isAdmin: boolean }) {
  const t = TEXT[kind];
  const qc = useQueryClient();
  const ask = useConfirm();
  const toast = useToast();
  const { data: entries = [] } = useQuery<PickListEntry[]>({ queryKey: ["pick-list", kind], queryFn: () => getSettingsList(kind), enabled: isAdmin });
  const [editing, setEditing] = useState<{ from: string; to: string } | null>(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState("");
  const done = (list: PickListEntry[], text: string) => {
    qc.setQueryData(["pick-list", kind], list);
    t.keys.forEach(k => qc.invalidateQueries({ queryKey: [k] }));
    setEditing(null); setError(""); toast(text);
  };
  const rename = useMutation({
    mutationFn: (v: { from: string; to: string }) => renameSettingsListEntry(kind, v.from, v.to),
    onSuccess: (list: PickListEntry[]) => done(list, "Renamed"), onError: e => setError(errorText(e)),
  });
  const remove = useMutation({
    mutationFn: (name: string) => removeSettingsListEntry(kind, name),
    onSuccess: (list: PickListEntry[]) => done(list, "Removed"), onError: e => setError(errorText(e)),
  });
  if (!isAdmin) return null;

  return (
    <section data-testid={t.testId} className="bg-white border border-gray-200 rounded-xl p-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-gray-800">{t.title}</h2>
          <p className="text-xs text-gray-500 mt-0.5 mb-3 max-w-2xl">{t.intro}</p>
        </div>
        <button type="button" onClick={() => setAdding(true)} className="shrink-0 text-sm text-brand-700 hover:underline">+ Add a {t.one}</button>
      </div>
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded mb-3">{error}</p>}
      <table className="text-sm w-full max-w-2xl">
        <thead><tr className="text-xs text-gray-500 text-left"><th className="pr-3 py-1">Name</th>{kind === "payment-terms" && <th className="pr-3">Due date</th>}<th className="pr-3 text-right">Used on</th><th></th></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {entries.length === 0 && <tr><td colSpan={4} className="py-2 text-gray-400">None yet.</td></tr>}
          {entries.map(e => (
            <tr key={e.name}>
              <td className="pr-3 py-1.5">
                {editing?.from === e.name ? (
                  // Not a <form>: its own question is asked below
                  <span className="inline-flex items-center gap-2">
                    <input aria-label={`New name for ${e.name}`} autoFocus value={editing.to} maxLength={100} className={`${INPUT} w-52`}
                      onChange={ev => setEditing({ from: e.name, to: ev.target.value })}
                      onKeyDown={ev => { if (ev.key === "Escape") setEditing(null); }} />
                    <button type="button" disabled={rename.isPending || !editing.to.trim() || editing.to.trim() === e.name} className="text-xs text-brand-700 hover:underline disabled:text-gray-300"
                      onClick={async () => {
                        const to = editing.to.trim();
                        if (await ask({ title: `Rename ${e.name} to ${to}?`, message: e.uses > 0 ? `It is renamed on the ${e.uses.toLocaleString()} ${t.usedOn} that use it.` : "Nothing uses it yet.", action: "Rename" })) rename.mutate({ from: e.name, to });
                      }}>Rename</button>
                    <button type="button" className="text-xs text-gray-500 hover:underline" onClick={() => setEditing(null)}>Cancel</button>
                  </span>
                ) : <span className="text-gray-900">{e.name}</span>}
                {kind === "sales-reps" && e.meaning && <span className="ml-2 text-[11px] text-gray-400">{e.meaning}</span>}
              </td>
              {kind === "payment-terms" && <td className="pr-3 text-gray-600">{e.meaning}</td>}
              <td className="pr-3 text-right tabular-nums text-gray-600">{e.uses.toLocaleString()}</td>
              <td className="text-right whitespace-nowrap">
                {editing?.from !== e.name && <button type="button" className="text-xs text-brand-700 hover:underline mr-3" onClick={() => { setError(""); setEditing({ from: e.name, to: e.name }); }}>Rename</button>}
                {e.added && e.uses === 0 && (
                  <button type="button" disabled={remove.isPending} className="text-xs text-red-600 hover:underline"
                    onClick={async () => { if (await ask({ title: `Remove ${e.name}?`, message: `It is taken off the ${t.title.toLowerCase()} list.`, action: "Remove", danger: true })) remove.mutate(e.name); }}>Remove</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {adding && kind === "payment-terms" && <PaymentTermDialog onClose={() => setAdding(false)} onAdded={() => { setAdding(false); qc.invalidateQueries({ queryKey: ["pick-list", kind] }); }} />}
      {adding && kind === "sales-reps" && <SalesRepDialog onClose={() => setAdding(false)} onAdded={() => { setAdding(false); qc.invalidateQueries({ queryKey: ["pick-list", kind] }); }} />}
    </section>
  );
}
