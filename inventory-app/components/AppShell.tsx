"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/contexts/AuthContext";
import Sidebar from "@/components/Sidebar";
import TopBar from "@/components/TopBar";
import { PLATFORM_HOME } from "@/components/nav";

export default function AppShell({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();
  const pathname = usePathname();
  const router = useRouter();

  const isPublic = pathname === "/login" || pathname === "/register";

  useEffect(() => {
    if (!isLoading && !user && !isPublic) {
      router.push("/login");
    }
    // The super admin is in no organisation: its pages are the platform's
    if (!isLoading && user?.role === "SUPER_ADMIN" && !isPublic && !pathname.startsWith("/platform")) {
      router.push(PLATFORM_HOME);
    }
  }, [isLoading, user, pathname, router, isPublic]);

  // Signed in, only <main> scrolls: the window itself never does, so the top bar and the menu stay in view
  // (review R6). Login and register keep normal window scrolling for phones.
  const framed = !isPublic && !!user;
  useEffect(() => {
    if (!framed) return;
    const html = document.documentElement;
    html.style.overflow = "hidden";
    document.body.style.overflow = "hidden";
    return () => { html.style.overflow = ""; document.body.style.overflow = ""; };
  }, [framed]);

  if (isPublic) {
    return <>{children}</>;
  }

  if (isLoading || !user) {
    return (
      <div className="flex items-center justify-center h-full w-full">
        <div className="text-gray-400 text-sm">Loading...</div>
      </div>
    );
  }

  return (
    <>
      <Sidebar />
      <div className="flex-1 min-w-0 flex flex-col">
        <TopBar />
        {/* relative: hidden labels, file inputs and pop-ups are placed inside the page area, not against the window (R6) */}
        <main className="relative flex-1 overflow-auto p-6">{children}</main>
      </div>
    </>
  );
}
