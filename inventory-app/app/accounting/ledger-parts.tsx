"use client";

import Link from "next/link";
import { formatGBP } from "@/lib/currency";

export const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white";
export const CLASS_LABEL: Record<string, string> = { ASSET: "Assets", LIABILITY: "Liabilities", EQUITY: "Equity", REVENUE: "Revenue", EXPENSE: "Expenses" };

export const money = (v: number | null | undefined) => (v == null || Number(v) === 0 ? "" : formatGBP(Number(v)));
export const today = () => new Date().toISOString().slice(0, 10);
export const errorOf = (e: unknown, fallback = "Something went wrong") =>
  (e as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback;

/** Where a journal comes from, linked to the document when there is one (Xero's "Source"). */
export function SourceLink({ sourceKey, sourceType, reference }: { sourceKey: string; sourceType: string; reference: string | null }) {
  const label = ({ INVOICE: "Invoice", CREDIT_NOTE: "Credit note", BILL: "Bill", SUPPLIER_CREDIT: "Supplier credit", PAYMENT: "Payment",
    CREDIT: "Credit / deposit", STOCK: "Stock", MANUAL: "Manual journal", OPENING: "Conversion balances" } as Record<string, string>)[sourceType] ?? sourceType;
  const [kind, id] = sourceKey.split(":");
  const href = kind === "DOC" ? `/invoices/${id}` : kind === "MJ" ? "/accounting/manual-journals" : kind === "OPENING" ? "/accounting/settings" : null;
  const text = reference ? `${label} ${reference}` : label;
  return href ? <Link href={href} className="text-brand-600 hover:underline">{text}</Link> : <span>{text}</span>;
}
