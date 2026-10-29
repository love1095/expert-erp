"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getWarehousesPage, createWarehouse, deleteWarehouse } from "@/lib/api";
import { Warehouse, Page } from "@/types";
import { useState } from "react";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";

export default function WarehousesPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [form, setForm] = useState({ name: "", location: "" });
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState("");

  const { data, isLoading } = useQuery<Page<Warehouse>>({
    queryKey: ["warehouses-page", page],
    queryFn: () => getWarehousesPage({ page, size: 20 }),
  });

  const warehouses = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const createMutation = useMutation({
    mutationFn: createWarehouse,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["warehouses-page"] });
      qc.invalidateQueries({ queryKey: ["warehouses"] });
      setShowForm(false); setForm({ name: "", location: "" }); setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create warehouse"),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteWarehouse,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["warehouses-page"] });
      qc.invalidateQueries({ queryKey: ["warehouses"] });
    },
  });

  const handleSubmit = (e: React.FormEvent) => { e.preventDefault(); createMutation.mutate(form); };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Warehouses</h1>
        <button onClick={() => setShowForm(!showForm)}
          className="bg-emerald-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-emerald-700 transition-colors">
          {showForm ? "Cancel" : "+ Add Warehouse"}
        </button>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white border border-gray-200 rounded-xl p-6 mb-6 grid grid-cols-2 gap-4">
          <h2 className="col-span-2 font-semibold text-gray-800">New Warehouse</h2>
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
          <div className="col-span-2 flex justify-end">
            <button type="submit" disabled={createMutation.isPending}
              className="bg-emerald-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-emerald-700 disabled:opacity-50">
              {createMutation.isPending ? "Saving..." : "Save Warehouse"}
            </button>
          </div>
        </form>
      )}

      {isLoading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Name", "Location", "Created", ""].map((h) => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {warehouses.length === 0 && (
                  <tr><td colSpan={4} className="px-4 py-8 text-center text-gray-400">No warehouses yet</td></tr>
                )}
                {warehouses.map((w) => (
                  <tr key={w.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium">{w.name}</td>
                    <td className="px-4 py-3 text-gray-500">{w.location || "—"}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={w.createdAt} /></td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => { if (confirm(`Delete "${w.name}"?`)) deleteMutation.mutate(w.id); }}
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
