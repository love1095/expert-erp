"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/contexts/AuthContext";
import Sidebar from "@/components/Sidebar";

export default function AppShell({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();
  const pathname = usePathname();
  const router = useRouter();

  const isPublic = pathname === "/login" || pathname === "/register";

  useEffect(() => {
    if (!isLoading && !user && !isPublic) {
      router.push("/login");
    }
  }, [isLoading, user, pathname, router]);

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
      <main className="flex-1 overflow-auto p-8">{children}</main>
    </>
  );
}
