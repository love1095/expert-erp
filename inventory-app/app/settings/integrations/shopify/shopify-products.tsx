"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { syncShopifyProducts, getShopifyProductSync } from "@/lib/api";
import { ShopifyConnection, ShopifyProductSync } from "@/types";
import { ClientDate } from "@/components/ClientDate";

const PHASES: ShopifyProductSync["phase"][] = ["SENDING", "REMOVING", "STOCK"];
const PHASE_LABEL: Record<ShopifyProductSync["phase"], string> = {
  SENDING: "Sending products",
  REMOVING: "Removing products no longer sent",
  STOCK: "Setting stock",
};

const BUTTON = "bg-brand-600 text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50";

/** Product sync: sends every ERP product (details, price, cost, stock) to Shopify, in the background. */
export default function ShopifyProducts({ conn }: { conn: ShopifyConnection }) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const { data: run } = useQuery<ShopifyProductSync | null>({
    queryKey: ["shopify-product-sync"],
    queryFn: getShopifyProductSync,
    // Poll while a run is in progress
    refetchInterval: q => (q.state.data?.state === "RUNNING" ? 1500 : false),
  });

  const startMutation = useMutation({
    mutationFn: syncShopifyProducts,
    onSuccess: (r: ShopifyProductSync) => {
      setError(null);
      qc.setQueryData(["shopify-product-sync"], r);
    },
    onError: (e: any) => setError(e.response?.data?.error || "Request failed"),
  });

  const running = run?.state === "RUNNING";
  const canWrite = conn.grantedScopes.includes("write_products");
  const connected = conn.status === "CONNECTED";
  const percent = run && run.phaseTotal > 0 ? Math.round((run.phaseDone / run.phaseTotal) * 100) : 0;

  // Refresh "Last sync" once a run ends
  const finishedAt = run?.finishedAt;
  useEffect(() => {
    if (finishedAt) qc.invalidateQueries({ queryKey: ["shopify-connection"] });
  }, [finishedAt, qc]);

  return (
    <section data-testid="shopify-products" className="bg-white border border-gray-200 rounded-xl p-6 space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold text-gray-800">Products</h2>
          <p className="text-xs text-gray-500 mt-0.5 max-w-2xl">
            Sends the ERP&apos;s active products to Shopify: name, description, category, brand, tags, SKU, selling price,
            cost, weight and the stock in the warehouse that ships Shopify orders. A product family (same item in several
            sizes/colours) becomes <b>one</b> Shopify product with a variant per SKU. Products are matched by SKU; new ones
            are created as <b>Draft</b>, so add photos and set them Active in Shopify. Without a selling price they get
            £0.00: price them before making them Active. Deprecated products are removed from Shopify (archived if you
            made them there by hand). Safe to repeat: unchanged products are skipped, stock is always refreshed.
          </p>
          {conn.lastProductSyncAt && (
            <p className="text-xs text-gray-500 mt-1">Last sync: <ClientDate iso={conn.lastProductSyncAt} showTime /></p>
          )}
        </div>
        <button onClick={() => startMutation.mutate()} disabled={running || startMutation.isPending || !connected || !canWrite}
          className={`${BUTTON} shrink-0`}>
          {running ? "Syncing..." : "Sync products to Shopify"}
        </button>
      </div>

      {connected && !canWrite && (
        <p data-testid="shopify-products-scope" className="text-xs text-amber-800 bg-amber-50 px-3 py-2 rounded">
          The Shopify app may not change products yet. In the Dev Dashboard, add the <code>write_products</code> scope to
          the app, release the new version and approve it on the store, then click <b>Test connection</b> above.
        </p>
      )}

      {error && (
        <p role="status" data-testid="shopify-products-message" className="text-sm px-3 py-2 rounded text-red-700 bg-red-50">✕ {error}</p>
      )}

      {run && (
        <div data-testid="shopify-products-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3 space-y-2">
          {running ? (
            <>
              <p>
                Step {PHASES.indexOf(run.phase) + 1} of 3 — {PHASE_LABEL[run.phase]}… {run.phaseDone.toLocaleString()} of{" "}
                {run.phaseTotal ? run.phaseTotal.toLocaleString() : "?"}
                {run.locationName && <> (stock from {run.warehouseName} → Shopify location {run.locationName})</>}
              </p>
              <div role="progressbar" aria-label="Product sync progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}
                className="h-2 bg-gray-200 rounded-full overflow-hidden">
                <div className="h-full bg-emerald-600 rounded-full transition-all" style={{ width: `${percent}%` }} />
              </div>
            </>
          ) : run.state === "FAILED" ? (
            <p className="text-red-700">✕ Sync stopped: {run.error}</p>
          ) : (
            <p>
              ✓ {run.total.toLocaleString()} Shopify product{run.total !== 1 ? "s" : ""} ({run.variants.toLocaleString()} SKUs):{" "}
              {run.created.toLocaleString()} created, {run.updated.toLocaleString()} updated, {run.unchanged.toLocaleString()} unchanged
              {run.removed > 0 && <>, {run.removed.toLocaleString()} removed</>}
              {run.failed > 0 && <>, <span className="text-red-700">{run.failed} failed</span></>}.
              {run.notSent > 0 && <> {run.notSent.toLocaleString()} deprecated/service products not sent.</>}{" "}
              Stock set for {run.stockUpdated.toLocaleString()}
              {run.warehouseName && <> from {run.warehouseName}</>}
              {run.locationName && <> at Shopify location {run.locationName}</>}.
              {run.withoutPrice > 0 && <> <Link href="/products" className="text-emerald-700 hover:underline">Set selling prices</Link></>}
            </p>
          )}
          {run.problems.length > 0 && (
            <ul className="text-xs text-amber-800 list-disc ml-5 space-y-0.5 max-h-48 overflow-y-auto">
              {run.problems.map((p, i) => <li key={i}>{p}</li>)}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
