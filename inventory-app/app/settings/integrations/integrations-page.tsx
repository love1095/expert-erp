"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getShopifyConnection, getXeroConnection } from "@/lib/api";
import { ShopifyConnection, XeroConnection } from "@/types";
import { useAuth } from "@/contexts/AuthContext";

type CardStatus = { label: string; color: string; detail?: string; connected?: boolean };
type Category = "Accounting" | "eCommerce" | "API";

const GREEN = "bg-emerald-100 text-emerald-700", GREY = "bg-gray-100 text-gray-600", RED = "bg-red-100 text-red-700", AMBER = "bg-amber-100 text-amber-800";
const NOT_CONNECTED: CardStatus = { label: "Not connected", color: GREY };

function shopifyStatus(conn: ShopifyConnection | null | undefined): CardStatus {
  if (!conn) return NOT_CONNECTED;
  const where = conn.shopName ? `${conn.shopName} · ${conn.shopDomain}` : conn.shopDomain;
  switch (conn.status) {
    case "CONNECTED":  return { label: "Connected", color: GREEN, detail: where, connected: true };
    case "ERROR":      return { label: "Not working", color: RED, detail: where, connected: true };
    case "NOT_TESTED": return { label: "Not tested", color: GREY, detail: where, connected: true };
  }
}

function xeroStatus(conn: XeroConnection | undefined): CardStatus {
  switch (conn?.status) {
    case "CONNECTED":           return { label: "Connected", color: GREEN, detail: conn.xeroTenantName ?? undefined, connected: true };
    case "EXPIRED":             return { label: "Connect again", color: RED, detail: conn.xeroTenantName ?? undefined, connected: true };
    case "CHOOSE_ORGANISATION": return { label: "Choose organisation", color: AMBER, connected: true };
    default:                    return NOT_CONNECTED;
  }
}

/**
 * Settings → Integrations, laid out like Cin7's: a catalogue of the systems Expert ERP connects to, found by a search
 * box or a category; a card opens that integration's own set-up page. A new integration is one more entry in `cards`.
 */
export default function IntegrationsPage() {
  const { user, can } = useAuth();
  const admin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const seesXero = can("SETTINGS") || can("REPORTS_FINANCIAL");
  const shopify = useQuery<ShopifyConnection | null>({ queryKey: ["shopify-connection"], queryFn: getShopifyConnection, retry: false, enabled: admin });
  const xero = useQuery<XeroConnection>({ queryKey: ["xero-connection"], queryFn: getXeroConnection, retry: false, enabled: seesXero });
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<"All" | "My integrations" | Category>("All");

  const cards: { key: string; name: string; category: Category; description: string; href: string; status: CardStatus; loading: boolean; allowed: boolean; who: string }[] = [
    { key: "xero", name: "Xero", category: "Accounting", href: "/settings/integrations/xero", allowed: seesXero, who: "Settings or financial reports",
      description: "Send invoices, bills, credit notes, payments and stock journals to your Xero, and bring payments made in Xero back.",
      status: xeroStatus(xero.data), loading: xero.isLoading },
    { key: "shopify", name: "Shopify", category: "eCommerce", href: "/settings/integrations/shopify", allowed: admin, who: "an ADMIN",
      description: "Import online orders and customers, send fulfilments back, and keep stock levels in step.",
      status: shopifyStatus(shopify.data), loading: shopify.isLoading },
    { key: "api", name: "API", category: "API", href: "/settings/integrations/api", allowed: admin, who: "an ADMIN",
      description: "Let other systems use this organisation's data with its Account ID and an Application Key.",
      status: { label: "Keys", color: GREY }, loading: false },
  ];
  const categories = ["All", "My integrations", ...Array.from(new Set(cards.map(c => c.category)))] as (typeof filter)[];
  const shown = cards.filter(c => (filter === "All" || (filter === "My integrations" ? !!c.status.connected : c.category === filter))
    && (c.name + " " + c.description + " " + c.category).toLowerCase().includes(q.trim().toLowerCase()));

  return (
    <div className="max-w-5xl">
      <h1 className="text-2xl font-bold">Integrations</h1>
      <p className="text-gray-500 text-sm mt-0.5 mb-5">Connect Expert ERP to the other systems your business uses.</p>
      <div className="flex flex-col md:flex-row gap-5">
        <nav aria-label="Integration categories" className="md:w-48 shrink-0 flex md:flex-col gap-1 overflow-x-auto">
          {categories.map(c => (
            <button key={c} type="button" aria-current={filter === c ? "true" : undefined} onClick={() => setFilter(c)}
              className={`text-left text-sm px-3 py-2 rounded-md whitespace-nowrap ${filter === c ? "bg-brand-50 text-brand-700 font-medium" : "text-gray-600 hover:bg-gray-50"}`}>{c}</button>
          ))}
        </nav>
        <div className="flex-1 min-w-0">
          <input type="search" aria-label="Search integrations" placeholder="Type text to search…" value={q} onChange={e => setQ(e.target.value)}
            className="w-full sm:w-72 border border-gray-300 rounded-md px-3 py-2 text-sm mb-4 focus:outline-none focus:ring-2 focus:ring-brand-400" />
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {shown.map(c => c.allowed ? (
              <Link key={c.key} href={c.href} data-testid={`integration-${c.key}`}
                className="block bg-white border border-gray-200 rounded-xl p-5 hover:shadow-md transition-shadow">
                <CardBody name={c.name} category={c.category} description={c.description} status={c.status} loading={c.loading} />
                <p className="text-sm font-medium text-brand-600 mt-3">{c.status === NOT_CONNECTED ? "Connect →" : "Manage →"}</p>
              </Link>
            ) : (
              <div key={c.key} data-testid={`integration-${c.key}`} className="bg-gray-50 border border-gray-200 rounded-xl p-5">
                <CardBody name={c.name} category={c.category} description={c.description} loading />
                <p className="text-xs text-gray-500 mt-3">Only {c.who} can manage this integration.</p>
              </div>
            ))}
            {shown.length === 0 && <p className="text-sm text-gray-500 sm:col-span-2" data-testid="integrations-empty">
              {filter === "My integrations" && !q ? "Nothing is connected yet." : "No integration matches."}</p>}
          </div>
        </div>
      </div>
    </div>
  );
}

function CardBody({ name, category, description, status, loading }: { name: string; category: string; description: string; status?: CardStatus; loading: boolean }) {
  return (
    <>
      <div className="flex items-start justify-between gap-3">
        <h2 className="font-semibold text-gray-900">{name}</h2>
        {!loading && status && <span className={`text-xs font-semibold px-2 py-0.5 rounded whitespace-nowrap ${status.color}`}>{status.label}</span>}
      </div>
      <p className="text-xs text-gray-400 mt-0.5">{category}</p>
      <p className="text-sm text-gray-500 mt-2">{description}</p>
      {status?.detail && <p className="text-xs text-gray-400 mt-3 truncate">{status.detail}</p>}
    </>
  );
}
