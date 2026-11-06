"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { mtdCallback } from "@/lib/api";
import { errorOf } from "../../ledger-parts";

export default function CallbackPage({ code, state, error }: { code: string; state: string; error: string }) {
  const [result, setResult] = useState(error ? `HMRC: ${error}` : "Connecting to HMRC…");
  const sent = useRef(false);
  useEffect(() => {
    if (error || !code || sent.current) return;
    sent.current = true;
    mtdCallback(code, state).then(() => setResult("✓ Connected to HMRC")).catch(e => setResult(errorOf(e, "The connection failed")));
  }, [code, state, error]);
  return (
    <div className="p-6 max-w-xl space-y-3">
      <h1 className="text-lg font-semibold">Making Tax Digital</h1>
      <p role="status" className="text-sm">{result}</p>
      <Link href="/accounting/vat" className="text-sm text-brand-700 hover:underline">Back to VAT returns</Link>
    </div>
  );
}
