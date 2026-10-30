"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getShopifyEvents } from "@/lib/api";
import { ShopifyWebhookEvent } from "@/types";
import { ClientDate } from "@/components/ClientDate";

const EVENT_COLOR: Record<ShopifyWebhookEvent["status"], string> = {
  PROCESSED: "bg-emerald-100 text-emerald-700",
  IGNORED:   "bg-gray-100 text-gray-500",
  RECEIVED:  "bg-sky-100 text-sky-700",
  FAILED:    "bg-red-100 text-red-700",
};

/** What Shopify sent to the ERP's webhook address and how each delivery was handled (ADMIN only). */
export default function ShopifyWebhooks() {
  const { data: events = [], refetch, isFetching, isLoading } = useQuery<ShopifyWebhookEvent[]>({
    queryKey: ["shopify-events"],
    queryFn: getShopifyEvents,
  });

  return (
    <section data-testid="shopify-webhooks-log">
      <div className="flex items-center justify-between mb-3">
        <p className="text-sm text-gray-500">
          The latest 20 deliveries from Shopify. Orders they created or changed are in the Changes tab (user &quot;shopify&quot;).
          Settings: <Link href="/settings/integrations/shopify" className="text-brand-600 hover:underline">Shopify integration</Link>.
        </p>
        <button onClick={() => refetch()} className="text-xs text-brand-600 hover:text-brand-800 shrink-0 ml-4">
          {isFetching ? "Refreshing..." : "Refresh"}
        </button>
      </div>
      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm" data-testid="shopify-events">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {["Received", "Topic", "Result", "Details"].map(h => (
                  <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {events.length === 0 && (
                <tr><td colSpan={4} className="px-4 py-8 text-center text-gray-400">Nothing received yet</td></tr>
              )}
              {events.map(e => (
                <tr key={e.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap"><ClientDate iso={e.receivedAt} showTime /></td>
                  <td className="px-4 py-3 text-xs font-mono">{e.topic}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded ${EVENT_COLOR[e.status]}`}>{e.status}</span>
                  </td>
                  <td className="px-4 py-3 text-xs text-red-600">{e.error ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
