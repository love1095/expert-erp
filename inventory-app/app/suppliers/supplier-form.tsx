"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createSupplier, updateSupplier, getPaymentTerms } from "@/lib/api";
import { PostalAddress, Supplier } from "@/types";
import { AddressFields, EMPTY_ADDRESS, isBlankAddress } from "@/components/AddressFields";
import NameCombobox from "@/components/NameCombobox";
import { useToast } from "@/components/ui/Toast";
import { PaymentTermDialog, WithAdd } from "@/components/QuickAdd";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const EMPTY_FORM = { name: "", status: "Active", currency: "GBP", paymentTerm: "", taxNumber: "", website: "", contactName: "", phone: "", email: "", comments: "" };
type Form = typeof EMPTY_FORM;

function toForm(s: Supplier): Form {
  const str = (v: unknown) => (v == null ? "" : String(v));
  return {
    name: s.name, status: s.status || "Active", currency: str(s.currency), paymentTerm: str(s.paymentTerm), taxNumber: str(s.taxNumber),
    website: str(s.website), contactName: str(s.contactName), phone: str(s.phone), email: str(s.email), comments: str(s.comments),
  };
}

/** A supplier's details: a new supplier (/suppliers/new) or Edit on its page (review R2, rule D1). */
export default function SupplierForm({ editing, onSaved, onCancel }: {
  editing?: Supplier;
  onSaved: (s: Supplier) => void;
  onCancel: () => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState<Form>(() => (editing ? toForm(editing) : EMPTY_FORM));
  const [addingTerm, setAddingTerm] = useState(false);
  // One address per supplier for now: copied onto new purchase orders as the vendor address
  const [address, setAddress] = useState<PostalAddress>({ ...EMPTY_ADDRESS, ...(editing?.address ?? {}) });
  const [error, setError] = useState("");
  // Like Cin7: terms are picked from a list, each with what it means for the due date
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });
  const set = (k: keyof Form, v: string) => setForm(f => ({ ...f, [k]: v }));

  const save = useMutation({
    // Fields the form does not show (discount) are sent back unchanged
    mutationFn: (data: Form) => {
      const body = { ...data, address: isBlankAddress(address) ? null : address };
      return editing ? updateSupplier(editing.id, { ...editing, ...body }) : createSupplier(body);
    },
    onSuccess: (saved: Supplier) => {
      toast(editing ? "Supplier saved" : "Supplier created");
      qc.invalidateQueries({ queryKey: ["suppliers-page"] });
      qc.invalidateQueries({ queryKey: ["suppliers"] });
      qc.invalidateQueries({ queryKey: ["payment-terms"] });
      qc.invalidateQueries({ queryKey: ["supplier", saved?.id ?? editing?.id] });
      onSaved(saved);
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to save supplier"),
  });

  const field = (id: keyof Form, label: string, opts: { placeholder?: string; type?: string; wide?: boolean } = {}) => (
    <div className={opts.wide ? "col-span-2" : undefined}>
      <label htmlFor={`suppliers-${id}`} className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
      <input id={`suppliers-${id}`} type={opts.type} value={form[id]} onChange={e => set(id, e.target.value)} placeholder={opts.placeholder} className={INPUT} />
    </div>
  );

  return (
    <form onSubmit={e => { e.preventDefault(); save.mutate(form); }} data-testid="supplier-form"
      className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="grid grid-cols-3 gap-4">
        <div className="col-span-2">
          <label htmlFor="suppliers-name" className="block text-xs font-medium text-gray-600 mb-1">Name *</label>
          <input id="suppliers-name" value={form.name} onChange={e => set("name", e.target.value)} required placeholder="Supplier name" className={INPUT} />
        </div>
        <div>
          <label htmlFor="suppliers-status" className="block text-xs font-medium text-gray-600 mb-1">Status</label>
          <select id="suppliers-status" value={form.status} onChange={e => set("status", e.target.value)} className={INPUT}>
            <option>Active</option>
            <option>Inactive</option>
          </select>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4">
        {field("currency", "Currency", { placeholder: "GBP" })}
        <div>
          <label htmlFor="suppliers-paymentTerm" className="block text-xs font-medium text-gray-600 mb-1">Payment Term</label>
          <WithAdd label="New term" onAdd={() => setAddingTerm(true)}>
            <NameCombobox id="suppliers-paymentTerm" value={form.paymentTerm} onChange={v => set("paymentTerm", v)} listLabel="Payment terms"
              placeholder="e.g. 30 Day EOM" className={INPUT} options={terms.map(t => ({ key: t.name, name: t.name, sub: t.meaning }))} />
          </WithAdd>
        </div>
        {field("taxNumber", "Tax Number", { placeholder: "VAT number" })}
      </div>

      <div className="grid grid-cols-3 gap-4">
        {field("contactName", "Contact Name")}
        {field("phone", "Phone")}
        {field("email", "Email", { type: "email" })}
        {field("website", "Website", { placeholder: "https://" })}
      </div>

      <div>
        <p className="block text-xs font-medium text-gray-600 mb-1">Address</p>
        <AddressFields label="Supplier address" value={address} onChange={setAddress} inputClass={INPUT} />
      </div>

      <div>
        <label htmlFor="suppliers-comments" className="block text-xs font-medium text-gray-600 mb-1">Comments</label>
        <textarea id="suppliers-comments" rows={2} value={form.comments} onChange={e => set("comments", e.target.value)} className={INPUT} />
      </div>

      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>
        <button type="submit" disabled={save.isPending} aria-busy={save.isPending}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {save.isPending ? "Saving..." : editing ? "Save Supplier" : "Create Supplier"}
        </button>
      </div>
      {addingTerm && <PaymentTermDialog onClose={() => setAddingTerm(false)} onAdded={name => { set("paymentTerm", name); setAddingTerm(false); }} />}
    </form>
  );
}
