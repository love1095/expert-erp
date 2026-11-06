"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLedgerAccounts, getManualJournals, postManualJournal, saveManualJournal, voidManualJournal } from "@/lib/api";
import { LedgerAccount, ManualJournal, ManualLine } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { INPUT, errorOf, today } from "../ledger-parts";

interface EditLine { accountCode: string; description: string; debit: string; credit: string }
interface Edit { id: number | null; date: string; narration: string; lines: EditLine[] }

const blankLine = (): EditLine => ({ accountCode: "", description: "", debit: "", credit: "" });
const num = (s: string) => (s.trim() === "" ? 0 : Number(s));

/** Phase K1: Xero's Manual journals — Draft / Posted / Voided tabs; narration, date, lines with debits = credits. */
export default function ManualJournalsPage() {
  const qc = useQueryClient();
  const [status, setStatus] = useState<"" | "DRAFT" | "POSTED" | "VOIDED">("");
  const [edit, setEdit] = useState<Edit | null>(null);
  const [error, setError] = useState("");
  const { data: journals = [] } = useQuery<ManualJournal[]>({ queryKey: ["manual-journals", status], queryFn: () => getManualJournals(status || undefined) });
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });
  const refresh = () => qc.invalidateQueries({ queryKey: ["manual-journals"] });

  const save = useMutation({
    mutationFn: ({ e, post }: { e: Edit; post: boolean }) => saveManualJournal(e.id, {
      date: e.date, narration: e.narration, post,
      lines: e.lines.map(l => ({ accountCode: l.accountCode, description: l.description || null,
        debit: l.debit ? Number(l.debit) : null, credit: l.credit ? Number(l.credit) : null })),
    }),
    onSuccess: () => { setEdit(null); setError(""); refresh(); },
    onError: e => setError(errorOf(e)),
  });
  const act = useMutation({
    mutationFn: ({ id, what }: { id: number; what: "post" | "void" }) => (what === "post" ? postManualJournal(id) : voidManualJournal(id)),
    onSuccess: refresh,
    onError: e => setError(errorOf(e)),
  });

  const debits = edit?.lines.reduce((s, l) => s + num(l.debit), 0) ?? 0;
  const credits = edit?.lines.reduce((s, l) => s + num(l.credit), 0) ?? 0;
  const setLine = (i: number, patch: Partial<EditLine>) => edit && setEdit({ ...edit, lines: edit.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Manual journals" />
      {error && !edit && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}
      {!edit && <button className={BTN_PRIMARY} onClick={() => { setError(""); setEdit({ id: null, date: today(), narration: "", lines: [blankLine(), blankLine()] }); }}>+ New journal</button>}

      {edit && (
        <section data-testid="journal-form" className="bg-white border border-gray-200 rounded-xl p-5 space-y-3">
          {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}
          <div className="flex flex-wrap gap-3 text-sm">
            <label className="flex flex-col gap-1 flex-1 min-w-[20rem]"><span className="text-xs text-gray-600">Narration</span>
              <input aria-label="Narration" className={INPUT} value={edit.narration} onChange={e => setEdit({ ...edit, narration: e.target.value })} /></label>
            <label className="flex flex-col gap-1"><span className="text-xs text-gray-600">Date</span>
              <input aria-label="Date" type="date" className={INPUT} value={edit.date} onChange={e => setEdit({ ...edit, date: e.target.value })} /></label>
          </div>
          <table className="w-full text-sm">
            <thead className="text-xs text-gray-500 text-left"><tr><th>Description</th><th className="w-72">Account</th><th className="w-32 text-right">Debit</th><th className="w-32 text-right">Credit</th><th className="w-8" /></tr></thead>
            <tbody>
              {edit.lines.map((l, i) => (
                <tr key={i}>
                  <td className="pr-2 py-1"><input aria-label={`Line ${i + 1} description`} className={`${INPUT} w-full`} value={l.description} onChange={e => setLine(i, { description: e.target.value })} /></td>
                  <td className="pr-2">
                    <select aria-label={`Line ${i + 1} account`} className={`${INPUT} w-full`} value={l.accountCode} onChange={e => setLine(i, { accountCode: e.target.value })}>
                      <option value="">Choose…</option>
                      {accounts.map(a => <option key={a.id} value={a.code}>{a.code} - {a.name}</option>)}
                    </select>
                  </td>
                  <td className="pr-2"><input aria-label={`Line ${i + 1} debit`} inputMode="decimal" className={`${INPUT} w-full text-right`} value={l.debit} onChange={e => setLine(i, { debit: e.target.value, credit: e.target.value ? "" : l.credit })} /></td>
                  <td className="pr-2"><input aria-label={`Line ${i + 1} credit`} inputMode="decimal" className={`${INPUT} w-full text-right`} value={l.credit} onChange={e => setLine(i, { credit: e.target.value, debit: e.target.value ? "" : l.debit })} /></td>
                  <td>{edit.lines.length > 2 && <button aria-label={`Remove line ${i + 1}`} className="text-gray-400 hover:text-red-600" onClick={() => setEdit({ ...edit, lines: edit.lines.filter((_, j) => j !== i) })}>✕</button>}</td>
                </tr>
              ))}
            </tbody>
            <tfoot className="font-medium">
              <tr>
                <td><button className="text-brand-700 text-sm hover:underline" onClick={() => setEdit({ ...edit, lines: [...edit.lines, blankLine()] })}>+ Add a line</button></td>
                <td className="text-right pr-2">Total</td>
                <td className="text-right pr-2 tabular-nums" data-testid="journal-debits">{formatGBP(debits)}</td>
                <td className="text-right pr-2 tabular-nums" data-testid="journal-credits">{formatGBP(credits)}</td><td />
              </tr>
              {Math.abs(debits - credits) > 0.004 && <tr><td colSpan={5} className="text-right text-red-700 text-xs pt-1">Out of balance by {formatGBP(Math.abs(debits - credits))}</td></tr>}
            </tfoot>
          </table>
          <div className="flex gap-2">
            <button className={BTN_PRIMARY} disabled={save.isPending} onClick={() => save.mutate({ e: edit, post: true })}>Post</button>
            <button className={BTN_SECONDARY} disabled={save.isPending} onClick={() => save.mutate({ e: edit, post: false })}>Save as draft</button>
            <button className={BTN_SECONDARY} onClick={() => { setEdit(null); setError(""); }}>Cancel</button>
          </div>
        </section>
      )}

      <div className="flex gap-5 border-b border-gray-200 text-sm" role="tablist">
        {([["", "All"], ["DRAFT", "Draft"], ["POSTED", "Posted"], ["VOIDED", "Voided"]] as const).map(([k, label]) => (
          <button key={k} role="tab" aria-selected={status === k} onClick={() => setStatus(k)}
            className={`pb-2 -mb-px ${status === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500"}`}>{label}</button>
        ))}
      </div>
      <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="manual-journals">
        <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">Narration</th><th>Date</th><th className="text-right">Amount</th><th>Status</th><th className="px-4" /></tr></thead>
        <tbody className="divide-y divide-gray-100">
          {journals.map(j => (
            <tr key={j.id}>
              <td className="px-4 py-2">
                {j.status === "DRAFT" ? <button className="text-brand-700 hover:underline text-left" onClick={() => {
                  setError("");
                  setEdit({ id: j.id, date: j.date, narration: j.narration, lines: j.lines.map((l: ManualLine) => ({ accountCode: l.accountCode, description: l.description ?? "",
                    debit: l.debit != null ? String(l.debit) : "", credit: l.credit != null ? String(l.credit) : "" })) });
                }}>{j.narration}</button> : j.narration}
                <div className="text-xs text-gray-400">{j.lines.map(l => `${l.accountCode} ${l.debit ? "Dr" : "Cr"} ${formatGBP(Number(l.debit ?? l.credit))}`).join(" · ")}</div>
              </td>
              <td>{new Date(j.date).toLocaleDateString("en-GB")}</td>
              <td className="text-right tabular-nums">{formatGBP(j.total)}</td>
              <td><span className={`text-xs px-2 py-0.5 rounded ${j.status === "POSTED" ? "bg-green-50 text-green-700" : j.status === "VOIDED" ? "bg-gray-100 text-gray-500" : "bg-amber-50 text-amber-700"}`}>{j.status === "POSTED" ? "Posted" : j.status === "VOIDED" ? "Voided" : "Draft"}</span></td>
              <td className="px-4 text-right whitespace-nowrap space-x-2">
                {j.status === "DRAFT" && <button className={BTN_SECONDARY} onClick={() => act.mutate({ id: j.id, what: "post" })}>Post</button>}
                {j.status !== "VOIDED" && <button className={BTN_DANGER} onClick={() => act.mutate({ id: j.id, what: "void" })}>{j.status === "DRAFT" ? "Delete" : "Void"}</button>}
              </td>
            </tr>
          ))}
          {journals.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-gray-400">No manual journals</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
