"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getShopifyConnection, importShopifyOrders } from "@/lib/api";
import { ShopifyConnection, ShopifyImportResult } from "@/types";
import { ClientDate } from "@/components/ClientDate";

const BUTTON_SECONDARY = "border border-gray-300 text-gray-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-50 disabled:opacity-50";

/**
 * Catch-up import of Shopify orders the ERP may have missed (e.g. while the backend was off). Only for admins, and
 * only once a store is connected; webhooks bring new orders in on their own.
 */
export function ShopifyImportButton({ onOpen }: { onOpen: () => void }) {
  const { data: conn } = useQuery<ShopifyConnection | null>({ queryKey: ["shopify-connection"], queryFn: getShopifyConnection });
  if (conn?.status !== "CONNECTED") return null;
  return (
    <button onClick={onOpen} className={BUTTON_SECONDARY}>
      Import from Shopify
    </button>
  );
}

export function ShopifyImportPanel({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { data: conn } = useQuery<ShopifyConnection | null>({ queryKey: ["shopify-connection"], queryFn: getShopifyConnection });
  const [days, setDays] = useState(30);
  const [result, setResult] = useState<ShopifyImportResult | null>(null);
  const [error, setError] = useState("");

  const importMutation = useMutation({
    mutationFn: importShopifyOrders,
    onSuccess: (r: ShopifyImportResult) => {
      setResult(r);
      setError("");
      qc.invalidateQueries({ queryKey: ["sale-orders"] });
      qc.invalidateQueries({ queryKey: ["shopify-connection"] });
    },
    onError: (e: any) => { setResult(null); setError(e.response?.data?.error || "Import failed"); },
  });

  return (
    <section data-testid="shopify-import" className="bg-white border border-gray-200 rounded-xl p-6 mb-6 space-y-3">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold text-gray-800">Import recent orders from Shopify</h2>
          <p className="text-xs text-gray-500 mt-0.5">
            New Shopify orders arrive on their own (webhooks). This fetches any the ERP may have missed, e.g. while this
            computer was off. Safe to repeat: orders already here are not created twice.
            {conn?.lastImportAt && <> Last import: <ClientDate iso={conn.lastImportAt} showTime />.</>}
          </p>
        </div>
        <button onClick={onClose} className="text-xs text-gray-500 hover:text-gray-700 shrink-0">Close</button>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor="shopify-import-days" className="sr-only">Days</label>
        <select id="shopify-import-days" value={days} onChange={e => setDays(Number(e.target.value))}
          className="border border-gray-300 rounded-md px-2 py-2 text-sm">
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={60}>Last 60 days</option>
        </select>
        <button onClick={() => importMutation.mutate(days)} disabled={importMutation.isPending}
          className="bg-rose-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-rose-700 disabled:opacity-50">
          {importMutation.isPending ? "Importing..." : "Import orders"}
        </button>
      </div>
      {error && <p data-testid="shopify-import-error" className="text-sm text-red-700 bg-red-50 px-3 py-2 rounded">✕ {error}</p>}
      {result && (
        <div data-testid="shopify-import-result" className="text-sm bg-gray-50 rounded-lg px-4 py-3">
          <p>
            Found {result.fetched} order{result.fetched !== 1 ? "s" : ""}: {result.created} new,{" "}
            {result.updated} updated, {result.unchanged} already in progress
            {result.skipped > 0 && <>, {result.skipped} cancelled (skipped)</>}.
          </p>
          {result.problems.length > 0 && (
            <ul className="mt-2 text-xs text-amber-800 list-disc ml-5 space-y-0.5">
              {result.problems.map((p, i) => <li key={i}>{p}</li>)}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
