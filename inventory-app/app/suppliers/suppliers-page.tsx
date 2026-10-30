"use client";

import { Clip } from "@/components/ListToolbar";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getSuppliersPage, createSupplier, deleteSupplier } from "@/lib/api";
import { Supplier, Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import { PageHeader, ListToolbar } from "@/components/ListToolbar";

const EMPTY_FORM = { name: "", status: "Active", currency: "GBP", paymentTerm: "", taxNumber: "", website: "", contactName: "", phone: "", email: "", comments: "" };

export default function SuppliersPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState("");

  const { data, isLoading, isFetching, refetch } = useQuery<Page<Supplier>>({
    queryKey: ["suppliers-page", search, page, size],
    queryFn: () => getSuppliersPage({ search: search || undefined, page, size }),
  });

  const suppliers = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  const createMutation = useMutation({
    mutationFn: createSupplier,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suppliers-page"] });
      qc.invalidateQueries({ queryKey: ["suppliers"] });
      setShowForm(false); setForm(EMPTY_FORM); setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create supplier"),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteSupplier,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suppliers-page"] });
      qc.invalidateQueries({ queryKey: ["suppliers"] });
    },
  });

  return (
    <div>
      <PageHeader title="Suppliers" count={totalElements} noun="suppliers">
        <button onClick={() => { setShowForm(!showForm); if (showForm) { setForm(EMPTY_FORM); setError(""); } }}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
          {showForm ? "Cancel" : "+ New Supplier"}
        </button>
      </PageHeader>

      {showForm && (
        <form onSubmit={e => { e.preventDefault(); createMutation.mutate(form); }}
          className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">New Supplier</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div className="grid grid-cols-3 gap-4">
            <div className="col-span-2">
              <label htmlFor="suppliers-name" className="block text-xs font-medium text-gray-600 mb-1">Name *</label>
              <input id="suppliers-name" value={form.name} onChange={e => set("name", e.target.value)} required placeholder="Supplier name"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="suppliers-status" className="block text-xs font-medium text-gray-600 mb-1">Status</label>
              <select id="suppliers-status" value={form.status} onChange={e => set("status", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
                <option>Active</option>
                <option>Inactive</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label htmlFor="suppliers-currency" className="block text-xs font-medium text-gray-600 mb-1">Currency</label>
              <input id="suppliers-currency" value={form.currency} onChange={e => set("currency", e.target.value)} placeholder="GBP"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="suppliers-payment-term" className="block text-xs font-medium text-gray-600 mb-1">Payment Term</label>
              <input id="suppliers-payment-term" value={form.paymentTerm} onChange={e => set("paymentTerm", e.target.value)} placeholder="e.g. 30 Day EOM"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="suppliers-tax-number" className="block text-xs font-medium text-gray-600 mb-1">Tax Number</label>
              <input id="suppliers-tax-number" value={form.taxNumber} onChange={e => set("taxNumber", e.target.value)} placeholder="VAT number"
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label htmlFor="suppliers-contact-name" className="block text-xs font-medium text-gray-600 mb-1">Contact Name</label>
              <input id="suppliers-contact-name" value={form.contactName} onChange={e => set("contactName", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="suppliers-phone" className="block text-xs font-medium text-gray-600 mb-1">Phone</label>
              <input id="suppliers-phone" value={form.phone} onChange={e => set("phone", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="suppliers-email" className="block text-xs font-medium text-gray-600 mb-1">Email</label>
              <input id="suppliers-email" type="email" value={form.email} onChange={e => set("email", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
          </div>

          <button type="submit" disabled={createMutation.isPending}
            className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {createMutation.isPending ? "Saving..." : "Create Supplier"}
          </button>
        </form>
      )}

      <ListToolbar
        search={search} onSearch={q => { setSearch(q); setPage(0); }}
        searchLabel="Search suppliers" placeholder="Name, email or contact"
        onRefresh={() => refetch()} refreshing={isFetching} />

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Name", "Status", "Currency", "Payment Term", "Contact", "Phone", "Email", ""].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {suppliers.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-gray-400">No suppliers found</td></tr>
                )}
                {suppliers.map(s => (
                  <tr key={s.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium"><Clip text={s.name} width="18rem" /></td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${s.status === "Active" ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-500"}`}>
                        {s.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500">{s.currency || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.paymentTerm || "—"}</td>
                    <td className="px-4 py-3 text-gray-600">{s.contactName || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.phone || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{s.email ? (
                      <a href={`mailto:${s.email}`} className="text-brand-700 hover:underline">{s.email}</a>
                    ) : "—"}</td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => { if (confirm(`Delete supplier "${s.name}"?`)) deleteMutation.mutate(s.id); }}
                        className="text-xs text-red-400 hover:text-red-600">Delete</button>
                    </td>
                  </tr>
                ))}
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
