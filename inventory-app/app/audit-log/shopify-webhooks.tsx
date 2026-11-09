"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getShopifyEvents, retryShopifyEvent } from "@/lib/api";
import { ShopifyWebhookEvent } from "@/types";
import { ClientDate } from "@/components/ClientDate";
import { useConfirm } from "@/components/ConfirmDialog";

const EVENT_COLOR: Record<ShopifyWebhookEvent["status"], string> = {
  PROCESSED: "bg-emerald-100 text-emerald-700",
  IGNORED:   "bg-gray-100 text-gray-500",
  RECEIVED:  "bg-sky-100 text-sky-700",
  FAILED:    "bg-red-100 text-red-700",
};

/**
 * What Shopify sent to the ERP's webhook address and how each delivery was handled (ADMIN only). Failed ones are
 * tried again on their own, waiting longer each time (7.5); Retry runs one now.
 */
export default function ShopifyWebhooks() {
  const ask = useConfirm();
  const qc = useQueryClient();
  const [failedOnly, setFailedOnly] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const { data: events = [], refetch, isFetching, isLoading } = useQuery<ShopifyWebhookEvent[]>({
    queryKey: ["shopify-events", failedOnly],
    queryFn: () => getShopifyEvents(failedOnly ? "FAILED" : undefined),
  });
  const retry = useMutation({
    mutationFn: retryShopifyEvent,
    onSuccess: (e: ShopifyWebhookEvent) => {
      setMessage(e.status === "FAILED" ? { ok: false, text: `Still failing: ${e.error ?? ""}` } : { ok: true, text: `${e.topic} handled` });
      qc.invalidateQueries({ queryKey: ["shopify-events"] });
      qc.invalidateQueries({ queryKey: ["shopify-health"] });
    },
    onError: (e: unknown) => setMessage({ ok: false, text: (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Retry failed" }),
  });

  return (
    <section data-testid="shopify-webhooks-log">
      <div className="flex items-center justify-between mb-3 gap-4">
        <p className="text-sm text-gray-500">
          {failedOnly ? "Deliveries that failed (up to 100)." : "The latest 20 deliveries from Shopify."} Failed ones are tried again
          on their own, waiting longer each time. Orders they created or changed are in the Changes tab (user &quot;shopify&quot;).
          Settings: <Link href="/settings/integrations/shopify" className="text-brand-600 hover:underline">Shopify integration</Link>.
        </p>
        <label className="flex items-center gap-1.5 text-xs text-gray-600 shrink-0">
          <input type="checkbox" checked={failedOnly} onChange={e => setFailedOnly(e.target.checked)} /> Failed only
        </label>
        <button onClick={() => refetch()} className="text-xs text-brand-600 hover:text-brand-800 shrink-0">
          {isFetching ? "Refreshing..." : "Refresh"}
        </button>
      </div>
      {message && <p role="status" className={`text-sm mb-3 ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.ok ? "✓ " : "✕ "}{message.text}</p>}
      {isLoading ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm" data-testid="shopify-events">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {["Received", "Topic", "Result", "Tries", "Details", ""].map(h => (
                  <th key={h} className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {events.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">{failedOnly ? "Nothing failed" : "Nothing received yet"}</td></tr>
              )}
              {events.map(e => (
                <tr key={e.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap"><ClientDate iso={e.receivedAt} showTime /></td>
                  <td className="px-4 py-3 text-xs font-mono">{e.topic}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded ${EVENT_COLOR[e.status]}`}>{e.status}</span>
                  </td>
                  <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap">
                    {e.attempts}
                    {e.status === "FAILED" && (e.nextAttemptAt
                      ? <span className="block text-gray-400">next <ClientDate iso={e.nextAttemptAt} showTime /></span>
                      : <span className="block text-red-600">given up</span>)}
                  </td>
                  <td className="px-4 py-3 text-xs text-red-600">{e.error ?? ""}</td>
                  <td className="px-4 py-3 text-right">
                    {(e.status === "FAILED" || e.status === "RECEIVED") && (
                      <button type="button" disabled={retry.isPending} onClick={async () => { if (!(await ask({ title: "Process this Shopify webhook again?", action: "Retry" }))) return; setMessage(null); retry.mutate(e.id); }}
                        className="text-xs text-brand-600 hover:underline disabled:opacity-50">Retry</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
