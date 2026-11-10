"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getContractPrices, addContractPrice, updateContractPrice, deleteContractPrice } from "@/lib/api";
import { ContractPrice, Customer, Product } from "@/types";
import { formatGBP } from "@/lib/currency";
import { Card, TH, TD } from "@/components/DetailParts";
import ProductPicker from "../sale-orders/product-picker";
import { useAuth } from "@/contexts/AuthContext";
import { useConfirm } from "@/components/ConfirmDialog";
import { todayIso } from "@/lib/dates";

const INPUT = "border border-gray-300 rounded-md px-2 py-1 text-sm";
const today = todayIso;

function errorText(e: unknown) {
  return (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.error ?? "Something went wrong";
}

/**
 * Phase G1: the customer's prices. Without a typed price an order line takes the contract price valid on the
 * day, else the customer's price tier, else Retail. Managers and admins change contract prices.
 */
export default function ContractPrices({ customer }: { customer: Customer }) {
  const ask = useConfirm();
  const qc = useQueryClient();
  const { user } = useAuth();
  const canEdit = user?.role === "ADMIN" || user?.role === "MANAGER" || user?.role === "SUPER_ADMIN";
  const { data: prices = [] } = useQuery<ContractPrice[]>({ queryKey: ["contract-prices", customer.id], queryFn: () => getContractPrices(customer.id) });
  const [product, setProduct] = useState<Pick<Product, "id" | "sku" | "name"> | null>(null);
  const [form, setForm] = useState({ unitPrice: "", validFrom: today(), validTo: "", note: "" });
  const [error, setError] = useState<string | null>(null);
  const [showEnded, setShowEnded] = useState(false);
  const refresh = () => { setError(null); qc.invalidateQueries({ queryKey: ["contract-prices", customer.id] }); };

  const add = useMutation({
    mutationFn: () => addContractPrice(customer.id, {
      productId: product!.id, unitPrice: Number(form.unitPrice), validFrom: form.validFrom, validTo: form.validTo || null, note: form.note,
    }),
    onSuccess: () => { refresh(); setProduct(null); setForm({ unitPrice: "", validFrom: today(), validTo: "", note: "" }); },
    onError: e => setError(errorText(e)),
  });
  const end = useMutation({
    mutationFn: (p: ContractPrice) => updateContractPrice(customer.id, p.id, {
      unitPrice: p.unitPrice, validFrom: p.validFrom, validTo: today() < p.validFrom ? p.validFrom : today(), note: p.note ?? null,
    }),
    onSuccess: refresh, onError: e => setError(errorText(e)),
  });
  const remove = useMutation({ mutationFn: (p: ContractPrice) => deleteContractPrice(customer.id, p.id), onSuccess: refresh, onError: e => setError(errorText(e)) });

  const ended = (p: ContractPrice) => !!p.validTo && p.validTo < today();
  const shown = prices.filter(p => showEnded || !ended(p));

  return (
    <Card title={`Contract prices (${prices.filter(p => !ended(p)).length})`} flush testId="customer-contract-prices"
      right={<span className="text-xs text-gray-500">Price tier: <b>{customer.priceTier || "Retail"}</b>
        {prices.some(ended) && <button type="button" onClick={() => setShowEnded(!showEnded)} className="ml-3 text-brand-700 hover:underline">
          {showEnded ? "Hide ended" : "Show ended"}</button>}</span>}>
      <p className="px-4 pt-3 text-xs text-gray-500">
        Order lines without a typed price take the contract price valid on the day, else the customer&apos;s tier price, else Retail.
        No customer discount is taken off a contract price.
      </p>
      <table className="w-full text-sm mt-2">
        <thead className="bg-gray-50 border-y border-gray-200">
          <tr>{["SKU", "Product", "Contract price", "Tier price", "From", "To", "Note", ""].map(h => <th key={h} className={TH}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {shown.length === 0 && <tr><td colSpan={8} className="px-3 py-4 text-center text-gray-400">No contract prices: the tier price is used</td></tr>}
          {shown.map(p => (
            <tr key={p.id} className={ended(p) ? "text-gray-400" : !p.current ? "text-gray-600" : ""}>
              <td className={`${TD} font-mono text-xs`}>{p.sku}</td>
              <td className={TD}>{p.productName}</td>
              <td className={`${TD} tabular-nums font-medium`}>{formatGBP(p.unitPrice)}</td>
              <td className={`${TD} tabular-nums text-gray-500`}>{p.tierPrice != null ? formatGBP(p.tierPrice) : "—"}</td>
              <td className={`${TD} text-xs`}>{p.validFrom}</td>
              <td className={`${TD} text-xs`}>{p.validTo ?? "no end"}{!p.current && !ended(p) && <span className="block text-amber-700">not started</span>}</td>
              <td className={`${TD} text-xs text-gray-500`}>{p.note}</td>
              <td className={`${TD} text-right whitespace-nowrap`}>
                {canEdit && !ended(p) && <button type="button" onClick={async () => { if (await ask({ title: `End the contract price for ${p.sku} today?`, action: "End today" })) end.mutate(p); }} className="text-xs text-brand-700 hover:underline">End today</button>}
                {user?.role === "ADMIN" && <button type="button" onClick={async () => { if (await ask({ title: `Delete the contract price for ${p.sku}?`, action: "Delete", danger: true })) remove.mutate(p); }}
                  className="ml-3 text-xs text-red-600 hover:underline">Delete</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {canEdit && (
        <form className="px-4 py-3 border-t border-gray-200 flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); if (product) add.mutate(); }}>
          <div className="w-72"><ProductPicker label="Contract price product" onSelect={p => { setProduct(p); setError(null); }} /></div>
          <label className="text-xs text-gray-600">Price (£)<input aria-label="Contract price" type="number" min="0" step="any" required value={form.unitPrice}
            onChange={e => setForm({ ...form, unitPrice: e.target.value })} className={`${INPUT} block w-24`} /></label>
          <label className="text-xs text-gray-600">From<input aria-label="Contract from" type="date" required value={form.validFrom}
            onChange={e => setForm({ ...form, validFrom: e.target.value })} className={`${INPUT} block`} /></label>
          <label className="text-xs text-gray-600">To (optional)<input aria-label="Contract to" type="date" value={form.validTo}
            onChange={e => setForm({ ...form, validTo: e.target.value })} className={`${INPUT} block`} /></label>
          <label className="text-xs text-gray-600 flex-1 min-w-32">Note<input aria-label="Contract note" maxLength={255} value={form.note}
            onChange={e => setForm({ ...form, note: e.target.value })} className={`${INPUT} block w-full`} /></label>
          <button type="submit" disabled={!product || add.isPending}
            className="bg-brand-600 text-white px-3 py-1.5 rounded-md text-sm font-medium hover:bg-brand-700 disabled:opacity-50">Add contract price</button>
        </form>
      )}
      {error && <p role="alert" className="px-4 pb-3 text-sm text-red-600">{error}</p>}
    </Card>
  );
}
