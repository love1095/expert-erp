"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getManualJournals, postManualJournal, voidManualJournal } from "@/lib/api";
import Link from "next/link";
import { ManualJournal } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { errorOf } from "../ledger-parts";
import { useConfirm } from "@/components/ConfirmDialog";

/** Phase K1: Xero's Manual journals — Draft / Posted / Voided tabs; narration, date, lines with debits = credits. */
export default function ManualJournalsPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [status, setStatus] = useState<"" | "DRAFT" | "POSTED" | "VOIDED">("");
  const [error, setError] = useState("");
  const { data: journals = [] } = useQuery<ManualJournal[]>({ queryKey: ["manual-journals", status], queryFn: () => getManualJournals(status || undefined) });
  const refresh = () => qc.invalidateQueries({ queryKey: ["manual-journals"] });

  const act = useMutation({
    mutationFn: ({ id, what }: { id: number; what: "post" | "void" }) => (what === "post" ? postManualJournal(id) : voidManualJournal(id)),
    onSuccess: refresh,
    onError: e => setError(errorOf(e)),
  });

  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Manual journals" />
      {error && <p role="alert" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}
      <Link href="/accounting/manual-journals/new" className={`${BTN_PRIMARY} inline-block`}>+ New journal</Link>

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
                <Link href={`/accounting/manual-journals/${j.id}`} className="text-brand-700 hover:underline">{j.narration}</Link>
                <div className="text-xs text-gray-400">{j.lines.map(l => `${l.accountCode} ${l.debit ? "Dr" : "Cr"} ${formatGBP(Number(l.debit ?? l.credit))}`).join(" · ")}</div>
              </td>
              <td>{new Date(j.date).toLocaleDateString("en-GB")}</td>
              <td className="text-right tabular-nums">{formatGBP(j.total)}</td>
              <td><span className={`text-xs px-2 py-0.5 rounded ${j.status === "POSTED" ? "bg-green-50 text-green-700" : j.status === "VOIDED" ? "bg-gray-100 text-gray-500" : "bg-amber-50 text-amber-700"}`}>{j.status === "POSTED" ? "Posted" : j.status === "VOIDED" ? "Voided" : "Draft"}</span></td>
              <td className="px-4 text-right whitespace-nowrap space-x-2">
                {j.status === "DRAFT" && <button className={BTN_SECONDARY} onClick={async () => { if (await ask({ title: "Post this journal?", message: "It goes into the accounts. Afterwards it can only be voided.", action: "Post" })) act.mutate({ id: j.id, what: "post" }); }}>Post</button>}
                {j.status !== "VOIDED" && <button className={BTN_DANGER} onClick={async () => { if (await ask({ title: `${j.status === "DRAFT" ? "Delete" : "Void"} this journal?`, action: j.status === "DRAFT" ? "Delete" : "Void", danger: true })) act.mutate({ id: j.id, what: "void" }); }}>{j.status === "DRAFT" ? "Delete" : "Void"}</button>}
              </td>
            </tr>
          ))}
          {journals.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-gray-400">No manual journals</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
