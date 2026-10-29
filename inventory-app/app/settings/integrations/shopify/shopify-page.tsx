"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getShopifyConnection, saveShopifyConnection, testShopifyConnection, deleteShopifyConnection } from "@/lib/api";
import { ShopifyConnection } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import ShopifyOrders from "./shopify-orders";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400";

const STATUS: Record<ShopifyConnection["status"], { label: string; color: string }> = {
  CONNECTED:  { label: "Connected",  color: "bg-emerald-100 text-emerald-700" },
  ERROR:      { label: "Not working", color: "bg-red-100 text-red-700" },
  NOT_TESTED: { label: "Not tested",  color: "bg-gray-100 text-gray-600" },
};

export default function ShopifyPage() {
  const qc = useQueryClient();
  const { data: conn, isLoading, error: loadError } = useQuery<ShopifyConnection | null>({
    queryKey: ["shopify-connection"],
    queryFn: getShopifyConnection,
    retry: false,
  });

  const [form, setForm] = useState({ shopDomain: "", clientId: "", clientSecret: "" });
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  // Outcome of the last save / test, shown until the next action
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (conn) setForm({ shopDomain: conn.shopDomain, clientId: conn.clientId, clientSecret: "" });
  }, [conn]);

  const onResult = (c: ShopifyConnection) => {
    qc.setQueryData(["shopify-connection"], c);
    const time = new Date().toLocaleTimeString();
    setResult(c.status === "CONNECTED"
      ? { ok: true, text: `Connection works — reached ${c.shopName ?? c.shopDomain} at ${time}.` }
      : { ok: false, text: `Connection test failed at ${time}. See the reason below.` });
    setEditing(false);
    setError("");
    setForm(f => ({ ...f, clientSecret: "" }));
  };
  const onError = (e: any) => { setResult(null); setError(e.response?.data?.error || "Request failed"); };

  const saveMutation = useMutation({ mutationFn: saveShopifyConnection, onSuccess: onResult, onError });
  const testMutation = useMutation({ mutationFn: testShopifyConnection, onSuccess: onResult, onError });
  const deleteMutation = useMutation({
    mutationFn: deleteShopifyConnection,
    onSuccess: () => {
      qc.setQueryData(["shopify-connection"], null);
      setForm({ shopDomain: "", clientId: "", clientSecret: "" });
      setError("");
    },
    onError,
  });

  const forbidden = (loadError as any)?.response?.status === 403;
  const showForm = !conn || editing;
  const busy = saveMutation.isPending || testMutation.isPending;

  return (
    <div className="max-w-3xl">
      <nav aria-label="Breadcrumb" className="text-xs text-gray-500 mb-2">
        <Link href="/settings/integrations" className="hover:text-gray-800">Integrations</Link>
        <span className="mx-1.5">/</span>
        <span className="text-gray-700">Shopify</span>
      </nav>
      <h1 className="text-2xl font-bold">Shopify</h1>
      <p className="text-gray-500 text-sm mt-0.5 mb-6">Connect your Shopify store so its orders, fulfilments and stock stay in step with the ERP.</p>

      {forbidden ? (
        <p className="text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg px-4 py-3">Only an ADMIN can manage the Shopify connection.</p>
      ) : isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <div className="space-y-5">
          {result && (
            <p role="status" data-testid="shopify-test-result"
              className={`text-sm px-3 py-2 rounded ${result.ok ? "text-emerald-800 bg-emerald-50" : "text-red-700 bg-red-50"}`}>
              {result.ok ? "✓ " : "✕ "}{result.text}
            </p>
          )}
          {error && <p role="alert" data-testid="shopify-error" className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{error}</p>}

          {conn && (
            <section data-testid="shopify-status" className="bg-white border border-gray-200 rounded-xl p-6">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-medium text-gray-500">Store</p>
                  <p className="text-lg font-semibold">{conn.shopName ?? conn.shopDomain}</p>
                  <p className="text-sm text-gray-500">{conn.shopDomain}{conn.shopCurrency ? ` · ${conn.shopCurrency}` : ""}</p>
                </div>
                <span className={`text-xs font-semibold px-2 py-0.5 rounded ${STATUS[conn.status].color}`}>{STATUS[conn.status].label}</span>
              </div>

              {conn.lastError && (
                <p className="mt-4 text-sm text-red-700 bg-red-50 px-3 py-2 rounded" data-testid="shopify-last-error">{conn.lastError}</p>
              )}
              {conn.status === "CONNECTED" && conn.missingScopes.length > 0 && (
                <div className="mt-4 text-sm text-amber-800 bg-amber-50 px-3 py-2 rounded">
                  The app is missing these permissions: <b>{conn.missingScopes.join(", ")}</b>. Add them in the Dev Dashboard,
                  release a new version, then test again.
                </div>
              )}

              <dl className="mt-4 grid grid-cols-3 gap-4 text-xs">
                <div><dt className="text-gray-500">Client ID</dt><dd className="font-mono text-gray-700 truncate">{conn.clientId}</dd></div>
                <div><dt className="text-gray-500">Last checked</dt><dd className="text-gray-700">{conn.lastCheckedAt ? <ClientDate iso={conn.lastCheckedAt} showTime /> : "—"}</dd></div>
                <div><dt className="text-gray-500">API version</dt><dd className="text-gray-700">{conn.apiVersion}</dd></div>
              </dl>

              <div className="mt-5 flex gap-2">
                <button onClick={() => { setResult(null); testMutation.mutate(); }} disabled={busy}
                  className="bg-emerald-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-emerald-700 disabled:opacity-50">
                  {testMutation.isPending ? "Testing..." : "Test connection"}
                </button>
                {!editing && (
                  <button onClick={() => setEditing(true)} className="border border-gray-300 text-gray-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-50">
                    Change credentials
                  </button>
                )}
                <button onClick={() => { if (confirm(`Disconnect ${conn.shopDomain}? The saved credentials are deleted.`)) deleteMutation.mutate(); }}
                  className="ml-auto text-sm text-red-500 hover:text-red-700">
                  Disconnect
                </button>
              </div>
            </section>
          )}

          {conn && !editing && <ShopifyOrders conn={conn} />}

          {showForm && (
            <form onSubmit={e => { e.preventDefault(); saveMutation.mutate(form); }}
              className="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
              <h2 className="font-semibold text-gray-800">{conn ? "Change credentials" : "Connect a store"}</h2>
              <ol className="text-xs text-gray-500 list-decimal ml-4 space-y-0.5">
                <li>In the Shopify Dev Dashboard, create an app with the scopes below, release a version and install it on your store.</li>
                <li>The store must be in the same Dev Dashboard organization as the app.</li>
                <li>Copy the client ID and client secret from the app's Settings page.</li>
              </ol>

              <div>
                <label htmlFor="shopify-domain" className="block text-xs font-medium text-gray-600 mb-1">Store address *</label>
                <input id="shopify-domain" value={form.shopDomain} required placeholder="your-store.myshopify.com"
                  onChange={e => setForm({ ...form, shopDomain: e.target.value })} className={INPUT} />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="shopify-client-id" className="block text-xs font-medium text-gray-600 mb-1">Client ID *</label>
                  <input id="shopify-client-id" value={form.clientId} required autoComplete="off"
                    onChange={e => setForm({ ...form, clientId: e.target.value })} className={`${INPUT} font-mono`} />
                </div>
                <div>
                  <label htmlFor="shopify-client-secret" className="block text-xs font-medium text-gray-600 mb-1">
                    Client secret {conn ? "" : "*"}
                  </label>
                  <input id="shopify-client-secret" type="password" value={form.clientSecret} required={!conn} autoComplete="new-password"
                    placeholder={conn ? "Saved — leave empty to keep it" : ""}
                    onChange={e => setForm({ ...form, clientSecret: e.target.value })} className={`${INPUT} font-mono`} />
                </div>
              </div>
              <p className="text-xs text-gray-400">
                The secret is stored encrypted and never shown again. Scopes needed: read_orders, read_customers, read_products,
                read_inventory, write_inventory, read_locations, read/write_merchant_managed_fulfillment_orders, read_returns.
              </p>

              <div className="flex gap-2">
                <button type="submit" disabled={busy}
                  className="bg-emerald-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-emerald-700 disabled:opacity-50">
                  {saveMutation.isPending ? "Saving and testing..." : "Save & test connection"}
                </button>
                {conn && (
                  <button type="button" onClick={() => { setEditing(false); setError(""); setForm({ shopDomain: conn.shopDomain, clientId: conn.clientId, clientSecret: "" }); }}
                    className="text-sm text-gray-500 hover:text-gray-800 px-3">Cancel</button>
                )}
              </div>
            </form>
          )}
        </div>
      )}
    </div>
  );
}
