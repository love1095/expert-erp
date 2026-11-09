"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getApiAccess, createApiKey, revokeApiKey } from "@/lib/api";
import { ApiAccess, ApiKey, ApiKeyCreated } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { useConfirm } from "@/components/ConfirmDialog";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

const ROLES: { value: ApiKey["role"]; label: string }[] = [
  { value: "STAFF", label: "Staff: read and day-to-day work" },
  { value: "MANAGER", label: "Manager: also authorise and price" },
  { value: "ADMIN", label: "Admin: also delete" },
];

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.error
    ?? (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? "Something went wrong";
}

function CopyField({ label, value, testId }: { label: string; value: string; testId: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div>
      <span className={LABEL}>{label}</span>
      <div className="flex gap-2">
        <code data-testid={testId} className="flex-1 font-mono text-sm bg-gray-50 border border-gray-200 rounded-md px-3 py-2 break-all">{value}</code>
        <button type="button" onClick={() => navigator.clipboard?.writeText(value).then(() => setCopied(true))}
          className="text-sm px-3 py-1.5 rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50">
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
    </div>
  );
}

export default function ApiPage() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery<ApiAccess>({ queryKey: ["api-access"], queryFn: getApiAccess, retry: false });
  const [name, setName] = useState("");
  const [role, setRole] = useState<ApiKey["role"]>("STAFF");
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () => createApiKey({ name, role }),
    onSuccess: (r: ApiKeyCreated) => { setCreated(r); setName(""); setMessage(null); qc.invalidateQueries({ queryKey: ["api-access"] }); },
    onError: e => setMessage(errorText(e)),
  });
  const revoke = useMutation({
    mutationFn: (id: number) => revokeApiKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["api-access"] }),
    onError: e => setMessage(errorText(e)),
  });

  const forbidden = (error as { response?: { status?: number } } | null)?.response?.status === 403;

  return (
    <div className="max-w-4xl">
      <Link href="/settings/integrations" className="text-sm text-gray-500 hover:text-gray-700">← Integrations</Link>
      <h1 className="text-2xl font-bold mt-1">API</h1>
      <p className="text-gray-500 text-sm mt-0.5 mb-6">
        Let other systems read and change this organisation&apos;s data. Each call sends the Account ID and an Application Key
        in the headers <code className="text-xs">api-auth-accountid</code> and <code className="text-xs">api-auth-applicationkey</code>,
        and sees this organisation only.
      </p>

      {forbidden ? (
        <p className="text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg px-4 py-3">Only an ADMIN can manage API keys.</p>
      ) : isLoading || !data ? (
        <p className="text-sm text-gray-400">Loading...</p>
      ) : (
        <div className="space-y-6">
          <section className="bg-white border border-gray-200 rounded-xl p-5">
            <CopyField label="Account ID" value={data.accountId} testId="api-account-id" />
          </section>

          {created && (
            <section data-testid="api-key-created" className="bg-amber-50 border border-amber-300 rounded-xl p-5 space-y-3">
              <p className="text-sm font-semibold text-amber-900">
                Copy the key for &quot;{created.key.name}&quot; now: it is not shown again.
              </p>
              <CopyField label="Application Key" value={created.applicationKey} testId="api-application-key" />
              <button type="button" onClick={() => setCreated(null)} className="text-sm text-amber-900 hover:underline">I have copied it</button>
            </section>
          )}

          <section className="bg-white border border-gray-200 rounded-xl p-5">
            <h2 className="font-semibold text-gray-900 mb-3">New Application Key</h2>
            <form className="grid grid-cols-1 md:grid-cols-[1fr_1fr_auto] gap-3 items-end"
              onSubmit={e => { e.preventDefault(); create.mutate(); }}>
              <div>
                <label htmlFor="api-key-name" className={LABEL}>Name</label>
                <input id="api-key-name" maxLength={100} value={name} onChange={e => setName(e.target.value)}
                  placeholder="e.g. Warehouse scanner" className={INPUT} />
              </div>
              <div>
                <label htmlFor="api-key-role" className={LABEL}>Can do what</label>
                <select id="api-key-role" value={role} onChange={e => setRole(e.target.value as ApiKey["role"])} className={INPUT}>
                  {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
                </select>
              </div>
              <button type="submit" disabled={create.isPending || !name.trim()}
                className="bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">
                {create.isPending ? "Creating..." : "Create key"}
              </button>
            </form>
            <p className="text-[11px] text-gray-400 mt-2">Keys never reach users, the subscription, settings or integrations: those need a person to log in.</p>
            {message && <p role="alert" className="text-sm text-red-600 mt-2">{message}</p>}
          </section>

          <section className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm" data-testid="api-keys">
              <thead className="bg-gray-50 text-xs text-gray-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Name</th>
                  <th className="px-4 py-2 text-left font-medium">Key</th>
                  <th className="px-4 py-2 text-left font-medium">Role</th>
                  <th className="px-4 py-2 text-left font-medium">Created</th>
                  <th className="px-4 py-2 text-left font-medium">Last used</th>
                  <th className="px-4 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {data.keys.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-6 text-center text-gray-400">No keys yet</td></tr>
                )}
                {data.keys.map(k => (
                  <tr key={k.id} className={`border-t border-gray-100 ${k.revokedAt ? "text-gray-400" : ""}`}>
                    <td className="px-4 py-2 font-medium">{k.name}</td>
                    <td className="px-4 py-2 font-mono text-xs">{k.keyPrefix}…</td>
                    <td className="px-4 py-2">{k.role}</td>
                    <td className="px-4 py-2"><ClientDate iso={k.createdAt} /> <span className="text-gray-400">by {k.createdBy}</span></td>
                    <td className="px-4 py-2">{k.lastUsedAt ? <ClientDate iso={k.lastUsedAt} showTime /> : "Never"}</td>
                    <td className="px-4 py-2 text-right">
                      {k.revokedAt
                        ? <span className="text-xs">Revoked <ClientDate iso={k.revokedAt} /></span>
                        : <button type="button" onClick={async () => {
                            if (await ask({ title: `Revoke "${k.name}"? Systems using it stop working at once.`, action: "Revoke", danger: true })) revoke.mutate(k.id);
                          }} className="text-sm text-red-600 hover:underline">Revoke</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>
      )}
    </div>
  );
}
