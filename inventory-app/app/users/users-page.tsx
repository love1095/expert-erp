"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getUsersPage, addUser } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { ClientDate } from "@/components/ClientDate";
import { Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";

const ROLE_COLOR: Record<string, string> = {
  SUPER_ADMIN: "bg-red-100 text-red-700",
  ADMIN:       "bg-brand-100 text-brand-700",
  MANAGER:     "bg-teal-100 text-teal-700",
  STAFF:       "bg-gray-100 text-gray-500",
};

const EMPTY = { username: "", email: "", password: "", role: "STAFF" };

export default function UsersPage() {
  const { user: me } = useAuth();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<any>>({
    queryKey: ["users-page", page],
    queryFn: () => getUsersPage({ page, size: 20 }),
  });

  const users = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements;

  const [showForm, setShowForm] = useState(false);
  const [form, setForm]         = useState(EMPTY);
  const [error, setError]       = useState("");

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  const addMutation = useMutation({
    mutationFn: addUser,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users-page"] });
      setShowForm(false); setForm(EMPTY); setError("");
    },
    onError: (e: any) => setError(e.response?.data?.error || "Failed to create user"),
  });

  const isAdmin = me?.role === "ADMIN" || me?.role === "SUPER_ADMIN";

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Users</h1>
          <p className="text-gray-500 text-sm mt-0.5">Members of {me?.tenantName}</p>
        </div>
        {isAdmin && (
          <button onClick={() => { setShowForm(!showForm); if (showForm) { setForm(EMPTY); setError(""); } }}
            className="bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700">
            {showForm ? "Cancel" : "+ Invite User"}
          </button>
        )}
      </div>

      {showForm && (
        <form onSubmit={e => { e.preventDefault(); addMutation.mutate(form); }}
          className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-4">
          <h2 className="font-semibold text-gray-800">Add User to {me?.tenantName}</h2>
          {error && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="users-username" className="block text-xs font-medium text-gray-600 mb-1">Username *</label>
              <input id="users-username" value={form.username} onChange={e => set("username", e.target.value)} required
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="users-email" className="block text-xs font-medium text-gray-600 mb-1">Email</label>
              <input id="users-email" type="email" value={form.email} onChange={e => set("email", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="users-password" className="block text-xs font-medium text-gray-600 mb-1">Password *</label>
              <input id="users-password" type="password" value={form.password} onChange={e => set("password", e.target.value)} required minLength={6}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400" />
            </div>
            <div>
              <label htmlFor="users-role" className="block text-xs font-medium text-gray-600 mb-1">Role</label>
              <select id="users-role" value={form.role} onChange={e => set("role", e.target.value)}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400">
                <option value="STAFF">Staff</option>
                <option value="MANAGER">Manager</option>
                <option value="ADMIN">Admin</option>
              </select>
            </div>
          </div>

          <button type="submit" disabled={addMutation.isPending}
            className="bg-brand-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {addMutation.isPending ? "Creating..." : "Create User"}
          </button>
        </form>
      )}

      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Username", "Email", "Role", "Status", "Created"].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {users.length === 0 && (
                  <tr><td colSpan={5} className="px-4 py-8 text-center text-gray-400">No users found</td></tr>
                )}
                {users.map((u: any) => (
                  <tr key={u.id} className={`hover:bg-gray-50 ${u.username === me?.username ? "bg-brand-50/40" : ""}`}>
                    <td className="px-4 py-3 font-medium">
                      {u.username} {u.username === me?.username && <span className="text-xs text-brand-400">(you)</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{u.email || "—"}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${ROLE_COLOR[u.role] ?? ROLE_COLOR.STAFF}`}>
                        {u.role}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${u.active ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-400"}`}>
                        {u.active ? "Active" : "Inactive"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs"><ClientDate iso={u.createdAt} /></td>
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
