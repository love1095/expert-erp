"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getWarehousesPage, createWarehouse, deleteWarehouse, updateWarehouse } from "@/lib/api";
import { AddressFields, EMPTY_ADDRESS, isBlankAddress, oneLine } from "@/components/AddressFields";
import { Warehouse, Page, PostalAddress } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";
import { useConfirm } from "@/components/ConfirmDialog";
import { SkeletonRows } from "@/components/ui/Skeleton";

export default function WarehousesPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [form, setForm] = useState({ name: "", location: "" });
  // Where purchases for the warehouse are shipped to
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  // The warehouse being changed (null: a new one)
  const [editingId, setEditingId] = useState<number | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState("");

  const { data, isLoading } = useQuery<Page<Warehouse>>({
    queryKey: ["warehouses-page", page],
    queryFn: () => getWarehousesPage({ page, size: 20 }),
  });

  const warehouses = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const closeForm = () => { setShowForm(false); setEditingId(null); setForm({ name: "", location: "" }); setAddress(EMPTY_ADDRESS); setError(""); };
  const createMutation = useMutation({
    mutationFn: (data: unknown) => (editingId ? updateWarehouse(editingId, data) : createWarehouse(data)),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["warehouses-page"] });
      qc.invalidateQueries({ queryKey: ["warehouses"] });
      closeForm();
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to save warehouse"),
  });
  const startEdit = (w: Warehouse) => {
    setEditingId(w.id); setForm({ name: w.name, location: w.location ?? "" }); setAddress({ ...EMPTY_ADDRESS, ...(w.address ?? {}) });
    setError(""); setShowForm(true);
  };

  const deleteMutation = useMutation({
    mutationFn: deleteWarehouse,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["warehouses-page"] });
      qc.invalidateQueries({ queryKey: ["warehouses"] });
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createMutation.mutate({ ...form, address: isBlankAddress(address) ? null : address });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Warehouses</h1>
        <button onClick={() => (showForm ? closeForm() : setShowForm(true))}
          className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 transition-colors">
          {showForm ? "Cancel" : "+ Add Warehouse"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 gap-4">
          <h2 className="col-span-2 font-semibold text-gray-800">{editingId ? `Edit ${form.name}` : "New Warehouse"}</h2>
          {error && <p className="col-span-2 text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}
          {[
            { label: "Name *", key: "name", placeholder: "e.g. Main Warehouse" },
            { label: "Location", key: "location", placeholder: "e.g. Ho Chi Minh City" },
          ].map(({ label, key, placeholder }) => (
            <div key={key}>
              <label htmlFor={`warehouses-${key}`} className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
              <input id={`warehouses-${key}`} value={form[key as keyof typeof form]} onChange={(e) => setForm({ ...form, [key]: e.target.value })}
                placeholder={placeholder}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400" />
            </div>
          ))}
          <div className="col-span-2">
            <p className="block text-xs font-medium text-gray-600 mb-1">Address (where purchases are shipped to)</p>
            <AddressFields label="Warehouse address" value={address} onChange={setAddress}
              inputClass="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400" />
          </div>
          <div className="col-span-2 flex justify-end">
            <button type="submit" disabled={createMutation.isPending}
              className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
              {createMutation.isPending ? "Saving..." : "Save Warehouse"}
            </button>
          </div>
        </form>
      )}

      {isLoading ? (
        <SkeletonRows />
      ) : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Name", "Location", "Address", "Created", ""].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {warehouses.length === 0 && (
                  <tr><td colSpan={5} className="px-4 py-8 text-center text-gray-400">No warehouses yet</td></tr>
                )}
                {warehouses.map((w) => (
                  <tr key={w.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{w.name}</td>
                    <td className="px-4 py-3 text-gray-500">{w.location || "—"}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{oneLine(w.address) || "—"}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={w.createdAt} /></td>
                    <td className="px-4 py-3 text-right space-x-3">
                      <button onClick={() => startEdit(w)} className="text-xs text-brand-700 hover:underline">Edit</button>
                      <button onClick={async () => { if (await ask({ title: `Delete "${w.name}"?`, action: "Delete", danger: true })) deleteMutation.mutate(w.id); }}
                        className="text-xs text-red-500 hover:text-red-700">Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
