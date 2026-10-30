"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/contexts/AuthContext";
import { getSubscription } from "@/lib/api";
import { SubscriptionInfo } from "@/types";

const ROLE_COLOR: Record<string, string> = {
  ADMIN:   "bg-indigo-100 text-indigo-700",
  MANAGER: "bg-teal-100 text-teal-700",
  STAFF:   "bg-gray-100 text-gray-500",
};

const nav = [
  { label: "OVERVIEW",    items: [
    { href: "/",               label: "Dashboard" },
    { href: "/stock-overview", label: "Stock Overview" },
  ]},
  { label: "MASTER DATA", items: [
    { href: "/products",   label: "Products" },
    { href: "/warehouses", label: "Warehouses" },
    { href: "/suppliers",  label: "Suppliers" },
    { href: "/customers",  label: "Customers" },
  ]},
  { label: "MOVEMENTS",   items: [
    { href: "/stock-movements", label: "Movements Log" },
    { href: "/stock-transfer",  label: "Transfer" },
    { href: "/stock-history",   label: "History" },
  ]},
  { label: "ORDERS",      items: [
    { href: "/purchase-orders", label: "Purchase Orders" },
    { href: "/sale-orders",     label: "Sale Orders" },
  ]},
  { label: "FINANCE",     items: [
    { href: "/invoices", label: "Invoices" },
  ]},
  { label: "ADMIN",       items: [
    { href: "/users",     label: "Users" },
    { href: "/audit-log", label: "Audit Log" },
  ]},
  { label: "SETTINGS",   items: [
    { href: "/settings/organisation", label: "Organisation" },
    { href: "/settings/subscription", label: "Subscription" },
    { href: "/settings/integrations", label: "Integrations" },
  ]},
];

const PLAN_COLOR: Record<string, string> = {
  FREE:       "bg-gray-100 text-gray-500",
  PRO:        "bg-indigo-100 text-indigo-600",
  ENTERPRISE: "bg-amber-100 text-amber-600",
};

export default function Sidebar() {
  const pathname = usePathname();
  const { user, logout } = useAuth();

  const { data: sub } = useQuery<SubscriptionInfo>({
    queryKey: ["subscription"],
    queryFn: getSubscription,
    enabled: !!user,
    staleTime: 60_000,
  });

  return (
    <aside className="w-56 bg-white border-r border-gray-200 flex flex-col shrink-0">
      <div className="px-6 py-5 border-b border-gray-200">
        <span className="text-base font-semibold text-indigo-600">Expert ERP</span>
        <p className="text-xs text-gray-400 mt-0.5">{user?.tenantName ?? "Inventory"}</p>
      </div>

      <nav className="flex-1 py-4 px-3 overflow-y-auto">
        {nav.map(group => (
          <div key={group.label} className="mb-4">
            <p className="px-3 mb-1 text-[10px] font-bold tracking-widest text-gray-400">{group.label}</p>
            <div className="space-y-0.5">
              {group.items.map(({ href, label }) => {
                const active = pathname === href || (href !== "/" && pathname.startsWith(href));
                return (
                  <Link key={href} href={href}
                    className={`flex items-center px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                      active ? "bg-indigo-50 text-indigo-700" : "text-gray-600 hover:bg-gray-100 hover:text-gray-900"
                    }`}>
                    {label}
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>

      {user && (
        <div className="px-4 py-4 border-t border-gray-200 space-y-2">
          {sub && (
            <Link href="/settings/subscription"
              className={`flex items-center gap-1.5 text-[10px] font-bold px-2 py-1 rounded-md w-full hover:opacity-80 transition-opacity ${PLAN_COLOR[sub.planTier] ?? PLAN_COLOR.FREE}`}>
              <span>{sub.planTier}</span>
              <span className="font-normal opacity-70">plan</span>
            </Link>
          )}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs font-semibold text-gray-700">{user.username}</p>
              <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${ROLE_COLOR[user.role] ?? ROLE_COLOR.STAFF}`}>
                {user.role}
              </span>
            </div>
            <button onClick={logout}
              className="text-xs text-gray-400 hover:text-red-500 transition-colors">
              Sign out
            </button>
          </div>
        </div>
      )}
    </aside>
  );
}
