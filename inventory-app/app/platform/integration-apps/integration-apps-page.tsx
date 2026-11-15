"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getXeroApp, removeXeroApp, saveXeroApp } from "@/lib/api";
import { XeroApp } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_PRIMARY, BTN_SECONDARY } from "@/components/DetailParts";
import { useConfirm } from "@/components/ConfirmDialog";
import { DateText } from "@/components/ui/DateText";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-400";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";
const errorOf = (e: unknown) => (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Something went wrong";

/**
 * Platform → Integration apps (super admin): the platform's own app at each outside system, one for every organisation.
 * Today the Expert ERP app at Xero: its client ID, client secret, redirect address and webhook key. An organisation
 * never sees these; it only connects its own Xero under Settings → Integrations → Xero.
 */
export default function IntegrationAppsPage() {
  return (
    <div className="max-w-3xl">
      <PageHeader title="Integration apps" subtitle="The Expert ERP app at each outside system. Set once here; every organisation connects its own account with it." />
      <XeroAppCard />
    </div>
  );
}

function XeroAppCard() {
  const { data: app } = useQuery<XeroApp>({ queryKey: ["platform-xero-app"], queryFn: getXeroApp });
  // Keyed on the saved state, so the boxes start again from what the server holds after each save
  const [msg, setMsg] = useState<Msg>(null);
  return app ? <XeroAppForm key={`${app.updatedAt}-${app.clientId}`} app={app} msg={msg} setMsg={setMsg} /> : null;
}

type Msg = { ok: boolean; text: string } | null;

function XeroAppForm({ app, msg, setMsg }: { app: XeroApp; msg: Msg; setMsg: (m: Msg) => void }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [form, setForm] = useState({ clientId: app.clientId, clientSecret: "", redirectUri: app.redirectUri || defaultRedirect(), webhookKey: "" });
  const done = (text: string) => (a: XeroApp) => { qc.setQueryData(["platform-xero-app"], a); setMsg({ ok: true, text }); };
  const save = useMutation({ mutationFn: () => saveXeroApp(form), onSuccess: done("Saved"), onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const remove = useMutation({ mutationFn: removeXeroApp, onSuccess: done("Removed"), onError: e => setMsg({ ok: false, text: errorOf(e) }) });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm(f => ({ ...f, [k]: e.target.value }));

  return (
    <section className="bg-white border border-gray-200 rounded-xl p-6 space-y-4" data-testid="xero-app">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-semibold text-gray-900">Xero</h2>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${app.configured ? "bg-emerald-100 text-emerald-700" : "bg-gray-100 text-gray-600"}`} data-testid="xero-app-status">
          {app.configured ? "Set up" : "Not set up"}</span>
        <span className={`text-xs px-2 py-0.5 rounded ${app.mode === "live" ? "bg-green-50 text-green-700" : "bg-amber-50 text-amber-800"}`}>
          {app.mode === "live" ? "Live Xero" : "Fake Xero (test mode)"}</span>
        <span className="text-sm text-gray-500 ml-auto" data-testid="xero-app-connected">
          {app.connectedOrganisations} {app.connectedOrganisations === 1 ? "organisation" : "organisations"} connected</span>
      </div>
      <p className="text-sm text-gray-600">
        Make one app of type <b>Web app</b> at developer.xero.com (My Apps) with the redirect address below, then copy its client ID and
        secret here. Each organisation then signs in to its own Xero under Settings → Integrations → Xero.
      </p>
      {app.mode === "fake" && <p className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
        This server runs the fake Xero: nothing leaves it and the app below is not used. Start the server with APP_XERO_MODE=live to use it.</p>}
      {app.fromServerSettings && <p className="text-xs text-gray-500">Read from the server&apos;s own settings (APP_XERO_…). Saving here takes their place.</p>}

      <form className="grid grid-cols-1 sm:grid-cols-2 gap-4" data-confirm="none" onSubmit={async e => {
        e.preventDefault();
        if (await ask({ title: "Save the Xero app?", message: "Every organisation's Xero connection uses it from now on.", action: "Save" })) save.mutate();
      }}>
        <div>
          <label className={LABEL} htmlFor="xa-id">Client ID</label>
          <input id="xa-id" className={INPUT} value={form.clientId} onChange={set("clientId")} autoComplete="off" required />
        </div>
        <div>
          <label className={LABEL} htmlFor="xa-secret">Client secret</label>
          <input id="xa-secret" type="password" className={INPUT} value={form.clientSecret} onChange={set("clientSecret")} autoComplete="new-password"
            placeholder={app.clientSecretSet ? "Saved. Leave empty to keep it" : ""} required={!app.clientSecretSet} />
          <p className="text-xs text-gray-500 mt-1">Kept encrypted and never shown again.</p>
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL} htmlFor="xa-redirect">Redirect address (OAuth 2.0 redirect URI)</label>
          <input id="xa-redirect" className={INPUT} value={form.redirectUri} onChange={set("redirectUri")} required />
          <p className="text-xs text-gray-500 mt-1">Must be exactly the redirect URI typed in the Xero app: this site&apos;s address + /settings/integrations/xero/callback</p>
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL} htmlFor="xa-hook">Webhook key (optional)</label>
          <input id="xa-hook" type="password" className={INPUT} value={form.webhookKey} onChange={set("webhookKey")} autoComplete="new-password"
            placeholder={app.webhookKeySet ? "Saved. Leave empty to keep it" : ""} />
          <p className="text-xs text-gray-500 mt-1">From the Xero app&apos;s Webhooks page. Without it, payments made in Xero are still checked every 5 minutes.</p>
        </div>
        <div className="sm:col-span-2 flex flex-wrap items-center gap-3">
          <button type="submit" className={BTN_PRIMARY} disabled={save.isPending}>Save</button>
          {app.updatedAt && <button type="button" className={BTN_SECONDARY} disabled={remove.isPending} onClick={async () => {
            if (await ask({ title: "Remove the Xero app?", message: "Organisations already connected stay connected until their sign-in runs out; nobody can connect anew.", action: "Remove", danger: true })) remove.mutate();
          }}>Remove</button>}
          {msg && <span role={msg.ok ? "status" : "alert"} className={`text-sm ${msg.ok ? "text-green-700" : "text-red-700"}`}>{msg.text}</span>}
          {app.updatedAt && <span className="text-xs text-gray-400 ml-auto">Last changed <DateText iso={app.updatedAt} />{app.updatedBy && ` by ${app.updatedBy}`}</span>}
        </div>
      </form>
    </section>
  );
}

function defaultRedirect() {
  return typeof window === "undefined" ? "" : `${window.location.origin}/settings/integrations/xero/callback`;
}
