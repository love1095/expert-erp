"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteDocumentTemplate, getDocumentTemplates, saveDocumentTemplate } from "@/lib/api";
import { DocType, DocumentTemplate } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";

const TYPES: { group: string; type: DocType; label: string; title: string }[] = [
  { group: "Sale", type: "QUOTE", label: "Quote", title: "Quote" },
  { group: "Sale", type: "SALES_ORDER", label: "Sales order (also delivery notes and pick lists)", title: "Sales order" },
  { group: "Sale", type: "INVOICE", label: "Invoice", title: "Invoice" },
  { group: "Sale", type: "CREDIT_NOTE", label: "Credit note", title: "Credit note" },
  { group: "Purchase", type: "PURCHASE_ORDER", label: "Purchase order", title: "Purchase order" },
];
const LABEL = "block text-xs font-medium text-gray-600 mb-1";
const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm disabled:bg-gray-50";

const blank = (type: DocType): DocumentTemplate => ({
  id: null, docType: type, name: "", isDefault: false, title: null, accentColor: "#d42020", showLogo: true, showPrices: true,
  showSku: true, showDiscount: true, showVatSummary: true, introText: null, paymentText: null, footerText: null, emailSubject: null,
});

function errorText(e: unknown, fallback: string) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;
}

/**
 * Phase I4: named templates per document type, like Cin7 Core's Document and email templates (e.g. "Invoice - Cash
 * Sale", "Delivery Note"): one default per type, chosen when printing. Cin7 takes Word mail-merge files; here a
 * template is a set of layout settings on the ERP's own layout.
 */
