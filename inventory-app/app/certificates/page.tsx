"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getExpiringCertificates } from "@/lib/api";
import { ExpiringCertificate } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { ExpiryBadge } from "../products/product-compliance";

/** Phase P1: certificates expiring in the next N days, and those already expired, soonest first. */
export default function CertificatesPage() {
  const [days, setDays] = useState(60);
  const { data: rows = [], isLoading } = useQuery<ExpiringCertificate[]>({ queryKey: ["expiring-certificates", days], queryFn: () => getExpiringCertificates(days) });
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Certificates expiring" />
      <label className="text-sm flex items-center gap-2">Expiring within
        <select className="border border-gray-300 rounded-md px-2 py-1.5" aria-label="Days" value={days} onChange={e => setDays(Number(e.target.value))}>
          {[30, 60, 90, 180, 365].map(d => <option key={d} value={d}>{d} days</option>)}
        </select>
        <span className="text-gray-500">(and already expired)</span>
      </label>
      <section className="bg-white border border-gray-200 rounded-xl">
        <table className="w-full text-sm" data-testid="expiring-certificates">
          <thead className="text-xs text-gray-500 text-left bg-gray-50"><tr><th className="px-5 py-2">SKU</th><th>Product</th><th>Standards</th>
            <th>Certificate</th><th>Issued by</th><th>Expiry</th><th /><th className="px-5">PDF</th></tr></thead>
          <tbody className="divide-y divide-gray-100">
            {rows.map(r => (
              <tr key={r.productId}>
                <td className="px-5 py-1.5"><Link href={`/products/${r.productId}`} className="text-brand-700 hover:underline font-mono">{r.sku}</Link></td>
                <td>{r.name}</td><td className="text-gray-600">{r.standards}</td><td>{r.certificateNumber}</td><td className="text-gray-600">{r.certificateIssuer}</td>
                <td>{new Date(`${r.certificateExpiry}T00:00:00Z`).toLocaleDateString("en-GB", { timeZone: "UTC" })}</td>
                <td><ExpiryBadge date={r.certificateExpiry} /></td>
                <td className="px-5">{r.hasFile ? "Attached" : <span className="text-red-700">Missing</span>}</td>
              </tr>
            ))}
            {!isLoading && rows.length === 0 && <tr><td colSpan={8} className="px-5 py-4 text-gray-400">No certificates expiring within {days} days</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
