"use client";

import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getOrganisations, setOrganisationSubscription, setOrganisationActive } from "@/lib/api";
import { PlatformOrganisation } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { useConfirm } from "@/components/ConfirmDialog";

const PLANS: PlatformOrganisation["planTier"][] = ["FREE", "PRO", "ENTERPRISE"];
const STATUSES: PlatformOrganisation["status"][] = ["ACTIVE", "CANCELLED", "EXPIRED"];
const SELECT = "border border-gray-300 rounded-md px-2 py-1 text-sm bg-white";

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.error ?? "Something went wrong";
}

function Row({ org, onError }: { org: PlatformOrganisation; onError: (m: string) => void }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ["platform-organisations"] });
  const plan = useMutation({
    mutationFn: (body: { planTier: string; status: string }) => setOrganisationSubscription(org.id, body),
    onSuccess: refresh, onError: e => onError(errorText(e)),
  });
  const active = useMutation({
    mutationFn: (on: boolean) => setOrganisationActive(org.id, on),
    onSuccess: refresh, onError: e => onError(errorText(e)),
  });

  return (
    <tr data-testid={`organisation-${org.slug}`} className={`border-t border-gray-100 ${org.active ? "" : "bg-gray-50 text-gray-400"}`}>
      <td className="px-4 py-2">
        <div className="font-medium text-gray-900">{org.name}</div>
        <div className="text-xs text-gray-400">{org.slug}</div>
      </td>
      <td className="px-4 py-2">
        <select aria-label={`Plan of ${org.name}`} className={SELECT} value={org.planTier} disabled={plan.isPending}
          onChange={e => plan.mutate({ planTier: e.target.value, status: org.status })}>
          {PLANS.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
      </td>
      <td className="px-4 py-2">
        <select aria-label={`Subscription status of ${org.name}`} className={SELECT} value={org.status} disabled={plan.isPending}
          onChange={e => plan.mutate({ planTier: org.planTier, status: e.target.value })}>
          {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </td>
      <td className="px-4 py-2 text-right tabular-nums">{org.users}</td>
      <td className="px-4 py-2 text-right tabular-nums">{org.products.toLocaleString()}</td>
      <td className="px-4 py-2 text-right tabular-nums">{org.warehouses}</td>
      <td className="px-4 py-2"><ClientDate iso={org.createdAt} /></td>
      <td className="px-4 py-2 text-right">
        <button type="button" disabled={active.isPending}
          onClick={async () => {
            if (!org.active || await ask({ title: `Switch off ${org.name}? Its people cannot log in and its API keys stop until it is switched on again.`, action: "Switch off", danger: true })) {
              active.mutate(!org.active);
            }
          }}
          className={`text-sm hover:underline ${org.active ? "text-red-600" : "text-emerald-700"}`}>
          {org.active ? "Switch off" : "Switch on"}
        </button>
      </td>
    </tr>
  );
}

export default function OrganisationsPage() {
  const { data = [], isLoading, error } = useQuery<PlatformOrganisation[]>({
    queryKey: ["platform-organisations"],
    queryFn: getOrganisations,
    retry: false,
  });
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const shown = useMemo(() => {
    const q = search.trim().toLowerCase();
    return q ? data.filter(o => o.name.toLowerCase().includes(q) || o.slug.includes(q)) : data;
  }, [data, search]);
  const forbidden = (error as { response?: { status?: number } } | null)?.response?.status === 403;

  return (
    <div className="max-w-6xl">
      <h1 className="text-2xl font-bold">Organisations</h1>
      <p className="text-gray-500 text-sm mt-0.5 mb-4">
        Every organisation on Expert ERP has one subscription. Give it a plan here; its own admins see the plan but cannot change it.
      </p>
      {forbidden ? (
        <p className="text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg px-4 py-3">Only the platform&apos;s super admin sees this page.</p>
      ) : (
        <>
          <div className="flex items-center gap-3 mb-3">
            <input aria-label="Search organisations" placeholder="Search organisations" value={search} onChange={e => setSearch(e.target.value)}
              className="border border-gray-300 rounded-md px-3 py-1.5 text-sm w-72" />
            <span className="text-sm text-gray-500">{shown.length} of {data.length}</span>
            {message && <span role="alert" className="text-sm text-red-600">{message}</span>}
          </div>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-xs text-gray-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Organisation</th>
                  <th className="px-4 py-2 text-left font-medium">Plan</th>
                  <th className="px-4 py-2 text-left font-medium">Subscription</th>
                  <th className="px-4 py-2 text-right font-medium">Users</th>
                  <th className="px-4 py-2 text-right font-medium">Products</th>
                  <th className="px-4 py-2 text-right font-medium">Warehouses</th>
                  <th className="px-4 py-2 text-left font-medium">Since</th>
                  <th className="px-4 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {isLoading && <tr><td colSpan={8} className="px-4 py-6 text-center text-gray-400">Loading...</td></tr>}
                {shown.map(o => <Row key={o.id} org={o} onError={setMessage} />)}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
