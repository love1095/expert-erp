"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getOrganisationSettings, getTaxRates, updateOrganisationSettings } from "@/lib/api";
import { OrganisationSettings, TaxRate } from "@/types";
import { useAuth } from "@/contexts/AuthContext";

const INPUT = "w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 disabled:bg-gray-50";
const LABEL = "block text-xs font-medium text-gray-600 mb-1";

export default function OrganisationPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMIN" || user?.role === "SUPER_ADMIN";
  const qc = useQueryClient();
  const { data: settings } = useQuery<OrganisationSettings>({ queryKey: ["organisation-settings"], queryFn: getOrganisationSettings });
  const { data: saleRates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "SALE"], queryFn: () => getTaxRates("SALE") });
  const { data: purchaseRates = [] } = useQuery<TaxRate[]>({ queryKey: ["tax-rates", "PURCHASE"], queryFn: () => getTaxRates("PURCHASE") });

  const [form, setForm] = useState<OrganisationSettings | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => { if (settings) setForm(settings); }, [settings]);

  const save = useMutation({
    mutationFn: updateOrganisationSettings,
    onSuccess: (s: OrganisationSettings) => { qc.setQueryData(["organisation-settings"], s); setMessage({ ok: true, text: "Saved" }); },
    onError: (e: any) => setMessage({ ok: false, text: e.response?.data?.error || "Could not save" }),
  });

  if (!form) return <p className="text-gray-400 text-sm">Loading...</p>;

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Organisation</h1>
        <p className="text-gray-500 text-sm mt-0.5">Order numbering, Shopify sales rep and VAT</p>
      </div>

      <form onSubmit={e => { e.preventDefault(); setMessage(null); save.mutate({ ...form, nextSaleOrderNumber: Number(form.nextSaleOrderNumber) }); }}
        className="bg-white border border-gray-200 rounded-xl p-6 grid grid-cols-2 gap-4" data-testid="organisation-settings">
        <div>
          <label htmlFor="org-prefix" className={LABEL}>Sale order prefix</label>
          <input id="org-prefix" value={form.saleOrderPrefix} disabled={!isAdmin} maxLength={10}
            onChange={e => setForm({ ...form, saleOrderPrefix: e.target.value })} className={INPUT} />
        </div>
        <div>
          <label htmlFor="org-next-number" className={LABEL}>Next sale order number</label>
          <input id="org-next-number" type="number" min="1" value={form.nextSaleOrderNumber} disabled={!isAdmin}
            onChange={e => setForm({ ...form, nextSaleOrderNumber: Number(e.target.value) })} className={INPUT} />
          <p className="text-[11px] text-gray-400 mt-1">
            Next order: <b>{form.saleOrderPrefix}{form.nextSaleOrderNumber}</b>. At cutover, set it to Cin7&apos;s next number so the series carries on; numbers already used are skipped.
          </p>
        </div>
        <div>
          <label htmlFor="org-web-rep" className={LABEL}>Sales rep for Shopify orders</label>
          <input id="org-web-rep" value={form.webSalesRep} disabled={!isAdmin} maxLength={100}
            onChange={e => setForm({ ...form, webSalesRep: e.target.value })} className={INPUT} />
        </div>
        <div>
          <label htmlFor="org-default-tax" className={LABEL}>Default sale tax rule</label>
          <select id="org-default-tax" value={form.defaultSaleTaxRule} disabled={!isAdmin}
            onChange={e => setForm({ ...form, defaultSaleTaxRule: e.target.value })} className={INPUT}>
            {saleRates.filter(r => r.active).map(r => <option key={r.id} value={r.name}>{r.name}</option>)}
          </select>
          <p className="text-[11px] text-gray-400 mt-1">Used when neither the product nor the customer has a tax rule.</p>
        </div>
        <div className="col-span-2 flex items-center gap-3">
          {isAdmin ? (
            <button type="submit" disabled={save.isPending}
              className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
              {save.isPending ? "Saving..." : "Save"}
            </button>
          ) : <p className="text-xs text-gray-400">Only an admin can change these settings.</p>}
          {message && <span role="status" className={`text-sm ${message.ok ? "text-emerald-700" : "text-red-600"}`}>{message.ok ? "✓ " : "✕ "}{message.text}</span>}
        </div>
      </form>

      <section className="bg-white border border-gray-200 rounded-xl p-6">
        <h2 className="font-semibold text-gray-800 mb-1">VAT rates</h2>
        <p className="text-xs text-gray-500 mb-3">
          UK rates, named as in Cin7 so imported products and customers match. Which one applies: a rate chosen on the line
          (manager or admin), else the product&apos;s, else the customer&apos;s, else the default above.
        </p>
        <div className="grid grid-cols-2 gap-6">
          {([["Sales", saleRates], ["Purchases", purchaseRates]] as [string, TaxRate[]][]).map(([title, rates]) => (
            <table key={title} className="w-full text-sm" data-testid={`tax-rates-${title.toLowerCase()}`}>
              <thead className="text-xs text-gray-500 text-left"><tr><th className="pb-1">{title}</th><th className="text-right">Rate</th></tr></thead>
              <tbody className="divide-y divide-gray-100">
                {rates.map(r => (
                  <tr key={r.id} className={r.active ? "" : "text-gray-400"}>
                    <td className="py-1">{r.name}</td><td className="text-right tabular-nums">{Number(r.rate)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        </div>
      </section>
    </div>
  );
}
