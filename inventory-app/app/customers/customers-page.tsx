"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getCustomersPage, createCustomer, updateCustomer, deleteCustomer, importCustomers, getSaleOrders } from "@/lib/api";
import { Customer, CustomerContact, CustomerImportResult, Page, SaleOrder } from "@/types";
import { Fragment, useRef, useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import { formatGBP } from "@/lib/currency";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar } from "@/components/ListToolbar";

const EMPTY_CONTACT: CustomerContact = {
  name: "", jobTitle: "", phone: "", mobilePhone: "", email: "",
  defaultContact: false, includeInEmail: false, marketingConsent: "UNKNOWN",
};

const EMPTY_FORM = {
  name: "", status: "ACTIVE" as Customer["status"], currency: "GBP", paymentTerm: "", priceTier: "",
  creditLimit: "", taxRule: "", taxNumber: "", carrier: "", salesRepresentative: "", location: "",
  tags: "", address: "", notes: "",
};
type Form = typeof EMPTY_FORM;

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const SMALL_INPUT = "w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400";

export default function CustomersPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<Form>(EMPTY_FORM);
  const [contacts, setContacts] = useState<CustomerContact[]>([]);
  const [error, setError] = useState("");
  const [openId, setOpenId] = useState<number | null>(null);
  const [importResult, setImportResult] = useState<CustomerImportResult | null>(null);
  const [importError, setImportError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Customer>>({
    queryKey: ["customers-page", search, page, size],
    queryFn: () => getCustomersPage({ search: search || undefined, page, size }),
  });

  const customers = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const set = (k: keyof Form, v: string) => setForm(f => ({ ...f, [k]: v }));
  const setContact = (i: number, patch: Partial<CustomerContact>) =>
    setContacts(cs => cs.map((c, j) => (j === i ? { ...c, ...patch } : patch.defaultContact ? { ...c, defaultContact: false } : c)));

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    qc.invalidateQueries({ queryKey: ["customers"] });
  };
  const closeForm = () => { setShowForm(false); setEditingId(null); setForm(EMPTY_FORM); setContacts([]); setError(""); };
  const onSaved = () => { refresh(); closeForm(); };
  const onError = (e: any) => {
    const fields = e.response?.data?.fields;
    setError(fields ? Object.values(fields).join(", ") : e.response?.data?.error || "Failed to save customer");
  };

  const createMutation = useMutation({ mutationFn: createCustomer, onSuccess: onSaved, onError });
  const updateMutation = useMutation({ mutationFn: updateCustomer, onSuccess: onSaved, onError });
  const deleteMutation = useMutation({
    mutationFn: deleteCustomer,
    onSuccess: refresh,
    onError: (e: any) => alert(e.response?.data?.error || "Failed to delete customer"),
  });
  const importMutation = useMutation({
    mutationFn: importCustomers,
    onSuccess: (r: CustomerImportResult) => { setImportResult(r); setImportError(""); refresh(); },
    onError: (e: any) => { setImportResult(null); setImportError(e.response?.data?.error || "Import failed"); },
    onSettled: () => { if (fileInput.current) fileInput.current.value = ""; },
  });

  const startCreate = () => {
    setEditingId(null); setForm(EMPTY_FORM); setContacts([{ ...EMPTY_CONTACT, defaultContact: true }]); setError(""); setShowForm(true);
  };

  const startEdit = (c: Customer) => {
    setEditingId(c.id);
    setForm({
      name: c.name, status: c.status, currency: c.currency ?? "", paymentTerm: c.paymentTerm ?? "",
      priceTier: c.priceTier ?? "", creditLimit: c.creditLimit != null ? String(c.creditLimit) : "",
      taxRule: c.taxRule ?? "", taxNumber: c.taxNumber ?? "", carrier: c.carrier ?? "",
      salesRepresentative: c.salesRepresentative ?? "", location: c.location ?? "", tags: c.tags ?? "",
      address: c.address ?? "", notes: c.notes ?? "",
    });
    setContacts(c.contacts.map(x => ({ ...x })));
    setError("");
    setShowForm(true);
    window.scrollTo({ top: 0 });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = { ...form, creditLimit: form.creditLimit === "" ? null : Number(form.creditLimit), contacts };
    if (editingId != null) updateMutation.mutate({ id: editingId, data: body });
    else createMutation.mutate(body);
  };

  const saving = createMutation.isPending || updateMutation.isPending;

  return (
    <div>
      <PageHeader title="Customers" count={totalElements} noun="customers">
          <input ref={fileInput} type="file" accept=".csv,text/csv" className="hidden" aria-label="Customer CSV file"
            onChange={e => { const f = e.target.files?.[0]; if (f) importMutation.mutate(f); }} />
          <button onClick={() => fileInput.current?.click()} disabled={importMutation.isPending}
            className="border border-gray-300 bg-white text-gray-700 px-4 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50">
            {importMutation.isPending ? "Importing..." : "Import CSV"}
          </button>
          <button onClick={() => (showForm ? closeForm() : startCreate())}
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
            {showForm ? "Cancel" : "+ New Customer"}
          </button>
      </PageHeader>

      {importError && <p role="alert" data-testid="customer-import-error" className="mb-4 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{importError}</p>}
      {importResult && (
        <div role="status" data-testid="customer-import-result" className="mb-4 text-sm bg-emerald-50 text-emerald-800 px-4 py-3 rounded-lg">
          <div className="flex items-start justify-between">
            <p>
              Imported {importResult.rows} rows: {importResult.customersCreated} customers created,{" "}
              {importResult.customersUpdated} updated, {importResult.contacts} contacts.
            </p>
            <button onClick={() => setImportResult(null)} className="text-xs text-emerald-700 hover:underline ml-4">Dismiss</button>
          </div>
          {importResult.warnings.length > 0 && (
            <details className="mt-2 text-amber-800">
              <summary className="cursor-pointer">{importResult.warnings.length} warning(s)</summary>
              <ul className="list-disc ml-5 mt-1 text-xs space-y-0.5">
                {importResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </details>
          )}
        </div>
      )}

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">{editingId != null ? "Edit Customer" : "New Customer"}</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

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
            <Field id="customers-payment-term" label="Payment Term" value={form.paymentTerm} onChange={v => set("paymentTerm", v)} placeholder="e.g. 30 Day EOM" />
            <Field id="customers-price-tier" label="Price Tier" value={form.priceTier} onChange={v => set("priceTier", v)} placeholder="e.g. Contract" />
            <Field id="customers-credit-limit" label="Credit Limit (£)" value={form.creditLimit} onChange={v => set("creditLimit", v)} type="number" />
            <Field id="customers-currency" label="Currency" value={form.currency} onChange={v => set("currency", v)} placeholder="GBP" />
            <Field id="customers-tax-rule" label="Tax Rule" value={form.taxRule} onChange={v => set("taxRule", v)} placeholder="e.g. 20% (VAT on Income)" />
            <Field id="customers-tax-number" label="Tax Number" value={form.taxNumber} onChange={v => set("taxNumber", v)} placeholder="VAT number" />
            <Field id="customers-carrier" label="Carrier" value={form.carrier} onChange={v => set("carrier", v)} />
            <Field id="customers-sales-rep" label="Sales Representative" value={form.salesRepresentative} onChange={v => set("salesRepresentative", v)} />
            <Field id="customers-location" label="Location" value={form.location} onChange={v => set("location", v)} />
            <div className="col-span-3">
              <Field id="customers-tags" label="Tags" value={form.tags} onChange={v => set("tags", v)} placeholder="Comma-separated" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="customers-address" className="block text-xs font-medium text-gray-600 mb-1">Address</label>
              <textarea id="customers-address" rows={2} value={form.address} onChange={e => set("address", e.target.value)} className={INPUT} />
            </div>
            <div>
              <label htmlFor="customers-notes" className="block text-xs font-medium text-gray-600 mb-1">Comments</label>
              <textarea id="customers-notes" rows={2} value={form.notes} onChange={e => set("notes", e.target.value)} className={INPUT} />
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

          <button type="submit" disabled={saving}
            className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {saving ? "Saving..." : editingId != null ? "Save Customer" : "Create Customer"}
          </button>
        </form>
      )}

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        searchLabel="Search customers" placeholder="Name, contact, email or phone"
        onRefresh={() => refetch()} refreshing={isFetching} />

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Name", "Status", "Main contact", "Terms", "Credit limit", "Sales rep", "Orders", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {customers.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No customers found</td></tr>
                )}
                {customers.map(c => {
                  const main = c.contacts[0];
                  return (
                    <Fragment key={c.id}>
                      <tr data-testid={`customer-row-${c.id}`} className={`hover:bg-gray-50 ${openId === c.id ? "bg-gray-50" : ""}`}>
                        <td className="px-4 py-3">
                          <div className="font-medium">{c.name}</div>
                          {c.source === "SHOPIFY" && <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700">Shopify</span>}
                        </td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2 py-0.5 rounded ${c.status === "ACTIVE" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
                            {c.status === "ACTIVE" ? "Active" : "Deprecated"}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs">
                          {main ? (
                            <>
                              <div className="text-gray-700">{main.name || "—"}{c.contacts.length > 1 && <span className="text-gray-400"> +{c.contacts.length - 1}</span>}</div>
                              {main.email && <a href={`mailto:${main.email}`} className="text-brand-600 hover:underline">{main.email}</a>}
                              {(main.phone || main.mobilePhone) && <div className="text-gray-500">{main.phone || main.mobilePhone}</div>}
                            </>
                          ) : <span className="text-gray-400">—</span>}
                        </td>
                        <td className="px-4 py-3 text-xs text-gray-600">
                          <div>{c.paymentTerm || "—"}</div>
                          {c.priceTier && <div className="text-gray-400">{c.priceTier}</div>}
                        </td>
                        <td className="px-4 py-3 text-xs tabular-nums text-gray-600">{c.creditLimit != null ? formatGBP(c.creditLimit) : "—"}</td>
                        <td className="px-4 py-3 text-xs text-gray-600">{c.salesRepresentative || "—"}</td>
                        <td className="px-4 py-3 tabular-nums">
                          {c.orderCount > 0 ? (
                            <button onClick={() => setOpenId(openId === c.id ? null : c.id)} className="text-brand-600 hover:text-brand-800 font-medium">
                              {c.orderCount}
                            </button>
                          ) : <span className="text-gray-400">0</span>}
                        </td>
                        <td className="px-4 py-3 text-right space-x-3 whitespace-nowrap">
                          <button onClick={() => startEdit(c)} className="text-xs text-gray-500 hover:text-gray-800">Edit</button>
                          <button onClick={() => { if (confirm(`Delete customer "${c.name}"?`)) deleteMutation.mutate(c.id); }}
                            className="text-xs text-red-400 hover:text-red-600">Delete</button>
                        </td>
                      </tr>
                      {openId === c.id && (
                        <tr>
                          <td colSpan={8} className="p-0">
                            <CustomerOrders customerId={c.id} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage}
            size={size} onSizeChange={n => { setSize(n); setPage(0); }} />
        </>
      )}
    </div>
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

function CustomerOrders({ customerId }: { customerId: number }) {
  const { data, isLoading } = useQuery<Page<SaleOrder>>({
    queryKey: ["sale-orders", "customer", customerId],
    queryFn: () => getSaleOrders({ customerId, size: 10 }),
  });
  const orders = data?.content ?? [];

  return (
    <div data-testid={`customer-orders-${customerId}`} className="bg-gray-50 px-8 py-4 border-t border-gray-100">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Recent sale orders{data && data.totalElements > orders.length ? ` (latest ${orders.length} of ${data.totalElements})` : ""}
      </p>
      {isLoading ? <p className="text-gray-400 text-xs">Loading...</p> : (
        <ul className="space-y-1 text-sm">
          {orders.map(o => (
            <li key={o.id} className="flex gap-4">
              <span className="font-mono text-xs text-gray-400 w-20">{o.orderNumber}</span>
              <span className="text-xs font-semibold text-gray-600 w-24">{o.status}</span>
              <span className="text-gray-600">{o.lines.length} item{o.lines.length !== 1 ? "s" : ""}</span>
              <span className="text-gray-400 text-xs"><ClientDate iso={o.createdAt} /></span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