export default function TemplatesPage() {
  const { user, can } = useAuth();
  // Q5: admins, or a role with Settings
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN" || can("SETTINGS");
  const qc = useQueryClient();
  const { data: templates = [] } = useQuery<DocumentTemplate[]>({ queryKey: ["document-templates"], queryFn: getDocumentTemplates });
  const [group, setGroup] = useState("Sale");
  const [form, setForm] = useState<DocumentTemplate | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const done = (text: string) => { qc.invalidateQueries({ queryKey: ["document-templates"] }); setForm(null); setMessage({ ok: true, text }); };
  const save = useMutation({
    mutationFn: () => saveDocumentTemplate(form!),
    onSuccess: () => done("Saved"),
    onError: e => setMessage({ ok: false, text: errorText(e, "Could not save the template") }),
  });
  const remove = useMutation({
    mutationFn: (id: number) => deleteDocumentTemplate(id),
    onSuccess: () => done("Template removed"),
    onError: e => setMessage({ ok: false, text: errorText(e, "Could not remove the template") }),
  });
  const set = (patch: Partial<DocumentTemplate>) => setForm(f => ({ ...f!, ...patch }));
  const check = (key: keyof DocumentTemplate, label: string) => (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" disabled={!isAdmin} checked={Boolean(form![key])} onChange={e => set({ [key]: e.target.checked })} /> {label}
    </label>
  );

  return (
    <div data-testid="document-templates">
      <PageHeader title="Document templates" subtitle="How printed quotes, orders, invoices, credit notes and purchase orders look, like Cin7's templates" />
      {message && <p role={message.ok ? "status" : "alert"} className={`mb-3 text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.text}</p>}
      <div className="grid md:grid-cols-[12rem_1fr] gap-4">
        <nav className="bg-white border border-gray-200 rounded-xl p-2 self-start">
          {["Sale", "Purchase"].map(g => (
            <button key={g} type="button" onClick={() => { setGroup(g); setForm(null); }}
              className={`block w-full text-left px-3 py-2 rounded-md text-sm ${group === g ? "bg-brand-50 font-medium" : "hover:bg-gray-50"}`}>{g}</button>
          ))}
        </nav>
        <div className="space-y-4">
          {TYPES.filter(t => t.group === group).map(t => {
            const list = templates.filter(x => x.docType === t.type);
            return (
              <section key={t.type} className="bg-white border border-gray-200 rounded-xl p-4" data-testid={`templates-${t.type}`}>
                <div className="flex items-center justify-between mb-2">
                  <h2 className="font-semibold text-gray-800">{t.label}</h2>
                  {isAdmin && <button type="button" className={BTN_SECONDARY} onClick={() => { setMessage(null); setForm(blank(t.type)); }}>+ New template</button>}
                </div>
                {list.length === 0 && <p className="text-sm text-gray-500">The standard layout. Add a template to change it.</p>}
                <ul className="divide-y divide-gray-100">
                  {list.map(x => (
                    <li key={x.id} className="flex items-center justify-between py-2">
                      <span className="text-sm">
                        <span className="inline-block w-3 h-3 rounded-sm mr-2 align-middle" style={{ background: x.accentColor }} />
                        <b>{x.name}</b>{x.isDefault && <span className="ml-2 text-xs text-emerald-700 font-semibold">Default</span>}
                        {!x.showPrices && <span className="ml-2 text-xs text-gray-500">No prices</span>}
                      </span>
                      <span className="flex gap-2">
                        <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => { setMessage(null); setForm(x); }}>{isAdmin ? "Edit" : "View"}</button>
                        {isAdmin && <button type="button" className="text-sm text-red-600 hover:underline"
                          onClick={() => { if (confirm(`Remove the template ${x.name}?`)) remove.mutate(x.id!); }}>Remove</button>}
                      </span>
                    </li>
                  ))}
                </ul>
                {form && form.docType === t.type && (
                  <form className="mt-4 border-t border-gray-200 pt-4 grid md:grid-cols-2 gap-4" data-testid="template-form"
                    onSubmit={e => { e.preventDefault(); setMessage(null); save.mutate(); }}>
                    <div>
                      <label htmlFor="tpl-name" className={LABEL}>Template name</label>
                      <input id="tpl-name" className={INPUT} disabled={!isAdmin} maxLength={80} value={form.name} onChange={e => set({ name: e.target.value })} />
                    </div>
                    <div>
                      <label htmlFor="tpl-title" className={LABEL}>Title printed</label>
                      <input id="tpl-title" className={INPUT} disabled={!isAdmin} maxLength={60} placeholder={t.title}
                        value={form.title ?? ""} onChange={e => set({ title: e.target.value || null })} />
                    </div>
                    <div>
                      <label htmlFor="tpl-colour" className={LABEL}>Accent colour</label>
                      <input id="tpl-colour" type="color" disabled={!isAdmin} value={form.accentColor} onChange={e => set({ accentColor: e.target.value })} />
                    </div>
                    <div className="space-y-1">
                      {check("isDefault", "Default for this document")}
                      {check("showLogo", "Show the logo")}
                      {check("showPrices", "Show prices and totals (untick for a delivery note or pick list)")}
                      {check("showSku", "Show the SKU column")}
                      {check("showDiscount", "Show discounts")}
                      {check("showVatSummary", "Show the VAT summary")}
                    </div>
                    {([["introText", "Text above the lines"], ["paymentText", "Payment text (instead of the usual bank details)"],
                      ["footerText", "Footer (instead of the company footer)"]] as [keyof DocumentTemplate, string][]).map(([k, label]) => (
                      <div key={k} className="md:col-span-2">
                        <label htmlFor={`tpl-${k}`} className={LABEL}>{label}</label>
                        <textarea id={`tpl-${k}`} rows={2} maxLength={2000} className={INPUT} disabled={!isAdmin}
                          value={(form[k] as string | null) ?? ""} onChange={e => set({ [k]: e.target.value || null })} />
                      </div>
                    ))}
                    <div className="md:col-span-2">
                      <label htmlFor="tpl-subject" className={LABEL}>E-mail subject (when e-mailing comes)</label>
                      <input id="tpl-subject" className={INPUT} disabled={!isAdmin} maxLength={200} value={form.emailSubject ?? ""}
                        onChange={e => set({ emailSubject: e.target.value || null })} />
                    </div>
                    <div className="md:col-span-2 flex gap-2">
                      {isAdmin && <button type="submit" className={BTN_PRIMARY} disabled={save.isPending || !form.name.trim()}>Save</button>}
                      <button type="button" className={isAdmin ? BTN_DANGER : BTN_SECONDARY} onClick={() => setForm(null)}>{isAdmin ? "Cancel" : "Close"}</button>
                    </div>
                  </form>
                )}
              </section>
            );
          })}
        </div>
      </div>
    </div>
  );
}
