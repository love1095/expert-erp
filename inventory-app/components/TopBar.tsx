"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/contexts/AuthContext";
import { getSubscription } from "@/lib/api";
import { SubscriptionInfo } from "@/types";
import { locate } from "@/components/nav";

const PLAN_COLOR: Record<string, string> = {
  FREE:       "bg-gray-100 text-gray-600",
  PRO:        "bg-gray-100 text-gray-700",
  ENTERPRISE: "bg-amber-50 text-amber-700",
};

export default function TopBar() {
  const pathname = usePathname();
  const { user, logout } = useAuth();
  const { module, item } = locate(pathname);

  const { data: sub } = useQuery<SubscriptionInfo>({
    queryKey: ["subscription"],
    queryFn: getSubscription,
    enabled: !!user,
    staleTime: 60_000,
  });

  const [menu, setMenu] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!menu) return;
    const close = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenu(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [menu]);

  const initials = (user?.username ?? "?").slice(0, 2).toUpperCase();

  return (
    <header className="h-14 bg-white border-b border-gray-200 flex items-center gap-4 px-6 shrink-0">
      {/* Where you are: Home / Sale / Invoices */}
      <nav aria-label="You are here" className="flex items-center gap-1.5 text-sm min-w-0">
        <Link href="/" className="text-gray-400 hover:text-gray-700">Home</Link>
        {module && module.href !== "/" && <>
          <span className="text-gray-300">/</span>
          <span className="text-gray-400">{module.label}</span>
        </>}
        {item && <>
          <span className="text-gray-300">/</span>
          <Link href={item.href} className="text-gray-700 font-medium truncate hover:text-brand-700">{item.label}</Link>
        </>}
      </nav>

      <div className="ml-auto flex items-center gap-3">
        <span className="hidden sm:inline text-sm text-gray-600 font-medium">{user?.tenantName}</span>
        {sub && (
          <Link href="/settings/subscription"
            className={`text-[10px] font-bold tracking-wide px-2 py-1 rounded ${PLAN_COLOR[sub.planTier] ?? PLAN_COLOR.FREE}`}>
            {sub.planTier}
          </Link>
        )}
        <div ref={menuRef} className="relative">
          <button type="button" onClick={() => setMenu(!menu)} aria-label="Account menu" aria-expanded={menu}
            className="flex items-center gap-2 rounded-full hover:bg-gray-100 pl-1 pr-2 py-1">
            <span className="w-8 h-8 rounded-full bg-accent-600 text-white text-xs font-semibold flex items-center justify-center">{initials}</span>
            <span className="hidden md:block text-left leading-tight">
              <span className="block text-xs font-semibold text-gray-800">{user?.username}</span>
              <span className="block text-[10px] text-gray-400">{user?.role}</span>
            </span>
          </button>
          {menu && (
            <div className="absolute right-0 mt-1 w-44 bg-white border border-gray-200 rounded-md shadow-lg py-1 z-50 text-sm">
              <Link href="/settings/organisation" onClick={() => setMenu(false)}
                className="block px-3 py-2 text-gray-700 hover:bg-gray-50">Organisation settings</Link>
              <button type="button" onClick={logout}
                className="block w-full text-left px-3 py-2 text-red-600 hover:bg-red-50">Sign out</button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
