"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getWarehouses, saveShopifySettings, registerShopifyWebhooks, importShopifyOrders, getShopifyEvents } from "@/lib/api";
import { ShopifyConnection, ShopifyImportResult, ShopifyWebhookEvent, Warehouse } from "@/types";
import { ClientDate } from "@/components/ClientDate";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400";
const BUTTON = "bg-emerald-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-emerald-700 disabled:opacity-50";
const BUTTON_SECONDARY = "border border-gray-300 text-gray-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-50 disabled:opacity-50";

const EVENT_COLOR: Record<ShopifyWebhookEvent["status"], string> = {
  PROCESSED: "bg-emerald-100 text-emerald-700",
  IGNORED:   "bg-gray-100 text-gray-500",
  RECEIVED:  "bg-sky-100 text-sky-700",
  FAILED:    "bg-red-100 text-red-700",
};

/** Order import (7.2): which warehouse ships Shopify orders, webhooks, catch-up import, recent deliveries. */
export default function ShopifyOrders({ conn }: { conn: ShopifyConnection }) {
  const qc = useQueryClient();
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: events = [], refetch: refetchEvents, isFetching: eventsLoading } = useQuery<ShopifyWebhookEvent[]>({
    queryKey: ["shopify-events"],
    queryFn: getShopifyEvents,
  });

  const [warehouseId, setWarehouseId] = useState("");
  const [publicUrl, setPublicUrl] = useState("");
  const [days, setDays] = useState(30);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [importResult, setImportResult] = useState<ShopifyImportResult | null>(null);

  useEffect(() => {
    setWarehouseId(conn.defaultWarehouseId ? String(conn.defaultWarehouseId) : "");
    setPublicUrl(conn.publicUrl ?? "");
  }, [conn.defaultWarehouseId, conn.publicUrl]);

  const onConn = (c: ShopifyConnection, text: string) => {
    qc.setQueryData(["shopify-connection"], c);
    setMessage({ ok: true, text });
  };
  const onError = (e: any) => setMessage({ ok: false, text: e.response?.data?.error || "Request failed" });

  const settingsMutation = useMutation({
    mutationFn: saveShopifySettings,
    onSuccess: (c: ShopifyConnection) => onConn(c, "Order settings saved."),
    onError,
  });
  const webhooksMutation = useMutation({
    mutationFn: registerShopifyWebhooks,
    onSuccess: (c: ShopifyConnection) => onConn(c, `Webhooks registered: Shopify now sends new and updated orders to ${c.webhookUrl}.`),
    onError,
  });
  const importMutation = useMutation({
    mutationFn: importShopifyOrders,
    onSuccess: (r: ShopifyImportResult) => {
      setImportResult(r);
      setMessage(null);
      qc.invalidateQueries({ queryKey: ["sale-orders"] });
      qc.invalidateQueries({ queryKey: ["shopify-connection"] });
    },
    onError,
  });

  const settingsChanged = warehouseId !== (conn.defaultWarehouseId ? String(conn.defaultWarehouseId) : "")
    || publicUrl.trim() !== (conn.publicUrl ?? "");
  const busy = settingsMutation.isPending || webhooksMutation.isPending || importMutation.isPending;
  const connected = conn.status === "CONNECTED";

  return (
    <section data-testid="shopify-orders" className="bg-white border border-gray-200 rounded-xl p-6 space-y-6">
      <div>
        <h2 className="font-semibold text-gray-800">Orders</h2>
        <p className="text-xs text-gray-500 mt-0.5">
          Shopify orders become sale orders. Paid orders arrive as CONFIRMED, ready to ship; unpaid ones as DRAFT.
          Orders with unknown SKUs or another currency are kept as DRAFT and marked &quot;Needs review&quot;.
        </p>
      </div>

      {message && (
        <p role="status" data-testid="shopify-orders-message"
          className={`text-sm px-3 py-2 rounded ${message.ok ? "text-emerald-800 bg-emerald-50" : "text-red-700 bg-red-50"}`}>
          {message.ok ? "✓ " : "✕ "}{message.text}
        </p>
      )}

      {/* Settings */}
      <form onSubmit={e => { e.preventDefault(); settingsMutation.mutate({ defaultWarehouseId: warehouseId ? Number(warehouseId) : null, publicUrl }); }}
        className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="shopify-warehouse" className="block text-xs font-medium text-gray-600 mb-1">Ship Shopify orders from</label>
            <select id="shopify-warehouse" value={warehouseId} onChange={e => setWarehouseId(e.target.value)} className={INPUT}>
              <option value="">First warehouse ({warehouses[0]?.name ?? "none yet"})</option>
              {warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="shopify-public-url" className="block text-xs font-medium text-gray-600 mb-1">Public address (for webhooks)</label>
            <input id="shopify-public-url" value={publicUrl} onChange={e => setPublicUrl(e.target.value)}
              placeholder="https://something.trycloudflare.com" className={`${INPUT} font-mono`} />
            <p className="text-[11px] text-gray-400 mt-1">
              While developing, run <code className="bg-gray-100 px-1 rounded">make tunnel</code> and paste the address it prints.
            </p>
          </div>
        </div>
        <button type="submit" disabled={busy || !settingsChanged} className={BUTTON_SECONDARY}>
          {settingsMutation.isPending ? "Saving..." : "Save order settings"}
        </button>
      </form>

      {/* Webhooks */}
      <div className="border-t border-gray-100 pt-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h3 className="text-sm font-semibold text-gray-800">Live orders (webhooks)</h3>
            {conn.webhooksRegisteredAt ? (
              <p data-testid="shopify-webhooks-state" className="text-xs text-gray-600 mt-0.5">
                Registered <ClientDate iso={conn.webhooksRegisteredAt} showTime /> → <span className="font-mono">{conn.webhookUrl}</span>
                <br />Topics: {conn.webhookTopics.join(", ")}
              </p>
            ) : (
              <p data-testid="shopify-webhooks-state" className="text-xs text-amber-700 mt-0.5">
                Not registered — Shopify is not sending orders yet.
                {conn.publicUrl ? " Click Register webhooks." : " Save a public address first."}
              </p>
            )}
          </div>
          <button onClick={() => webhooksMutation.mutate()} disabled={busy || !connected || !conn.publicUrl || settingsChanged}
            title={settingsChanged ? "Save the order settings first" : undefined} className={BUTTON}>
            {webhooksMutation.isPending ? "Registering..." : "Register webhooks"}
          </button>
        </div>
      </div>

      {/* Catch-up import */}
      <div className="border-t border-gray-100 pt-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h3 className="text-sm font-semibold text-gray-800">Import recent orders</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Fetches orders Shopify has but the ERP may have missed (e.g. while this computer was off). Safe to repeat.
              {conn.lastImportAt && <> Last import: <ClientDate iso={conn.lastImportAt} showTime />.</>}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <label htmlFor="shopify-import-days" className="sr-only">Days</label>
            <select id="shopify-import-days" value={days} onChange={e => setDays(Number(e.target.value))}
              className="border border-gray-300 rounded-md px-2 py-2 text-sm">
              <option value={7}>Last 7 days</option>
              <option value={30}>Last 30 days</option>
              <option value={60}>Last 60 days</option>
            </select>
            <button onClick={() => importMutation.mutate(days)} disabled={busy || !connected} className={BUTTON}>
              {importMutation.isPending ? "Importing..." : "Import orders"}
            </button>
          </div>
        </div>
        {importResult && (
          <div data-testid="shopify-import-result" className="mt-3 text-sm bg-gray-50 rounded-lg px-4 py-3">
            <p>
              Found {importResult.fetched} order{importResult.fetched !== 1 ? "s" : ""}: {importResult.created} new,{" "}
              {importResult.updated} updated, {importResult.unchanged} already in progress
              {importResult.skipped > 0 && <>, {importResult.skipped} cancelled (skipped)</>}.
              {importResult.created + importResult.updated > 0 && <> <Link href="/sale-orders" className="text-emerald-700 hover:underline">See sale orders</Link></>}
            </p>
            {importResult.problems.length > 0 && (
              <ul className="mt-2 text-xs text-amber-800 list-disc ml-5 space-y-0.5">
                {importResult.problems.map((p, i) => <li key={i}>{p}</li>)}
              </ul>
            )}
          </div>
        )}
      </div>

      {/* Deliveries */}
      <div className="border-t border-gray-100 pt-5">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-semibold text-gray-800">Recent webhook deliveries</h3>
          <button onClick={() => refetchEvents()} className="text-xs text-emerald-700 hover:text-emerald-900">
            {eventsLoading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
        {events.length === 0 ? <p className="text-xs text-gray-400">Nothing received yet.</p> : (
          <table className="w-full text-xs" data-testid="shopify-events">
            <thead className="text-gray-500 text-left">
              <tr><th className="py-1">Received</th><th>Topic</th><th>Result</th><th>Details</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {events.map(e => (
                <tr key={e.id}>
                  <td className="py-1.5 text-gray-500 whitespace-nowrap"><ClientDate iso={e.receivedAt} showTime /></td>
                  <td className="font-mono">{e.topic}</td>
                  <td><span className={`font-semibold px-1.5 py-0.5 rounded ${EVENT_COLOR[e.status]}`}>{e.status}</span></td>
                  <td className="text-red-600">{e.error ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
