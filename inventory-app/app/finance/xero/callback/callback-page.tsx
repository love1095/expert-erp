"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { xeroCallback } from "@/lib/api";

export default function CallbackPage({ code, state, error }: { code: string; state: string; error: string }) {
  const router = useRouter();
  const [result, setResult] = useState(error ? `Xero: ${error}` : "Connecting to Xero…");
  const sent = useRef(false);
  useEffect(() => {
    if (error || !code || sent.current) return;
    sent.current = true;
    xeroCallback(code, state)
      .then(() => router.replace("/finance/xero"))
      .catch((e: unknown) => setResult((e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? "The connection failed"));
  }, [code, state, error, router]);
  return <div className="p-6 text-sm" role="status">{result}</div>;
}
