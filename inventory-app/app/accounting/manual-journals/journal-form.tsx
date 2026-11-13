"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLedgerAccounts, saveManualJournal } from "@/lib/api";
import { LedgerAccount, ManualJournal, ManualLine } from "@/types";
import { formatGBP } from "@/lib/currency";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { INPUT, errorOf, today } from "../ledger-parts";
import { useConfirm } from "@/components/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

interface EditLine { accountCode: string; description: string; debit: string; credit: string }
interface Edit { date: string; narration: string; lines: EditLine[] }

const blankLine = (): EditLine => ({ accountCode: "", description: "", debit: "", credit: "" });
const num = (s: string) => (s.trim() === "" ? 0 : Number(s));

function toEdit(j?: ManualJournal): Edit {
  if (!j) return { date: today(), narration: "", lines: [blankLine(), blankLine()] };
  return {
    date: j.date, narration: j.narration,
    lines: j.lines.map((l: ManualLine) => ({ accountCode: l.accountCode, description: l.description ?? "",
      debit: l.debit != null ? String(l.debit) : "", credit: l.credit != null ? String(l.credit) : "" })),
  };
}

/**
 * Phase K1: a manual journal (narration, date, lines with debits = credits), new (/accounting/manual-journals/new) or a draft
 * edited on its own page (review R2, rule D1). Post or save as draft.
 */
export default function JournalForm({ editing, onSaved, onCancel }: {
  editing?: ManualJournal;
  onSaved: (j: ManualJournal) => void;
  onCancel: () => void;
}) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const toast = useToast();
  const [edit, setEdit] = useState<Edit>(() => toEdit(editing));
  const [error, setError] = useState("");
  const { data: accounts = [] } = useQuery<LedgerAccount[]>({ queryKey: ["ledger-accounts", false], queryFn: () => getLedgerAccounts(false) });

  const save = useMutation({
    mutationFn: (post: boolean) => saveManualJournal(editing?.id ?? null, {
      date: edit.date, narration: edit.narration, post,
      lines: edit.lines.map(l => ({ accountCode: l.accountCode, description: l.description || null,
        debit: l.debit ? Number(l.debit) : null, credit: l.credit ? Number(l.credit) : null })),
    }),
    onSuccess: (j: ManualJournal) => {
      toast(j?.status === "POSTED" ? "Journal posted" : "Journal saved");
      qc.invalidateQueries({ queryKey: ["manual-journals"] });
      qc.invalidateQueries({ queryKey: ["manual-journal", j?.id ?? editing?.id] });
      onSaved(j);
    },
    onError: e => setError(errorOf(e)),
  });

  const debits = edit.lines.reduce((s, l) => s + num(l.debit), 0);
  const credits = edit.lines.reduce((s, l) => s + num(l.credit), 0);
  const setLine = (i: number, patch: Partial<EditLine>) => setEdit({ ...edit, lines: edit.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  return (
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
              <td>{edit.lines.length > 2 && <button type="button" aria-label={`Remove line ${i + 1}`} className="text-gray-400 hover:text-red-600" onClick={() => setEdit({ ...edit, lines: edit.lines.filter((_, j) => j !== i) })}>✕</button>}</td>
            </tr>
          ))}
        </tbody>
        <tfoot className="font-medium">
          <tr>
            <td><button type="button" className="text-brand-700 text-sm hover:underline" onClick={() => setEdit({ ...edit, lines: [...edit.lines, blankLine()] })}>+ Add a line</button></td>
            <td className="text-right pr-2">Total</td>
            <td className="text-right pr-2 tabular-nums" data-testid="journal-debits">{formatGBP(debits)}</td>
            <td className="text-right pr-2 tabular-nums" data-testid="journal-credits">{formatGBP(credits)}</td><td />
          </tr>
          {Math.abs(debits - credits) > 0.004 && <tr><td colSpan={5} className="text-right text-red-700 text-xs pt-1">Out of balance by {formatGBP(Math.abs(debits - credits))}</td></tr>}
        </tfoot>
      </table>
      <div className="flex gap-2">
        <button type="button" className={BTN_PRIMARY} disabled={save.isPending} aria-busy={save.isPending} onClick={async () => { if (await ask({ title: "Post this journal?", message: "It goes into the accounts. Afterwards it can only be voided.", action: "Post" })) save.mutate(true); }}>Post</button>
        <button data-confirm-save type="button" className={BTN_SECONDARY} disabled={save.isPending} onClick={() => save.mutate(false)}>Save as draft</button>
        <button type="button" className={BTN_SECONDARY} onClick={onCancel}>Cancel</button>
      </div>
    </section>
  );
}
