"use client";

import { use, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getManualJournal, postManualJournal, voidManualJournal } from "@/lib/api";
import { ManualJournal } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import { BackLink, RecordHeader, Card, BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY, TH, TD } from "@/components/DetailParts";
import { errorOf } from "../../ledger-parts";
import JournalForm from "../journal-form";

const STATUS: Record<ManualJournal["status"], { label: string; color: string }> = {
  DRAFT: { label: "Draft", color: "bg-amber-50 text-amber-700" },
  POSTED: { label: "Posted", color: "bg-green-50 text-green-700" },
  VOIDED: { label: "Voided", color: "bg-gray-100 text-gray-500" },
};

// One manual journal on its own page (review R2): a draft is edited here; a posted one can be voided
export default function ManualJournalPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const journalId = Number(id);
  const router = useRouter();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const { data: j, isLoading } = useQuery<ManualJournal>({ queryKey: ["manual-journal", journalId], queryFn: () => getManualJournal(journalId) });
  const refresh = () => { qc.invalidateQueries({ queryKey: ["manual-journals"] }); qc.invalidateQueries({ queryKey: ["manual-journal", journalId] }); };
  const act = useMutation({
    mutationFn: (what: "post" | "void") => (what === "post" ? postManualJournal(journalId) : voidManualJournal(journalId)),
    onSuccess: (_r, what) => {
      refresh();
      // Deleting a draft removes it: back to the list
      if (what === "void" && j?.status === "DRAFT") router.push("/accounting/manual-journals");
    },
    onError: e => setError(errorOf(e)),
  });

  if (isLoading || !j) return <p className="text-gray-400 text-sm">Loading…</p>;
  const s = STATUS[j.status];

  return (
    <div className="max-w-6xl" data-testid={`journal-page-${j.id}`}>
      <BackLink href="/accounting/manual-journals" />
      <RecordHeader title={j.narration}
        badges={<div className="flex items-center gap-2 mt-1.5">
          <span data-testid="journal-status" className={`text-xs px-2 py-0.5 rounded ${s.color}`}>{s.label}</span>
          <span className="text-xs text-gray-500"><ClientDate iso={j.date} /></span>
        </div>}
        actions={editing ? undefined : <>
          {j.status === "DRAFT" && <button type="button" className={BTN_SECONDARY} onClick={() => setEditing(true)}>Edit</button>}
          {j.status === "DRAFT" && <button type="button" className={BTN_PRIMARY} disabled={act.isPending} onClick={() => act.mutate("post")}>Post</button>}
          {j.status !== "VOIDED" && (
            <button type="button" className={BTN_DANGER} disabled={act.isPending}
              onClick={() => { if (confirm(j.status === "DRAFT" ? "Delete this draft journal?" : "Void this journal? A reversing journal is posted.")) act.mutate("void"); }}>
              {j.status === "DRAFT" ? "Delete" : "Void"}
            </button>
          )}
        </>} />
      {error && <p role="alert" className="mb-4 text-sm text-red-700 bg-red-50 px-3 py-2 rounded">{error}</p>}

      {editing ? <JournalForm editing={j} onSaved={() => setEditing(false)} onCancel={() => setEditing(false)} /> : (
        <Card title="Lines" flush testId="journal-lines">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200"><tr>
              <th className={TH}>Account</th><th className={TH}>Description</th><th className={`${TH} text-right`}>Debit</th><th className={`${TH} text-right`}>Credit</th>
            </tr></thead>
            <tbody className="divide-y divide-gray-100">
              {j.lines.map((l, i) => (
                <tr key={i}>
                  <td className={`${TD} font-mono text-xs`}>{l.accountCode}</td>
                  <td className={`${TD} text-gray-600`}>{l.description || "—"}</td>
                  <td className={`${TD} text-right tabular-nums`}>{l.debit != null ? formatGBP(l.debit) : ""}</td>
                  <td className={`${TD} text-right tabular-nums`}>{l.credit != null ? formatGBP(l.credit) : ""}</td>
                </tr>
              ))}
            </tbody>
            <tfoot className="font-medium border-t border-gray-200"><tr>
              <td className={TD} colSpan={2}>Total</td>
              <td className={`${TD} text-right tabular-nums`}>{formatGBP(j.total)}</td>
              <td className={`${TD} text-right tabular-nums`}>{formatGBP(j.total)}</td>
            </tr></tfoot>
          </table>
          <p className="px-4 py-2 text-xs text-gray-400">
            Created by {j.createdBy ?? "—"} on <ClientDate iso={j.createdAt} showTime />
            {j.voidedAt && <> · voided by {j.voidedBy ?? "—"} on <ClientDate iso={j.voidedAt} showTime /></>}
          </p>
        </Card>
      )}
    </div>
  );
}
