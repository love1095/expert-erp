"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getOutbox, getOutboxAttachment, MailRequest, previewMail, sendMail } from "@/lib/api";
import { MailDraft, SentMail } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY, Card } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const INPUT = "w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm";
const STATUS: Record<string, string> = { KEPT: "In the outbox (test mode)", SENT: "Sent", FAILED: "Not sent" };

/**
 * Phase M3: E-mail a document — the recipient from the customer / supplier contacts, the subject and text from the
 * organisation's template, the PDF attached; everything can be changed before sending.
 */
export default function EmailButton({ request, label = "E-mail", docKey, onSent }: { request: MailRequest; label?: string; docKey: string; onSent?: () => void }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<MailDraft | null>(null);
  const [form, setForm] = useState({ recipient: "", cc: "", subject: "", body: "" });
  const [msg, setMsg] = useState("");
  const load = useMutation({
    mutationFn: () => previewMail(request),
    onSuccess: (d: MailDraft) => { setDraft(d); setForm({ recipient: d.to ?? "", cc: "", subject: d.subject, body: d.body }); setMsg(""); setOpen(true); },
    onError: e => { setMsg(errorOf(e)); setOpen(true); },
  });
  const send = useMutation({
    mutationFn: () => sendMail({ ...request, recipient: form.recipient, cc: form.cc, subject: form.subject, body: form.body }),
    onSuccess: (s: SentMail) => {
      qc.invalidateQueries({ queryKey: ["mail-log", docKey] });
      qc.invalidateQueries({ queryKey: ["outbox"] });
      setMsg(s.status === "FAILED" ? `Not sent: ${s.error}` : `✓ ${STATUS[s.status]} — to ${s.to}`);
      setDraft(null);
      if (s.status !== "FAILED") onSent?.();
    },
    onError: e => setMsg(errorOf(e)),
  });
  return (
    <>
      <button type="button" className={BTN_SECONDARY} disabled={load.isPending} onClick={() => load.mutate()}>{label}</button>
      {open && (
        <div className="fixed inset-0 bg-black/30 z-40 flex items-start justify-center pt-20" role="dialog" aria-label="E-mail" onClick={() => setOpen(false)}>
          <div className="bg-white rounded-xl shadow-xl w-full max-w-2xl p-5 space-y-3" onClick={e => e.stopPropagation()} data-testid="email-dialog">
            <h2 className="font-semibold">{draft ? `E-mail ${draft.docLabel}` : "E-mail"}</h2>
            {draft && (<>
              {draft.missingRecipient && !form.recipient && <p className="text-sm text-amber-800 bg-amber-50 rounded px-3 py-2">{draft.missingRecipient}</p>}
              <label className="block text-sm"><span className="text-xs text-gray-600">To</span>
                <input aria-label="To" className={INPUT} value={form.recipient} onChange={e => setForm({ ...form, recipient: e.target.value })} /></label>
              <label className="block text-sm"><span className="text-xs text-gray-600">Cc</span>
                <input aria-label="Cc" className={INPUT} value={form.cc} onChange={e => setForm({ ...form, cc: e.target.value })} /></label>
              <label className="block text-sm"><span className="text-xs text-gray-600">Subject</span>
                <input aria-label="Subject" className={INPUT} value={form.subject} onChange={e => setForm({ ...form, subject: e.target.value })} /></label>
              <label className="block text-sm"><span className="text-xs text-gray-600">Message</span>
                <textarea aria-label="Message" rows={8} className={INPUT} value={form.body} onChange={e => setForm({ ...form, body: e.target.value })} /></label>
              <p className="text-xs text-gray-500">Attached: {draft.attachmentName}</p>
            </>)}
            {msg && <p role="status" className={`text-sm ${msg.startsWith("✓") ? "text-green-700" : "text-red-700"}`}>{msg}</p>}
            <div className="flex gap-2">
              {draft && <button className={BTN_PRIMARY} disabled={send.isPending} onClick={async () => { if (await ask({ title: "Send this e-mail?", action: "Send" })) send.mutate(); }}>{send.isPending ? "Sending…" : "Send"}</button>}
              <button className={BTN_SECONDARY} onClick={() => setOpen(false)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** Phase M4: what was e-mailed about this document, when, to whom and by whom. */
export function SentEmails({ docKey }: { docKey: string }) {
  const { data = [] } = useQuery<SentMail[]>({ queryKey: ["mail-log", docKey], queryFn: () => getOutbox(docKey) });
  if (data.length === 0) return null;
  return (
    <Card title="E-mails" testId="sent-emails">
      <ul className="text-sm divide-y divide-gray-100">
        {data.map(m => (
          <li key={m.id} className="py-1.5 flex flex-wrap gap-x-3">
            <span className="text-gray-500">{new Date(m.createdAt).toLocaleString("en-GB")}</span>
            <span>{m.subject}</span>
            <span className="text-gray-600">to {m.to}</span>
            {m.createdBy && <span className="text-gray-400">by {m.createdBy}</span>}
            <span className={m.status === "FAILED" ? "text-red-700" : "text-gray-500"}>{STATUS[m.status]}{m.error ? `: ${m.error}` : ""}</span>
            {m.attachmentName && <button className="text-brand-700 hover:underline" onClick={() => openPdf(() => getOutboxAttachment(m.id), m.attachmentName!)}>PDF</button>}
          </li>
        ))}
      </ul>
    </Card>
  );
}
