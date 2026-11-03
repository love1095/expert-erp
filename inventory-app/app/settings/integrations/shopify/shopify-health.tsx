"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getShopifyHealth, reconcileShopify, getShopifyRefundsToReview, syncShopifyStock } from "@/lib/api";
import { ShopifyHealth, ShopifyReconcileResult, ShopifyRefundWaiting, ShopifyStockSyncResult } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";

/** 7.5: is anything being lost? Webhook deliveries, failures waiting or given up, and the daily check. */
export default function ShopifyHealthCard() {
  const qc = useQueryClient();
  const { data: health } = useQuery<ShopifyHealth | null>({ queryKey: ["shopify-health"], queryFn: getShopifyHealth, refetchInterval: 60_000 });
  const { data: refunds = [] } = useQuery<ShopifyRefundWaiting[]>({
    queryKey: ["shopify-refunds-waiting", health?.refundsToReview],
    queryFn: getShopifyRefundsToReview,
    enabled: !!health && health.refundsToReview > 0,
  });
  const [result, setResult] = useState<ShopifyReconcileResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const check = useMutation({
    mutationFn: reconcileShopify,
    onSuccess: (r: ShopifyReconcileResult) => { setResult(r); setError(null); qc.invalidateQueries({ queryKey: ["shopify-health"] }); },
    onError: (e: unknown) => setError((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "The check failed"),
  });

  const [stockResult, setStockResult] = useState<ShopifyStockSyncResult | null>(null);
  const [stockError, setStockError] = useState<string | null>(null);
  const sendStock = useMutation({
    mutationFn: syncShopifyStock,
    onSuccess: (r: ShopifyStockSyncResult) => { setStockResult(r); setStockError(null); qc.invalidateQueries({ queryKey: ["shopify-health"] }); },
    onError: (e: unknown) => setStockError((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "Sending stock failed"),
  });

  if (!health) return null;
  return (
    <section data-testid="shopify-health" className="bg-white border border-gray-200 rounded-xl p-6">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold text-gray-800">Health</h2>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${health.ok ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-800"}`}>
          {health.ok ? "All good" : "Needs attention"}
        </span>
      </div>
      {health.warnings.length > 0 && (
        <ul data-testid="shopify-health-warnings" className="mt-3 space-y-1 text-sm text-amber-800 bg-amber-50 px-3 py-2 rounded">
          {health.warnings.map(w => <li key={w}>• {w}</li>)}
        </ul>
      )}
      <dl className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-4 text-xs">
        <div><dt className="text-gray-500">Last webhook</dt><dd className="text-gray-700">{health.lastEventAt ? <ClientDate iso={health.lastEventAt} showTime /> : "None yet"}</dd></div>
        <div><dt className="text-gray-500">Last 24 h</dt><dd className="text-gray-700">{health.processedLast24h} handled · {health.failedLast24h} failed</dd></div>
        <div><dt className="text-gray-500">Failed, retrying</dt><dd className="text-gray-700" data-testid="shopify-waiting-retry">{health.waitingRetry}</dd></div>
        <div><dt className="text-gray-500">Failed for good</dt>
          <dd className={health.gaveUp > 0 ? "text-red-700 font-semibold" : "text-gray-700"}>
            {health.gaveUp > 0 ? <Link href="/audit-log?tab=shopify-webhooks" className="hover:underline">{health.gaveUp} → retry</Link> : 0}
          </dd></div>
      </dl>
      {refunds.length > 0 && (
        <div data-testid="shopify-refunds-waiting" className="mt-4 border-t border-gray-100 pt-4 text-xs">
          <p className="text-gray-500 mb-1">Refunds made in Shopify, to review on their orders</p>
          <ul className="space-y-0.5">
            {refunds.map(r => (
              <li key={r.id}>
                <Link href={`/sale-orders/${r.saleOrderId}`} className="text-brand-600 hover:underline">{r.orderNumber}{r.shopifyOrder ? ` (${r.shopifyOrder})` : ""}</Link>
                {" "}— {formatGBP(r.amount)}{r.refundedAt ? <>, <ClientDate iso={r.refundedAt} /></> : null}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="mt-4 border-t border-gray-100 pt-4 flex items-start gap-4">
        <div className="text-xs flex-1">
          <p className="text-gray-500">Stock in Shopify = available over all warehouses (on hand − reserved, never below 0). Changes go within 20 seconds; everything again every night</p>
          <p className="text-gray-700 mt-0.5" data-testid="shopify-stock-sync">
            {health.stockSyncedAt ? <>Last: <ClientDate iso={health.stockSyncedAt} showTime />{health.stockSyncResult ? ` — ${health.stockSyncResult}` : ""}</> : "Not sent yet"}
            {" · "}{health.stockWaiting === 0 ? "Shopify is up to date" : `${health.stockWaiting.toLocaleString()} product(s) to send`}
          </p>
          {stockResult && <p role="status" className="text-emerald-700 mt-1">✓ Stock sent: {stockResult.sent.toLocaleString()} products{stockResult.failed > 0 ? `, ${stockResult.failed} refused` : ""}</p>}
          {stockError && <p role="alert" className="text-red-600 mt-1">{stockError}</p>}
        </div>
        <button type="button" onClick={() => sendStock.mutate()} disabled={sendStock.isPending}
          className="border border-gray-300 text-gray-700 px-3 py-1.5 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50 shrink-0">
          {sendStock.isPending ? "Sending..." : "Send all stock now"}
        </button>
      </div>
      <div className="mt-4 border-t border-gray-100 pt-4 flex items-start gap-4">
        <div className="text-xs flex-1">
          <p className="text-gray-500">Daily check against Shopify (missed orders, removed webhooks), every night at 03:15</p>
          <p className="text-gray-700 mt-0.5" data-testid="shopify-last-check">
            {health.lastReconciledAt ? <>Last: <ClientDate iso={health.lastReconciledAt} showTime />{health.lastReconcileSummary ? ` — ${health.lastReconcileSummary}` : ""}</> : "Not run yet"}
          </p>
          {health.lastReconcileError && <p className="text-red-600 mt-0.5">Last check failed: {health.lastReconcileError}</p>}
          {result && <p role="status" className="text-emerald-700 mt-1">✓ Checked: {result.summary || "nothing to do"}{result.problems.length > 0 ? ` (${result.problems.length} to review)` : ""}</p>}
          {error && <p role="alert" className="text-red-600 mt-1">{error}</p>}
        </div>
        <button type="button" onClick={() => check.mutate()} disabled={check.isPending}
          className="border border-gray-300 text-gray-700 px-3 py-1.5 rounded-md text-sm font-medium hover:bg-gray-50 disabled:opacity-50 shrink-0">
          {check.isPending ? "Checking..." : "Check now"}
        </button>
      </div>
    </section>
  );
}
