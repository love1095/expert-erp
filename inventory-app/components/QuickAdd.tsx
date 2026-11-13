"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { addCustomerContact, addPaymentTerm, addSalesRep, createCustomer, createSupplier, createWarehouse, getPaymentTerms, getPriceTiers, getSalesReps, getTaxRates, updateSupplier } from "@/lib/api";
import { Customer, CustomerContact, PostalAddress, PriceTier, Supplier, TaxRate, Warehouse } from "@/types";
import { AddressFields, EMPTY_ADDRESS, isBlankAddress } from "@/components/AddressFields";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { Checkbox, FormField, Input, Select, inputClass } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";

/**
 * The "+" next to a pick list on a form, like Cin7: it opens a small pop-up to add what is missing (a customer, a
 * contact, a payment term, a sales representative) without leaving the form; the new entry is then chosen in the field.
 */
const errorOf = (e: unknown, fallback: string) =>
  (e as { response?: { status?: number; data?: { error?: string } } })?.response?.status === 403
    ? "Your role may not add this. Ask an admin."
    : (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;

/** A field with its "+" on the right. */
export function WithAdd({ children, label, onAdd, disabled, disabledHint }: {
  children: React.ReactNode;
  label: string;                // e.g. "New customer" (the button's name and tooltip)
  onAdd: () => void;
  disabled?: boolean;
  disabledHint?: string;        // why it is switched off, e.g. "Pick a customer first"
}) {
  return (
    <div className="flex items-stretch gap-1.5">
      <div className="flex-1 min-w-0">{children}</div>
      <button type="button" onClick={onAdd} disabled={disabled} aria-label={label} title={disabled ? disabledHint ?? label : label}
        className="shrink-0 w-8 flex items-center justify-center rounded-md border border-gray-300 bg-white text-gray-600 hover:bg-gray-50 hover:text-gray-900 disabled:opacity-40 disabled:cursor-not-allowed">
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={2} className="w-4 h-4" aria-hidden="true"><path d="M10 4v12M4 10h12" strokeLinecap="round" /></svg>
      </button>
    </div>
  );
}

function Footer({ onClose, busy, action }: { onClose: () => void; busy: boolean; action: string }) {
  return (
    <>
      <Button onClick={onClose}>Cancel</Button>
      <Button kind="primary" type="submit" busy={busy}>{busy ? "Saving…" : action}</Button>
    </>
  );
}

const ErrorLine = ({ text }: { text: string }) =>
  text ? <p role="alert" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded mb-3">{text}</p> : null;

/** New customer: the details needed for an order; the rest (more contacts, addresses, credit limit) on the customer's page. */
export function NewCustomerDialog({ initialName = "", onClose, onCreated }: {
  initialName?: string;
  onClose: () => void;
  onCreated: (c: Customer) => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const { data: tiers = [] } = useQuery<PriceTier[]>({ queryKey: ["price-tiers"], queryFn: getPriceTiers });
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });
  const { data: salesReps = [] } = useQuery<string[]>({ queryKey: ["sales-reps"], queryFn: getSalesReps });
  const [form, setForm] = useState({ name: initialName, contact: "", phone: "", email: "", paymentTerm: "", priceTier: "", salesRepresentative: "", taxNumber: "" });
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  const [error, setError] = useState("");
  const set = (patch: Partial<typeof form>) => setForm(f => ({ ...f, ...patch }));

  const save = useMutation({
    mutationFn: () => createCustomer({
      name: form.name.trim(), status: "ACTIVE", currency: "GBP",
      paymentTerm: form.paymentTerm, priceTier: form.priceTier, salesRepresentative: form.salesRepresentative, taxNumber: form.taxNumber,
      contacts: form.contact.trim() || form.phone.trim() || form.email.trim()
        ? [{ name: form.contact, phone: form.phone, email: form.email, defaultContact: true, includeInEmail: true, marketingConsent: "UNKNOWN" }] : [],
      addresses: isBlankAddress(address) ? [] : [{ ...address, type: "BILLING", defaultForType: true }],
    }),
    onSuccess: (c: Customer) => {
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customers-page"] });
      toast("Customer added");
      onCreated(c);
    },
    onError: e => setError(errorOf(e, "Could not add the customer")),
  });

  return (
    <Modal title="New customer" hint="More contacts, addresses and the credit limit can be added later on the customer's page."
      onClose={onClose} width="max-w-2xl" testId="quick-new-customer">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <FormField label="Name" required className="sm:col-span-2">
            {p => <Input {...p} value={form.name} onChange={e => set({ name: e.target.value })} maxLength={255} autoComplete="off" />}
          </FormField>
          <FormField label="Contact name">
            {p => <Input {...p} value={form.contact} onChange={e => set({ contact: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Phone">
            {p => <Input {...p} value={form.phone} onChange={e => set({ phone: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="E-mail">
            {p => <Input {...p} type="email" value={form.email} onChange={e => set({ email: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="VAT number">
            {p => <Input {...p} value={form.taxNumber} onChange={e => set({ taxNumber: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Payment terms">
            {p => <Select {...p} value={form.paymentTerm} onChange={e => set({ paymentTerm: e.target.value })}>
              <option value="">None (due on the invoice date)</option>
              {terms.map(t => <option key={t.name} value={t.name}>{t.name}</option>)}
            </Select>}
          </FormField>
          <FormField label="Price tier">
            {p => <Select {...p} value={form.priceTier} onChange={e => set({ priceTier: e.target.value })}>
              <option value="">{tiers[0]?.name ?? "Retail"} (the first tier)</option>
              {tiers.map(t => <option key={t.number} value={t.name}>{t.name}</option>)}
            </Select>}
          </FormField>
          <FormField label="Sales representative" className="sm:col-span-2">
            {p => <Select {...p} value={form.salesRepresentative} onChange={e => set({ salesRepresentative: e.target.value })}>
              <option value="">None</option>
              {salesReps.map(r => <option key={r} value={r}>{r}</option>)}
            </Select>}
          </FormField>
          <div className="sm:col-span-2">
            <p className="block text-xs font-medium text-gray-600 mb-1">Billing address</p>
            <AddressFields label="New customer billing address" value={address} onChange={setAddress} inputClass={inputClass("md")} />
          </div>
        </div>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add customer" /></div>
      </form>
    </Modal>
  );
}

/** New supplier from the purchase form: what an order needs; the rest on the supplier's page. */
export function NewSupplierDialog({ initialName = "", onClose, onCreated }: {
  initialName?: string;
  onClose: () => void;
  onCreated: (s: Supplier) => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const { data: terms = [] } = useQuery<{ name: string; meaning: string }[]>({ queryKey: ["payment-terms"], queryFn: getPaymentTerms });
  const { data: rates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "PURCHASE"], queryFn: () => getTaxRates("PURCHASE") });
  const [form, setForm] = useState({ name: initialName, contactName: "", phone: "", email: "", taxNumber: "", paymentTerm: "", taxRule: "" });
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  const [error, setError] = useState("");
  const set = (patch: Partial<typeof form>) => setForm(f => ({ ...f, ...patch }));

  const save = useMutation({
    mutationFn: () => createSupplier({
      name: form.name.trim(), status: "Active", currency: "GBP", contactName: form.contactName, phone: form.phone, email: form.email,
      taxNumber: form.taxNumber, paymentTerm: form.paymentTerm || null, taxRule: form.taxRule || null,
      address: isBlankAddress(address) ? null : address,
    }),
    onSuccess: (s: Supplier) => {
      qc.invalidateQueries({ queryKey: ["suppliers"] });
      qc.invalidateQueries({ queryKey: ["suppliers-page"] });
      toast("Supplier added");
      onCreated(s);
    },
    onError: e => setError(errorOf(e, "Could not add the supplier")),
  });

  return (
    <Modal title="New supplier" hint="The rest can be added later on the supplier's page." onClose={onClose} width="max-w-2xl" testId="quick-new-supplier">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <FormField label="Name" required className="sm:col-span-2">
            {p => <Input {...p} value={form.name} onChange={e => set({ name: e.target.value })} maxLength={255} autoComplete="off" />}
          </FormField>
          <FormField label="Contact name">
            {p => <Input {...p} value={form.contactName} onChange={e => set({ contactName: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Phone">
            {p => <Input {...p} value={form.phone} onChange={e => set({ phone: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="E-mail">
            {p => <Input {...p} type="email" value={form.email} onChange={e => set({ email: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="VAT number">
            {p => <Input {...p} value={form.taxNumber} onChange={e => set({ taxNumber: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Payment terms">
            {p => <Select {...p} value={form.paymentTerm} onChange={e => set({ paymentTerm: e.target.value })}>
              <option value="">None</option>
              {terms.map(t => <option key={t.name} value={t.name}>{t.name}</option>)}
            </Select>}
          </FormField>
          <FormField label="Tax rule">
            {p => <Select {...p} value={form.taxRule} onChange={e => set({ taxRule: e.target.value })}>
              <option value="">The organisation default</option>
              {rates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}
            </Select>}
          </FormField>
          <div className="sm:col-span-2">
            <p className="block text-xs font-medium text-gray-600 mb-1">Address</p>
            <AddressFields label="New supplier address" value={address} onChange={setAddress} inputClass={inputClass("md")} />
          </div>
        </div>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add supplier" /></div>
      </form>
    </Modal>
  );
}

/** New contact for the customer chosen on the form; the customer's other contacts stay as they are. */
export function NewContactDialog({ customer, onClose, onAdded }: {
  customer: Customer;
  onClose: () => void;
  onAdded: (contact: CustomerContact, customer: Customer) => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState({ name: "", jobTitle: "", phone: "", mobilePhone: "", email: "", defaultContact: false, includeInEmail: false });
  const [error, setError] = useState("");
  const set = (patch: Partial<typeof form>) => setForm(f => ({ ...f, ...patch }));

  const save = useMutation({
    mutationFn: () => addCustomerContact(customer.id, { ...form, marketingConsent: "UNKNOWN" }),
    onSuccess: (c: Customer) => {
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customer", customer.id] });
      toast("Contact added");
      // The one just added: the newest of the customer's contacts
      const added = [...c.contacts].sort((a, b) => (b.id ?? 0) - (a.id ?? 0))[0];
      onAdded(added, c);
    },
    onError: e => setError(errorOf(e, "Could not add the contact")),
  });

  return (
    <Modal title="New contact" hint={<>For <b className="text-gray-700">{customer.name}</b></>} onClose={onClose} testId="quick-new-contact">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <FormField label="Name" required>
            {p => <Input {...p} value={form.name} onChange={e => set({ name: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Job title">
            {p => <Input {...p} value={form.jobTitle} onChange={e => set({ jobTitle: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Phone">
            {p => <Input {...p} value={form.phone} onChange={e => set({ phone: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Mobile">
            {p => <Input {...p} value={form.mobilePhone} onChange={e => set({ mobilePhone: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="E-mail" className="sm:col-span-2">
            {p => <Input {...p} type="email" value={form.email} onChange={e => set({ email: e.target.value })} autoComplete="off" />}
          </FormField>
        </div>
        <div className="mt-3 space-y-2">
          <Checkbox label="Default contact" hint="Used on new orders for this customer" checked={form.defaultContact} onChange={e => set({ defaultContact: e.target.checked })} />
          <Checkbox label="Include in e-mails" hint="Invoices and statements are also sent to this contact" checked={form.includeInEmail} onChange={e => set({ includeInEmail: e.target.checked })} />
        </div>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add contact" /></div>
      </form>
    </Modal>
  );
}

type TermMethod = "ON_INVOICE" | "DAYS" | "DAYS_EOM";
const termMeaning = (method: TermMethod, days: string) => {
  const n = days.trim() === "" ? "…" : days.trim();
  return method === "ON_INVOICE" ? "Due on the invoice date"
    : method === "DAYS" ? `Due ${n} days after the invoice date`
    : `Due ${n} days after the end of the invoice month`;
};

/** Add a payment term: its name and how the due date of an invoice is worked out. */
export function PaymentTermDialog({ onClose, onAdded }: { onClose: () => void; onAdded: (name: string) => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [method, setMethod] = useState<TermMethod>("DAYS");
  const [days, setDays] = useState("30");
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: () => addPaymentTerm({ name: name.trim(), method, days: method === "ON_INVOICE" ? null : Number(days) }),
    onSuccess: t => { qc.invalidateQueries({ queryKey: ["payment-terms"] }); toast("Payment term added"); onAdded(t.name); },
    onError: e => setError(errorOf(e, "Could not add the payment term")),
  });

  return (
    <Modal title="Add a payment term" onClose={onClose} testId="quick-payment-term">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <FormField label="Name" required className="sm:col-span-3" hint="As it shows on orders and invoices, e.g. 45 Days">
            {p => <Input {...p} value={name} onChange={e => setName(e.target.value)} maxLength={100} autoComplete="off" />}
          </FormField>
          <FormField label="Due" className="sm:col-span-2">
            {p => <Select {...p} value={method} onChange={e => setMethod(e.target.value as TermMethod)}>
              <option value="DAYS">A number of days after the invoice date</option>
              <option value="DAYS_EOM">A number of days after the end of the invoice month</option>
              <option value="ON_INVOICE">On the invoice date (cash, proforma)</option>
            </Select>}
          </FormField>
          <FormField label="Days" required={method !== "ON_INVOICE"}>
            {p => <Input {...p} type="number" min={0} max={365} step={1} value={method === "ON_INVOICE" ? "" : days}
              disabled={method === "ON_INVOICE"} onChange={e => setDays(e.target.value)} />}
          </FormField>
        </div>
        <p data-testid="quick-term-meaning" className="text-sm text-gray-700 bg-gray-50 border border-gray-200 rounded-md px-3 py-2 mt-3">{termMeaning(method, days)}</p>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add payment term" /></div>
      </form>
    </Modal>
  );
}

/** Add a sales representative to the list (a name; it does not need a login). */
export function SalesRepDialog({ onClose, onAdded }: { onClose: () => void; onAdded: (name: string) => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: () => addSalesRep(name.trim()),
    onSuccess: r => { qc.invalidateQueries({ queryKey: ["sales-reps"] }); toast("Sales representative added"); onAdded(r.name); },
    onError: e => setError(errorOf(e, "Could not add the sales representative")),
  });

  return (
    <Modal title="Add a sales representative" onClose={onClose} width="max-w-md" testId="quick-sales-rep">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <FormField label="Name" required hint="The name shown on customers, orders and the sales by rep report">
          {p => <Input {...p} value={name} onChange={e => setName(e.target.value)} maxLength={100} autoComplete="off" />}
        </FormField>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add sales representative" /></div>
      </form>
    </Modal>
  );
}

export type SupplierContact = { contactName: string; contactPhone: string; email: string };

/**
 * "+" next to Contact on a purchase: a contact for this order. A supplier keeps one contact for now, so it can also be
 * saved as the supplier's own (ticked when the supplier has none; unticked, only this order gets it).
 */
export function SupplierContactDialog({ supplier, onClose, onDone }: {
  supplier: Supplier;
  onClose: () => void;
  onDone: (contact: SupplierContact) => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState({ contactName: "", contactPhone: "", email: "", keep: !supplier.contactName?.trim() });
  const [error, setError] = useState("");
  const set = (patch: Partial<typeof form>) => setForm(f => ({ ...f, ...patch }));

  const save = useMutation({
    mutationFn: async () => {
      const contact = { contactName: form.contactName.trim(), contactPhone: form.contactPhone.trim(), email: form.email.trim() };
      if (form.keep) await updateSupplier(supplier.id, { ...supplier, contactName: contact.contactName, phone: contact.contactPhone, email: contact.email || supplier.email });
      return contact;
    },
    onSuccess: contact => {
      if (form.keep) {
        qc.invalidateQueries({ queryKey: ["suppliers"] });
        qc.invalidateQueries({ queryKey: ["suppliers-page"] });
        toast(`Saved as ${supplier.name}'s contact`);
      }
      onDone(contact);
    },
    onError: e => setError(errorOf(e, "Could not save the contact")),
  });

  return (
    <Modal title="New contact" hint={<>For <b className="text-gray-700">{supplier.name}</b></>} onClose={onClose} testId="quick-supplier-contact">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <FormField label="Name" required className="sm:col-span-2">
            {p => <Input {...p} value={form.contactName} onChange={e => set({ contactName: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="Phone">
            {p => <Input {...p} value={form.contactPhone} onChange={e => set({ contactPhone: e.target.value })} autoComplete="off" />}
          </FormField>
          <FormField label="E-mail">
            {p => <Input {...p} type="email" value={form.email} onChange={e => set({ email: e.target.value })} autoComplete="off" />}
          </FormField>
        </div>
        <div className="mt-3">
          <Checkbox label={`Save as ${supplier.name}'s contact`}
            hint={supplier.contactName?.trim() ? `Replaces ${supplier.contactName} on the supplier; unticked, only this order gets it` : "New purchases from this supplier then start with it"}
            checked={form.keep} onChange={e => set({ keep: e.target.checked })} />
        </div>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Use contact" /></div>
      </form>
    </Modal>
  );
}

/** "+" next to Vendor address on a purchase: an address for this order, which can also become the supplier's own. */
export function SupplierAddressDialog({ supplier, onClose, onDone }: {
  supplier: Supplier;
  onClose: () => void;
  onDone: (address: PostalAddress) => void;
}) {
  const qc = useQueryClient();
  const toast = useToast();
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  const [keep, setKeep] = useState(isBlankAddress(supplier.address));
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: async () => {
      if (isBlankAddress(address)) throw { response: { data: { error: "Enter the address" } } };
      if (keep) await updateSupplier(supplier.id, { ...supplier, address });
      return address;
    },
    onSuccess: a => {
      if (keep) {
        qc.invalidateQueries({ queryKey: ["suppliers"] });
        qc.invalidateQueries({ queryKey: ["suppliers-page"] });
        toast(`Saved as ${supplier.name}'s address`);
      }
      onDone(a);
    },
    onError: e => setError(errorOf(e, "Could not save the address")),
  });

  return (
    <Modal title="New vendor address" hint={<>For <b className="text-gray-700">{supplier.name}</b></>} onClose={onClose} width="max-w-xl" testId="quick-supplier-address">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <AddressFields label="New vendor address" value={address} onChange={setAddress} inputClass={inputClass("md")} />
        <div className="mt-3">
          <Checkbox label={`Save as ${supplier.name}'s address`}
            hint={isBlankAddress(supplier.address) ? "New purchases from this supplier then start with it" : "Replaces the supplier's address (one per supplier for now); unticked, only this order gets it"}
            checked={keep} onChange={e => setKeep(e.target.checked)} />
        </div>
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Use address" /></div>
      </form>
    </Modal>
  );
}

/** "+" next to Location on a purchase: a new warehouse with its address (purchases for it are shipped there). */
export function NewLocationDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (w: Warehouse) => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState({ name: "", location: "" });
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: () => createWarehouse({ name: form.name.trim(), location: form.location.trim() || undefined, address: isBlankAddress(address) ? null : address }),
    onSuccess: (w: Warehouse) => {
      qc.invalidateQueries({ queryKey: ["warehouses"] });
      qc.invalidateQueries({ queryKey: ["warehouses-page"] });
      toast("Location added");
      onCreated(w);
    },
    onError: e => setError(errorOf(e, "Could not add the location")),
  });

  return (
    <Modal title="New location" hint="A warehouse stock is kept in; more can be changed under Warehouses." onClose={onClose} width="max-w-xl" testId="quick-new-location">
      <form onSubmit={e => { e.preventDefault(); setError(""); save.mutate(); }}>
        <ErrorLine text={error} />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <FormField label="Name" required>
            {p => <Input {...p} value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} maxLength={255} autoComplete="off" />}
          </FormField>
          <FormField label="Description" hint="e.g. Unit 4, back store">
            {p => <Input {...p} value={form.location} onChange={e => setForm(f => ({ ...f, location: e.target.value }))} autoComplete="off" />}
          </FormField>
        </div>
        <p className="block text-xs font-medium text-gray-600 mb-1 mt-3">Address</p>
        <AddressFields label="New location address" value={address} onChange={setAddress} inputClass={inputClass("md")} />
        <div className="flex justify-end gap-2 mt-4"><Footer onClose={onClose} busy={save.isPending} action="Add location" /></div>
      </form>
    </Modal>
  );
}
