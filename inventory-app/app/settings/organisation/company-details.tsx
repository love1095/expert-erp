"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteCompanyLogo, getCompanyDetails, getCompanyLogo, updateCompanyDetails, uploadCompanyLogo } from "@/lib/api";
import { CompanyDetails } from "@/types";
import { useConfirm } from "@/components/ConfirmDialog";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400 disabled:bg-gray-50";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

type Text = Exclude<keyof CompanyDetails, "hasLogo">;
const FIELDS: { key: Text; label: string; rows?: number; max: number; hint?: string; span?: boolean }[] = [
  { key: "legalName", label: "Company name on documents", max: 255, hint: "Empty = the organisation's name" },
  { key: "vatNumber", label: "VAT number", max: 50 },
  { key: "address", label: "Address", rows: 4, max: 1000, hint: "One line per row" },
  { key: "bankDetails", label: "Bank details (printed on invoices)", rows: 4, max: 1000, hint: "Account name, sort code, account number" },
  { key: "phone", label: "Phone", max: 100 },
  { key: "companyNumber", label: "Company number", max: 50 },
  { key: "email", label: "E-mail", max: 255 },
  { key: "website", label: "Website", max: 255 },
  { key: "documentFooter", label: "Footer on every document", rows: 2, max: 2000, hint: "e.g. terms of sale", span: true },
];

/** The company details printed on quotes, orders, invoices, credit notes and purchase orders (D5). */
export default function CompanyDetailsCard({ isAdmin }: { isAdmin: boolean }) {
  const { data } = useQuery<CompanyDetails>({ queryKey: ["company-details"], queryFn: getCompanyDetails });
  if (!data) return null;
  return <DetailsForm initial={data} isAdmin={isAdmin} />;
}

function DetailsForm({ initial, isAdmin }: { initial: CompanyDetails; isAdmin: boolean }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [form, setForm] = useState<CompanyDetails>(initial);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  // The logo needs the login token: fetched, then shown from memory
  const hasLogo = form.hasLogo;
  const { data: logo } = useQuery<Blob>({ queryKey: ["company-logo"], queryFn: getCompanyLogo, enabled: hasLogo });
  const logoUrl = useMemo(() => (hasLogo && logo ? URL.createObjectURL(logo) : null), [hasLogo, logo]);
  useEffect(() => () => { if (logoUrl) URL.revokeObjectURL(logoUrl); }, [logoUrl]);

  // A logo change keeps what is being typed in the fields
  const done = (d: CompanyDetails, text: string, logoOnly = false) => {
    qc.setQueryData(["company-details"], d);
    qc.invalidateQueries({ queryKey: ["company-logo"] });
    setForm(f => (logoOnly ? { ...f, hasLogo: d.hasLogo } : d));
    setMessage({ ok: true, text });
  };
  const failed = (e: unknown) => setMessage({ ok: false, text: (e as { response?: { data?: { error?: string } } }).response?.data?.error || "Could not save" });
  const save = useMutation({ mutationFn: updateCompanyDetails, onSuccess: (d: CompanyDetails) => done(d, "Saved"), onError: failed });
  const upload = useMutation({ mutationFn: uploadCompanyLogo, onSuccess: (d: CompanyDetails) => done(d, "Logo saved", true), onError: failed });
  const removeLogo = useMutation({ mutationFn: deleteCompanyLogo, onSuccess: (d: CompanyDetails) => done(d, "Logo removed", true), onError: failed });

  return (
    <form onSubmit={e => { e.preventDefault(); setMessage(null); save.mutate(form); }}
      className="bg-white border border-gray-200 rounded-xl p-6 grid grid-cols-2 gap-4" data-testid="company-details">
      <div className="col-span-2">
        <h2 className="font-semibold text-gray-800">Company details on documents</h2>
        <p className="text-xs text-gray-500 mt-0.5">Printed on quotes, sales orders, invoices, credit notes and purchase orders. Empty fields are left out.</p>
      </div>
      {FIELDS.map(f => (
        <div key={f.key} className={f.span ? "col-span-2" : undefined}>
          <label htmlFor={`company-${f.key}`} className={LABEL}>{f.label}</label>
          {f.rows ? (
            <textarea id={`company-${f.key}`} rows={f.rows} maxLength={f.max} disabled={!isAdmin} value={form[f.key] ?? ""}
              onChange={e => setForm({ ...form, [f.key]: e.target.value })} className={INPUT} />
          ) : (
            <input id={`company-${f.key}`} maxLength={f.max} disabled={!isAdmin} value={form[f.key] ?? ""}
              type={f.key === "email" ? "email" : "text"}
              onChange={e => setForm({ ...form, [f.key]: e.target.value })} className={INPUT} />
          )}
          {f.hint && <p className="text-[11px] text-gray-400 mt-1">{f.hint}</p>}
        </div>
      ))}
      <div className="col-span-2">
        <span className={LABEL}>Logo</span>
        <div className="flex items-center gap-3">
          {logoUrl
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={logoUrl} alt="Company logo" data-testid="company-logo" className="max-h-12 max-w-48 border border-gray-200 rounded p-1" />
            : <span className="text-xs text-gray-400">No logo</span>}
          {isAdmin && <>
            <label className="text-sm px-3 py-1.5 rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 cursor-pointer">
              {hasLogo ? "Change logo" : "Upload logo"}
              <input type="file" accept="image/png,image/jpeg" className="sr-only" aria-label="Logo file"
                onChange={e => { const file = e.target.files?.[0]; if (file) { setMessage(null); upload.mutate(file); } e.target.value = ""; }} />
            </label>
            {hasLogo && <button type="button" onClick={async () => { if (!(await ask({ title: "Remove the logo?", action: "Remove", danger: true }))) return; setMessage(null); removeLogo.mutate(); }}
              className="text-sm text-red-600 hover:underline">Remove</button>}
          </>}
          <span className="text-[11px] text-gray-400">PNG or JPEG, up to 512 KB</span>
        </div>
      </div>
      <div className="col-span-2 flex items-center gap-3">
        {isAdmin ? (
          <button type="submit" disabled={save.isPending}
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {save.isPending ? "Saving..." : "Save company details"}
          </button>
        ) : <p className="text-xs text-gray-400">Only an admin can change these details.</p>}
        {message && <span role="status" className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.ok ? "✓ " : "✕ "}{message.text}</span>}
      </div>
    </form>
  );
}
