"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createCustomer, getPriceTiers, getSalesReps, updateCustomer, getPaymentTerms } from "@/lib/api";
import NameCombobox from "@/components/NameCombobox";
import { AddressType, Customer, CustomerAddress, CustomerContact, PriceTier } from "@/types";
import { ADDRESS_TYPES, AddressFields, EMPTY_ADDRESS } from "@/components/AddressFields";

const EMPTY_CONTACT: CustomerContact = {
  name: "", jobTitle: "", phone: "", mobilePhone: "", email: "",
  defaultContact: false, includeInEmail: false, marketingConsent: "UNKNOWN",
};

const EMPTY_FORM = {
  name: "", status: "ACTIVE" as Customer["status"], currency: "GBP", paymentTerm: "", priceTier: "",
  creditLimit: "", taxRule: "", taxNumber: "", carrier: "", salesRepresentative: "", location: "",
  tags: "", notes: "",
};
type Form = typeof EMPTY_FORM;

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const SMALL_INPUT = "w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400";

function toForm(c: Customer): Form {
  return {
    name: c.name, status: c.status, currency: c.currency ?? "", paymentTerm: c.paymentTerm ?? "",
    priceTier: c.priceTier ?? "", creditLimit: c.creditLimit != null ? String(c.creditLimit) : "",
    taxRule: c.taxRule ?? "", taxNumber: c.taxNumber ?? "", carrier: c.carrier ?? "",
    salesRepresentative: c.salesRepresentative ?? "", location: c.location ?? "", tags: c.tags ?? "",
    notes: c.notes ?? "",
  };
}

