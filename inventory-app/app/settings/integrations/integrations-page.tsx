"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getShopifyConnection } from "@/lib/api";
import { ShopifyConnection } from "@/types";

type CardStatus = { label: string; color: string; detail?: string };

const NOT_CONNECTED: CardStatus = { label: "Not connected", color: "bg-gray-100 text-gray-600" };

function shopifyStatus(conn: ShopifyConnection | null | undefined): CardStatus {
  if (!conn) return NOT_CONNECTED;
  const where = conn.shopName ? `${conn.shopName} · ${conn.shopDomain}` : conn.shopDomain;
  switch (conn.status) {
    case "CONNECTED":  return { label: "Connected", color: "bg-emerald-100 text-emerald-700", detail: where };
    case "ERROR":      return { label: "Not working", color: "bg-red-100 text-red-700", detail: where };
    case "NOT_TESTED": return { label: "Not tested", color: "bg-gray-100 text-gray-600", detail: where };
  }
}

function IntegrationCard({ name, description, href, status, loading, testId }: {
  name: string; description: string; href: string; status: CardStatus; loading: boolean; testId: string;
}) {
  return (
    <Link href={href} data-testid={testId}
      className="block bg-white border border-gray-200 rounded-xl p-5 hover:shadow-md transition-shadow">
      <div className="flex items-start justify-between gap-3">
        <h2 className="font-semibold text-gray-900">{name}</h2>
        {!loading && <span className={`text-xs font-semibold px-2 py-0.5 rounded whitespace-nowrap ${status.color}`}>{status.label}</span>}
      </div>
      <p className="text-sm text-gray-500 mt-1">{description}</p>
      {status.detail && <p className="text-xs text-gray-400 mt-3 truncate">{status.detail}</p>}
      <p className="text-sm font-medium text-indigo-600 mt-3">{status === NOT_CONNECTED ? "Connect →" : "Manage →"}</p>
    </Link>
  );
}

export default function IntegrationsPage() {
  const { data: shopify, isLoading, error } = useQuery<ShopifyConnection | null>({
    queryKey: ["shopify-connection"],
    queryFn: getShopifyConnection,
    retry: false,
  });
  const forbidden = (error as any)?.response?.status === 403;

  return (
    <div className="max-w-4xl">
      <h1 className="text-2xl font-bold">Integrations</h1>
      <p className="text-gray-500 text-sm mt-0.5 mb-6">Connect Expert ERP to the other systems your business uses.</p>

      {forbidden ? (
        <p className="text-sm text-gray-600 bg-gray-50 border border-gray-200 rounded-lg px-4 py-3">Only an ADMIN can manage integrations.</p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <IntegrationCard
            testId="integration-shopify"
            name="Shopify"
            description="Import online orders and customers, send fulfilments back, and keep stock levels in step."
            href="/settings/integrations/shopify"
            status={shopifyStatus(shopify)}
            loading={isLoading}
          />
        </div>
      )}
    </div>
  );
}
