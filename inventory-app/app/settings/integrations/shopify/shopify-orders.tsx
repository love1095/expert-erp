"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getWarehouses, saveShopifySettings, registerShopifyWebhooks } from "@/lib/api";
import { ShopifyConnection, Warehouse } from "@/types";
import { ClientDate } from "@/components/ClientDate";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-400";
const BUTTON = "bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50";
const BUTTON_SECONDARY = "border border-gray-300 text-gray-700 px-4 py-2 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50";


/** Order import (7.2): which warehouse ships Shopify orders, and webhooks. */
export default function ShopifyOrders({ conn }: { conn: ShopifyConnection }) {
  const qc = useQueryClient();
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });

  const [warehouseId, setWarehouseId] = useState("");
  const [publicUrl, setPublicUrl] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

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

  const settingsChanged = warehouseId !== (conn.defaultWarehouseId ? String(conn.defaultWarehouseId) : "")
    || publicUrl.trim() !== (conn.publicUrl ?? "");
  const busy = settingsMutation.isPending || webhooksMutation.isPending;
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
              <option value="">Not chosen — uses {conn.fallbackWarehouseName ?? "the first warehouse created"}</option>
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

      {/* Catch-up import: on the Sale Orders page */}
      <p className="border-t border-gray-100 pt-5 text-xs text-gray-500">
        Missed orders (e.g. while this computer was off) can be fetched from{" "}
        <Link href="/sale-orders" className="text-emerald-700 hover:underline">Sale Orders → Import from Shopify</Link>.
      </p>

      {/* Deliveries are a log: on the Audit Log page */}
      <p className="border-t border-gray-100 pt-5 text-xs text-gray-500">
        What Shopify sent and how each delivery was handled:{" "}
        <Link href="/audit-log?tab=shopify-webhooks" className="text-emerald-700 hover:underline">Audit Log → Shopify webhooks</Link>.
      </p>
    </section>
  );
}
