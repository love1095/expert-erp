"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getUsersPage, addUser, getAccessRoles, assignAccessRoles, getSecuritySettings, setTwoFactorRequired, resetTwoFactor } from "@/lib/api";
import RolesCard from "./roles-card";
import { AccessRole } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { ClientDate } from "@/components/ClientDate";
import { Page } from "@/types";
import { useState } from "react";
import Pagination from "@/components/Pagination";
import { useConfirm } from "@/components/ConfirmDialog";

const ROLE_COLOR: Record<string, string> = {
  SUPER_ADMIN: "bg-red-100 text-red-700",
  ADMIN:       "bg-brand-100 text-brand-700",
  MANAGER:     "bg-teal-100 text-teal-700",
  STAFF:       "bg-gray-100 text-gray-500",
};

const EMPTY = { username: "", email: "", password: "", role: "STAFF" };

export default function UsersPage() {
  const ask = useConfirm();
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
  const { data: roles = [] } = useQuery<AccessRole[]>({ queryKey: ["access-roles"], queryFn: getAccessRoles, enabled: isAdmin });
  // Phase I5: the organisation's two-step sign-in requirement, and resetting someone's (lost phone)
  const { data: security } = useQuery<{ twoFactorRequired: boolean }>({ queryKey: ["security-settings"], queryFn: getSecuritySettings, enabled: isAdmin });
  const require2fa = useMutation({
    mutationFn: setTwoFactorRequired,
    onSuccess: s => qc.setQueryData(["security-settings"], s),
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Could not change it"),
  });
  const reset2fa = useMutation({
    mutationFn: resetTwoFactor,
    onSuccess: () => setError(""),
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Could not reset it"),
  });
  // Q5: several roles; the first is chosen in the list, others ticked ("also")
  const assign = useMutation({
    mutationFn: ({ userId, roleIds }: { userId: number; roleIds: number[] }) => assignAccessRoles(userId, roleIds),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["users-page"] }); qc.invalidateQueries({ queryKey: ["access-roles"] }); },
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } }).response?.data?.error || "Could not change the role"),
  });

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Users</h1>
          <p className="text-gray-500 text-sm mt-0.5">Members of {me?.tenantName}</p>
        </div>
        {isAdmin && (
          <button onClick={() => { setShowForm(!showForm); if (showForm) { setForm(EMPTY); setError(""); } }}
            className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700">
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
            className="bg-brand-600 text-white px-5 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
            {addMutation.isPending ? "Creating..." : "Create User"}
          </button>
        </form>
      )}

      {isAdmin && security && (
        <label className="flex items-center gap-2 mb-4 text-sm bg-white border border-gray-200 rounded-xl px-4 py-3" data-testid="require-two-factor">
          <input type="checkbox" checked={security.twoFactorRequired} disabled={require2fa.isPending}
            onChange={async e => { if (!e.target.checked || await ask({ title: "Require two-step sign-in? Everyone without it sets it up at their next sign-in.", action: "Require" })) require2fa.mutate(e.target.checked); }} />
          <span><b>Two-step sign-in required</b> for everyone in {me?.tenantName} (a code from an authenticator app after the password)</span>
        </label>
      )}
      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
            <table className="list-table">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {["Username", "Email", "Role", "Access", "Status", "Created"].map(h => (
                    <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {users.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">No users found</td></tr>
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
                      {u.role === "ADMIN" || u.role === "SUPER_ADMIN" ? <span className="text-xs text-gray-500">Everything (admin)</span>
                        : isAdmin ? (<>
                          <select aria-label={`Access role of ${u.username}`} className="border border-gray-300 rounded-md px-2 py-1 text-sm bg-white"
                            value={u.accessRoleId ?? ""} onChange={e => {
                              const first = e.target.value ? Number(e.target.value) : null;
                              const others = ((u.accessRoleIds ?? []) as number[]).filter(id => id !== first && id !== u.accessRoleId);
                              assign.mutate({ userId: u.id, roleIds: first == null ? [] : [first, ...others] });
                            }}>
                            <option value="">Default for {u.role === "MANAGER" ? "Manager" : "Staff"}</option>
                            {roles.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                          </select>
                          {u.accessRoleId != null && <div className="mt-1 flex flex-wrap gap-x-3 text-xs text-gray-600">
                            {roles.filter(r => r.id !== u.accessRoleId).map(r => {
                              const on = ((u.accessRoleIds ?? []) as number[]).includes(r.id);
                              return <label key={r.id} className="flex items-center gap-1">
                                <input type="checkbox" aria-label={`Also ${r.name} for ${u.username}`} checked={on} onChange={() => assign.mutate({ userId: u.id,
                                  roleIds: [u.accessRoleId!, ...((u.accessRoleIds ?? []) as number[]).filter(id => id !== u.accessRoleId && id !== r.id), ...(on ? [] : [r.id])] })} />
                                also {r.name}</label>;
                            })}
                          </div>}
                          </>
                        ) : <span className="text-xs text-gray-500">{((u.accessRoleIds ?? []) as number[]).map(id => roles.find(r => r.id === id)?.name).filter(Boolean).join(", ") || "Default"}</span>}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded ${u.active ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-400"}`}>
                        {u.active ? "Active" : "Inactive"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs">
                      <ClientDate iso={u.createdAt} />
                      {isAdmin && u.username !== me?.username && (
                        <button type="button" className="ml-3 text-xs text-red-600 hover:underline"
                          onClick={async () => { if (await ask({ title: `Reset two-step sign-in for ${u.username}? Use it when they lost their phone.`, action: "Reset", danger: true })) reset2fa.mutate(u.id); }}>
                          Reset two-step sign-in
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPageChange={setPage} />
          {isAdmin && <RolesCard />}
        </>
      )}
    </div>
  );
}
