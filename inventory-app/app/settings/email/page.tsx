"use client";

import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMailSettings, getMailTemplates, getOutbox, getOutboxAttachment, resetMailTemplate, saveMailSettings, saveMailTemplate } from "@/lib/api";
import { MailSettings, MailTemplate, SentMail } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { openPdf } from "@/components/PrintButton";
import { useConfirm } from "@/components/ConfirmDialog";

const errorOf = (e: unknown, f = "Something went wrong") => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? f;
const INPUT = "w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm disabled:bg-gray-50";
const NAMES: Record<string, string> = { QUOTE: "Quote", SALES_ORDER: "Sales order", INVOICE: "Invoice", CREDIT_NOTE: "Credit note", STATEMENT: "Statement",
  REMINDER_1: "1st reminder", REMINDER_2: "2nd reminder", REMINDER_3: "Final notice", PURCHASE_ORDER: "Purchase order", TRACKING: "Tracking (shipped)" };
const STATUS: Record<string, string> = { KEPT: "Kept (test mode)", SENT: "Sent", FAILED: "Not sent" };

/** Phase M: Settings → E-mail — the sender and SMTP server, the e-mail wording per document, and the outbox. */
export default function EmailSettingsPage() {
  const [tab, setTab] = useState<"outbox" | "templates" | "settings">("outbox");
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="E-mail" />
      <div className="flex gap-5 border-b border-gray-200 text-sm" role="tablist">
        {([["outbox", "Outbox"], ["templates", "Templates"], ["settings", "Settings"]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`pb-2 -mb-px ${tab === k ? "border-b-2 border-brand-600 text-brand-700 font-medium" : "text-gray-500"}`}>{l}</button>
        ))}
      </div>
      {tab === "outbox" && <Outbox />}
      {tab === "templates" && <Templates />}
      {tab === "settings" && <Settings />}
    </div>
  );
}

function Outbox() {
  const { data = [] } = useQuery<SentMail[]>({ queryKey: ["outbox"], queryFn: () => getOutbox() });
  const [open, setOpen] = useState<number | null>(null);
  return (
    <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="outbox">
      <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-4 py-2">When</th><th>About</th><th>To</th><th>Subject</th><th>Status</th><th className="px-4" /></tr></thead>
      <tbody className="divide-y divide-gray-100">
        {data.map(m => (<Fragment key={m.id}>
          <tr className="hover:bg-gray-50 cursor-pointer" onClick={() => setOpen(open === m.id ? null : m.id)}>
            <td className="px-4 py-1.5 whitespace-nowrap">{new Date(m.createdAt).toLocaleString("en-GB")}</td>
            <td>{m.docLabel}</td><td>{m.to}</td><td>{m.subject}</td>
            <td className={m.status === "FAILED" ? "text-red-700" : ""}>{STATUS[m.status]}</td>
            <td className="px-4 text-right">{m.attachmentName && <button className="text-brand-700 hover:underline"
              onClick={e => { e.stopPropagation(); openPdf(() => getOutboxAttachment(m.id), m.attachmentName!); }}>PDF</button>}</td>
          </tr>
          {open === m.id && <tr><td colSpan={6} className="px-4 py-2 bg-gray-50">
            <pre className="whitespace-pre-wrap text-sm font-sans">{m.body}</pre>
            {m.error && <p className="text-xs text-red-700 mt-1">{m.error}</p>}
            <p className="text-xs text-gray-400 mt-1">By {m.createdBy}{m.cc && ` · cc ${m.cc}`}</p></td></tr>}
        </Fragment>))}
        {data.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-gray-400">No e-mails yet</td></tr>}
      </tbody>
    </table>
  );
}

function Templates() {
  const { user, can } = useAuth();
  // Q5: admins, or a role with Settings
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || can("SETTINGS");
  const qc = useQueryClient();
  const { data = [] } = useQuery<MailTemplate[]>({ queryKey: ["mail-templates"], queryFn: getMailTemplates });
  const [type, setType] = useState("INVOICE");
  const t = data.find(x => x.docType === type);
  return (
    <div className="grid grid-cols-[14rem_1fr] gap-4">
      <ul className="bg-white border border-gray-200 rounded-xl py-2 text-sm self-start">
        {data.map(x => (
          <li key={x.docType}><button onClick={() => setType(x.docType)}
            className={`w-full text-left px-4 py-1.5 ${type === x.docType ? "bg-brand-50 text-brand-700 font-medium" : "hover:bg-gray-50"}`}>
            {NAMES[x.docType]}{x.custom && <span className="text-xs text-gray-400"> · own</span>}</button></li>
        ))}
      </ul>
      {t && <TemplateForm key={`${t.docType}-${t.subject}-${t.body}`} t={t} disabled={!isAdmin} onDone={() => qc.invalidateQueries({ queryKey: ["mail-templates"] })} />}
    </div>
  );
}

function TemplateForm({ t, disabled, onDone }: { t: MailTemplate; disabled: boolean; onDone: () => void }) {
  const ask = useConfirm();
  const [subject, setSubject] = useState(t.subject);
  const [body, setBody] = useState(t.body);
  const [msg, setMsg] = useState("");
  const save = useMutation({ mutationFn: () => saveMailTemplate(t.docType, subject, body), onSuccess: () => { setMsg("Saved"); onDone(); }, onError: e => setMsg(errorOf(e)) });
  const reset = useMutation({ mutationFn: () => resetMailTemplate(t.docType), onSuccess: () => { setMsg("Back to the standard wording"); onDone(); }, onError: e => setMsg(errorOf(e)) });
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3" data-testid="mail-template">
      <label className="block text-sm"><span className="text-xs text-gray-600">Subject</span>
        <input aria-label="Template subject" className={INPUT} disabled={disabled} value={subject} onChange={e => setSubject(e.target.value)} /></label>
      <label className="block text-sm"><span className="text-xs text-gray-600">Message</span>
        <textarea aria-label="Template message" rows={10} className={INPUT} disabled={disabled} value={body} onChange={e => setBody(e.target.value)} /></label>
      <p className="text-xs text-gray-500">Placeholders: {"{{customer}} {{supplier}} {{number}} {{amount}} {{amount_due}} {{due_date}} {{date}} {{reference}} {{order_number}} {{invoices}} {{balance}} {{company}} {{carrier}} {{tracking}} {{tracking_url}}"}</p>
      {!disabled && <div className="flex items-center gap-2">
        <button className={BTN_PRIMARY} onClick={() => save.mutate()}>Save</button>
        {t.custom && <button className={BTN_SECONDARY} onClick={async () => { if (await ask({ title: "Go back to the standard wording?", message: "Your own wording for this e-mail is removed.", action: "Use standard", danger: true })) reset.mutate(); }}>Use the standard wording</button>}
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}
      </div>}
    </section>
  );
}

