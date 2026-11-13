"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createAccessRole, deleteAccessRole, getAccessRoles, getPermissionCatalogue, updateAccessRole } from "@/lib/api";
import { AccessRole, PermissionInfo } from "@/types";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { useConfirm } from "@/components/ConfirmDialog";

function errorText(e: unknown, fallback: string) {
  return (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;
}

// The ticks that only read (Cin7's "Read-only access" button)
const READ_ONLY = ["SALE_VIEW", "CUSTOMERS", "PURCHASE_VIEW", "PRODUCT_VIEW", "DASHBOARD"];

/**
 * Phase I1: roles like Cin7 Core's Settings → Users & Roles. Each role is a set of ticked permissions, grouped by module;
 * "Full access" ticks all, "Read-only access" only the viewing ones. Admins always have everything.
 */
export default function RolesCard() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data: catalogue = [] } = useQuery<PermissionInfo[]>({ queryKey: ["permission-catalogue"], queryFn: getPermissionCatalogue });
  const { data: roles = [] } = useQuery<AccessRole[]>({ queryKey: ["access-roles"], queryFn: getAccessRoles });
  const [selected, setSelected] = useState<number | "new" | null>(null);
  const [draft, setDraft] = useState<{ name: string; permissions: string[] } | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const role = typeof selected === "number" ? roles.find(r => r.id === selected) : undefined;
  const shown = draft ?? (role ? { name: role.name, permissions: role.permissions } : selected === "new" ? { name: "", permissions: [] } : null);
  const groups = [...new Set(catalogue.map(p => p.group))];

  const done = (text: string) => { qc.invalidateQueries({ queryKey: ["access-roles"] }); setDraft(null); setMessage({ ok: true, text }); };
  const save = useMutation({
    mutationFn: () => (selected === "new" ? createAccessRole(shown!) : updateAccessRole(selected as number, shown!)),
    onSuccess: (r: AccessRole) => { setSelected(r.id); done("Saved"); },
    onError: e => setMessage({ ok: false, text: errorText(e, "Could not save the role") }),
  });
  const remove = useMutation({
    mutationFn: (id: number) => deleteAccessRole(id),
    onSuccess: () => { setSelected(null); done("Role removed"); },
    onError: e => setMessage({ ok: false, text: errorText(e, "Could not remove the role") }),
  });
  const edit = (patch: Partial<{ name: string; permissions: string[] }>) => { setMessage(null); setDraft({ ...shown!, ...patch }); };
  const toggle = (key: string) => edit({ permissions: shown!.permissions.includes(key) ? shown!.permissions.filter(p => p !== key) : [...shown!.permissions, key] });

  return (
    <section className="bg-white border border-gray-200 rounded-xl mt-6" data-testid="roles">
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <div>
          <h2 className="font-semibold text-gray-800">Roles</h2>
          <p className="text-xs text-gray-500">What each role lets its users see and do, like Cin7&apos;s Users &amp; Roles. Admins always have everything.</p>
        </div>
        {shown && (
          <div className="flex gap-2">
            <button type="button" className={BTN_SECONDARY} onClick={() => edit({ permissions: catalogue.map(p => p.key) })}>Full access</button>
            <button type="button" className={BTN_SECONDARY} onClick={() => edit({ permissions: READ_ONLY })}>Read-only access</button>
            <button data-confirm-save type="button" className={BTN_PRIMARY} disabled={save.isPending || !shown.name.trim()} onClick={() => save.mutate()}>Save</button>
            {draft && <button type="button" className={BTN_SECONDARY} onClick={() => { setDraft(null); if (selected === "new") setSelected(null); }}>Cancel</button>}
          </div>
        )}
      </div>
      {message && <p role={message.ok ? "status" : "alert"} className={`px-6 pt-3 text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.text}</p>}
      <div className="grid md:grid-cols-[16rem_1fr]">
        <div className="border-r border-gray-200 p-4 space-y-1">
          <button type="button" className={`${BTN_SECONDARY} w-full mb-2`} onClick={() => { setSelected("new"); setDraft(null); setMessage(null); }}>+ Add new role</button>
          {roles.map(r => (
            <div key={r.id} className={`flex items-center justify-between rounded-md px-2 py-1.5 ${selected === r.id ? "bg-brand-50" : "hover:bg-gray-50"}`}>
              <button type="button" className="text-sm font-medium text-left flex-1" onClick={() => { setSelected(r.id); setDraft(null); setMessage(null); }}>
                {r.name} <span className="text-xs text-gray-400">({r.users})</span>
              </button>
              <button type="button" aria-label={`Remove role ${r.name}`} className="text-gray-400 hover:text-red-600 text-sm px-1"
                onClick={async () => { if (await ask({ title: `Remove the role ${r.name}?`, action: "Remove", danger: true })) remove.mutate(r.id); }}>×</button>
            </div>
          ))}
        </div>
        <div className="p-4">
          {!shown && <p className="text-sm text-gray-500 bg-brand-50 rounded px-3 py-2">Please select a role to edit permissions.</p>}
          {shown && (
            <>
              <label htmlFor="role-name" className="block text-xs font-medium text-gray-600 mb-1">Role name</label>
              <input id="role-name" className="border border-gray-300 rounded-md px-3 py-2 text-sm w-72 mb-4" maxLength={60}
                value={shown.name} onChange={e => edit({ name: e.target.value })} />
              {groups.map(g => (
                <fieldset key={g} className="mb-3">
                  <legend className="text-xs font-semibold text-gray-500 uppercase tracking-wide bg-gray-50 w-full px-2 py-1 rounded">{g}</legend>
                  {catalogue.filter(p => p.group === g).map(p => (
                    <label key={p.key} className="flex items-center gap-2 px-2 py-1 text-sm">
                      <input type="checkbox" checked={shown.permissions.includes(p.key)} onChange={() => toggle(p.key)} />
                      {p.label}
                    </label>
                  ))}
                </fieldset>
              ))}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