/** The customer's details, contacts and addresses: a new customer (/customers/new) or Edit on its page (review R2, rule D1). */
export default function CustomerForm({ editing, onSaved, onCancel }: {
  editing?: Customer;
  onSaved: (c: Customer) => void;
  onCancel: () => void;
}) {
  const qc = useQueryClient();
  const { data: tiers = [] } = useQuery<PriceTier[]>({ queryKey: ["price-tiers"], queryFn: getPriceTiers });
  // Like Cin7: terms are picked from a list, each with what it means for the due date
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });
  // Like Cin7: the sales rep is picked from a list (names used on customers and orders)
  const { data: salesReps = [] } = useQuery<string[]>({ queryKey: ["sales-reps"], queryFn: getSalesReps });
  const [form, setForm] = useState<Form>(() => (editing ? toForm(editing) : EMPTY_FORM));
  const [contacts, setContacts] = useState<CustomerContact[]>(() =>
    editing ? editing.contacts.map(x => ({ ...x })) : [{ ...EMPTY_CONTACT, defaultContact: true }]);
  // G4: the address book, like Cin7's Addresses tab (one default per type)
  const [addresses, setAddresses] = useState<CustomerAddress[]>(() =>
    editing ? (editing.addresses ?? []).map(x => ({ ...x })) : [{ ...EMPTY_ADDRESS, type: "BILLING", defaultForType: true }]);
  const setAddress = (i: number, patch: Partial<CustomerAddress>) =>
    setAddresses(as => as.map((a, j) => {
      if (j === i) return { ...a, ...patch };
      // Ticking a default (or moving to a type) leaves one default of that type
      const type = patch.type ?? as[i].type;
      return (patch.defaultForType || (patch.type && as[i].defaultForType)) && a.type === type ? { ...a, defaultForType: false } : a;
    }));
  const [error, setError] = useState("");
  const set = (k: keyof Form, v: string) => setForm(f => ({ ...f, [k]: v }));
  const setContact = (i: number, patch: Partial<CustomerContact>) =>
    setContacts(cs => cs.map((c, j) => (j === i ? { ...c, ...patch } : patch.defaultContact ? { ...c, defaultContact: false } : c)));

  const save = useMutation({
    mutationFn: (body: unknown) => (editing ? updateCustomer({ id: editing.id, data: body }) : createCustomer(body)),
    onSuccess: (saved: Customer) => {
      qc.invalidateQueries({ queryKey: ["customers-page"] });
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["sales-reps"] });
      qc.invalidateQueries({ queryKey: ["payment-terms"] });
      qc.invalidateQueries({ queryKey: ["customer", saved?.id ?? editing?.id] });
      onSaved(saved);
    },
    onError: (e: any) => {
      const fields = e.response?.data?.fields;
      setError(fields ? Object.values(fields).join(", ") : e.response?.data?.error || "Failed to save customer");
    },
  });
  const saving = save.isPending;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    save.mutate({ ...form, creditLimit: form.creditLimit === "" ? null : Number(form.creditLimit), contacts, addresses });
  };

  return (
    <form onSubmit={handleSubmit} data-testid="customer-form" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
      {error && <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

      <div className="grid grid-cols-4 gap-4">
        <div className="col-span-3">
          <label htmlFor="customers-name" className="block text-xs font-medium text-gray-600 mb-1">Name *</label>
          <input id="customers-name" value={form.name} onChange={e => set("name", e.target.value)} required placeholder="Customer or company name" className={INPUT} />
        </div>
        <div>
          <label htmlFor="customers-status" className="block text-xs font-medium text-gray-600 mb-1">Status</label>
          <select id="customers-status" value={form.status} onChange={e => set("status", e.target.value)} className={INPUT}>
            <option value="ACTIVE">Active</option>
            <option value="DEPRECATED">Deprecated</option>
          </select>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <div>
          <label htmlFor="customers-payment-term" className="block text-xs font-medium text-gray-600 mb-1">Payment Term</label>
          <NameCombobox id="customers-payment-term" value={form.paymentTerm} onChange={v => set("paymentTerm", v)} listLabel="Payment terms"
            placeholder="e.g. 30 Day EOM" className={INPUT} options={terms.map(t => ({ key: t.name, name: t.name, sub: t.meaning }))} />
        </div>
        <div>
          <label htmlFor="customers-price-tier" className="block text-xs font-medium text-gray-600 mb-1">Price Tier</label>
          <select id="customers-price-tier" value={form.priceTier} onChange={e => set("priceTier", e.target.value)} className={INPUT}>
            <option value="">{tiers[0]?.name ?? "Retail"} (default)</option>
            {tiers.filter(t => t.number !== 1).map(t => <option key={t.number} value={t.name}>{t.name}</option>)}
            {/* A name from Cin7 that is not a tier here: keep it visible */}
            {form.priceTier && !tiers.some(t => t.name.toLowerCase() === form.priceTier.toLowerCase()) && <option value={form.priceTier}>{form.priceTier} (not a tier)</option>}
            {tiers[0] && form.priceTier.toLowerCase() === tiers[0].name.toLowerCase() && <option value={form.priceTier}>{form.priceTier}</option>}
          </select>
        </div>
        <Field id="customers-credit-limit" label="Credit Limit (£)" value={form.creditLimit} onChange={v => set("creditLimit", v)} type="number" />
        <Field id="customers-currency" label="Currency" value={form.currency} onChange={v => set("currency", v)} placeholder="GBP" />
        <Field id="customers-tax-rule" label="Tax Rule" value={form.taxRule} onChange={v => set("taxRule", v)} placeholder="e.g. 20% (VAT on Income)" />
        <Field id="customers-tax-number" label="Tax Number" value={form.taxNumber} onChange={v => set("taxNumber", v)} placeholder="VAT number" />
        <Field id="customers-carrier" label="Carrier" value={form.carrier} onChange={v => set("carrier", v)} />
        <div>
          <label htmlFor="customers-sales-rep" className="block text-xs font-medium text-gray-600 mb-1">Sales Representative</label>
          <NameCombobox id="customers-sales-rep" value={form.salesRepresentative} onChange={v => set("salesRepresentative", v)} listLabel="Sales reps"
            placeholder="Pick or type a name" className={INPUT} options={salesReps.map(r => ({ key: r, name: r }))} />
        </div>
        <Field id="customers-location" label="Location" value={form.location} onChange={v => set("location", v)} />
        <div className="col-span-3">
          <Field id="customers-tags" label="Tags" value={form.tags} onChange={v => set("tags", v)} placeholder="Comma-separated" />
        </div>
      </div>

      <div>
        <label htmlFor="customers-notes" className="block text-xs font-medium text-gray-600 mb-1">Comments</label>
        <textarea id="customers-notes" rows={2} value={form.notes} onChange={e => set("notes", e.target.value)} className={INPUT} />
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Addresses</span>
          <button type="button" onClick={() => {
            const type: AddressType = addresses.some(a => a.type === "BILLING") ? "SHIPPING" : "BILLING";
            setAddresses([...addresses, { ...EMPTY_ADDRESS, type, defaultForType: !addresses.some(a => a.type === type) }]);
          }} className="text-xs text-brand-600 hover:text-brand-800">+ Add address</button>
        </div>
        {addresses.length === 0 && <p className="text-xs text-gray-400">No addresses</p>}
        <div className="grid md:grid-cols-2 gap-3">
          {addresses.map((a, i) => (
            <div key={i} className="border border-gray-200 rounded-md p-3 space-y-2" data-testid={`address-row-${i}`}>
              <div className="flex items-center gap-3 text-xs">
                <select aria-label={`Address ${i + 1} type`} value={a.type} onChange={e => setAddress(i, { type: e.target.value as AddressType })}
                  className={`${SMALL_INPUT} w-32`}>
                  {ADDRESS_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                <label className="flex items-center gap-1 text-gray-600">
                  <input type="checkbox" aria-label={`Address ${i + 1} default for type`} checked={a.defaultForType}
                    onChange={e => setAddress(i, { defaultForType: e.target.checked })} />
                  Default for type
                </label>
                <button type="button" onClick={() => setAddresses(addresses.filter((_, j) => j !== i))}
                  className="ml-auto text-red-400 hover:text-red-600">Remove</button>
              </div>
              <AddressFields label={`Address ${i + 1}`} value={a} inputClass={SMALL_INPUT}
                onChange={v => setAddress(i, v)} />
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-medium text-gray-600">Contacts</span>
          <button type="button" onClick={() => setContacts([...contacts, { ...EMPTY_CONTACT, defaultContact: contacts.length === 0 }])}
            className="text-xs text-brand-600 hover:text-brand-800">+ Add contact</button>
        </div>
        {contacts.length === 0 && <p className="text-xs text-gray-400">No contacts</p>}
        {contacts.map((c, i) => (
          <div key={i} className="grid grid-cols-12 gap-2 mb-2 items-center" data-testid={`contact-row-${i}`}>
            <input aria-label={`Contact ${i + 1} name`} placeholder="Name" value={c.name ?? ""} onChange={e => setContact(i, { name: e.target.value })} className={`${SMALL_INPUT} col-span-2`} />
            <input aria-label={`Contact ${i + 1} job title`} placeholder="Job title" value={c.jobTitle ?? ""} onChange={e => setContact(i, { jobTitle: e.target.value })} className={`${SMALL_INPUT} col-span-2`} />
            <input aria-label={`Contact ${i + 1} email`} placeholder="Email" type="email" value={c.email ?? ""} onChange={e => setContact(i, { email: e.target.value })} className={`${SMALL_INPUT} col-span-3`} />
            <input aria-label={`Contact ${i + 1} phone`} placeholder="Phone" value={c.phone ?? ""} onChange={e => setContact(i, { phone: e.target.value })} className={`${SMALL_INPUT} col-span-2`} />
            <input aria-label={`Contact ${i + 1} mobile`} placeholder="Mobile" value={c.mobilePhone ?? ""} onChange={e => setContact(i, { mobilePhone: e.target.value })} className={`${SMALL_INPUT} col-span-1`} />
            <label className="col-span-1 flex items-center gap-1 text-xs text-gray-600">
              <input type="radio" name="default-contact" checked={c.defaultContact} onChange={() => setContact(i, { defaultContact: true })} />
              Default
            </label>
            <button type="button" onClick={() => setContacts(contacts.filter((_, j) => j !== i))}
              className="col-span-1 text-red-400 hover:text-red-600 text-xs text-right">Remove</button>
          </div>
        ))}
      </div>

      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-700 px-3 py-2">Cancel</button>
        <button type="submit" disabled={saving}
          className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
          {saving ? "Saving..." : editing ? "Save Customer" : "Create Customer"}
        </button>
      </div>
    </form>
  );
}

function Field({ id, label, value, onChange, placeholder, type }: {
  id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string; type?: string;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
      <input id={id} type={type} min={type === "number" ? 0 : undefined} step={type === "number" ? "any" : undefined}
        value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} className={INPUT} />
    </div>
  );
}