function Settings() {
  const { data } = useQuery<MailSettings>({ queryKey: ["mail-settings"], queryFn: getMailSettings });
  if (!data) return <p className="text-sm text-gray-500">Loading…</p>;
  return <SettingsForm initial={data} />;
}

function SettingsForm({ initial }: { initial: MailSettings }) {
  const { user, can } = useAuth();
  // Q5: admins, or a role with Settings
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || can("SETTINGS");
  const qc = useQueryClient();
  const [s, setS] = useState({ fromName: initial.fromName ?? "", fromAddress: initial.fromAddress ?? "", replyTo: initial.replyTo ?? "",
    smtpHost: initial.smtpHost ?? "", smtpPort: initial.smtpPort ? String(initial.smtpPort) : "587", smtpUsername: initial.smtpUsername ?? "", smtpPassword: "",
    smtpStartTls: initial.smtpStartTls });
  const [msg, setMsg] = useState("");
  const save = useMutation({
    mutationFn: () => saveMailSettings({ ...s, smtpPort: s.smtpPort ? Number(s.smtpPort) : null }),
    onSuccess: () => { setMsg("Saved"); setS({ ...s, smtpPassword: "" }); qc.invalidateQueries({ queryKey: ["mail-settings"] }); },
    onError: e => setMsg(errorOf(e)),
  });
  const field = (k: keyof typeof s, label: string, type = "text") => (
    <label className="block text-sm"><span className="text-xs text-gray-600">{label}</span>
      <input aria-label={label} type={type} className={INPUT} disabled={!isAdmin} value={String(s[k])} onChange={e => setS({ ...s, [k]: e.target.value })} /></label>
  );
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-3 max-w-2xl" data-testid="mail-settings">
      <p className={`text-sm px-3 py-2 rounded ${initial.mode === "fake" ? "bg-amber-50 text-amber-800" : "bg-green-50 text-green-700"}`}>
        {initial.mode === "fake" ? "Test mode: e-mails are kept in the outbox, not sent." : "Live: e-mails are sent by the SMTP server below."}</p>
      {field("fromName", "Sender name")}
      {field("fromAddress", "Sender address")}
      {field("replyTo", "Reply-to address")}
      <h3 className="font-medium pt-2">SMTP server</h3>
      <div className="grid grid-cols-[1fr_8rem] gap-3">{field("smtpHost", "SMTP host")}{field("smtpPort", "Port")}</div>
      {field("smtpUsername", "SMTP username")}
      {field("smtpPassword", initial.smtpPasswordSet ? "SMTP password (kept; type to replace)" : "SMTP password", "password")}
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!isAdmin} checked={s.smtpStartTls} onChange={e => setS({ ...s, smtpStartTls: e.target.checked })} /> STARTTLS</label>
      {isAdmin && <div className="flex items-center gap-2"><button className={BTN_PRIMARY} onClick={() => save.mutate()}>Save</button>
        {msg && <span role="status" className="text-sm text-gray-600">{msg}</span>}</div>}
    </section>
  );
}
