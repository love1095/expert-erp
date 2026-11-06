"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getSalesProfit, getSalesProfitCsv } from "@/lib/api";
import { ProfitGroupBy, SalesProfitReport, SalesProfitRow } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PERIOD_LABELS, Period, PeriodKey, periodFor } from "@/lib/periods";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white";

/** Cin7's "Mode" (Last month by default) with the dates it gives; changing a date makes it Custom. */
export function PeriodPicker({ value, onChange }: { value: { key: PeriodKey; period: Period }; onChange: (v: { key: PeriodKey; period: Period }) => void }) {
  return (
    <>
      <select aria-label="Mode" className={INPUT} value={value.key}
        onChange={e => {
          const key = e.target.value as PeriodKey;
          onChange({ key, period: key === "custom" ? value.period : periodFor(key) });
        }}>
        {(Object.keys(PERIOD_LABELS) as PeriodKey[]).map(k => <option key={k} value={k}>{PERIOD_LABELS[k]}</option>)}
      </select>
      <input type="date" aria-label="From" className={INPUT} value={value.period.from}
        onChange={e => onChange({ key: "custom", period: { ...value.period, from: e.target.value } })} />
      <input type="date" aria-label="To" className={INPUT} value={value.period.to}
        onChange={e => onChange({ key: "custom", period: { ...value.period, to: e.target.value } })} />
    </>
  );
}

export function usePeriod() {
  return useState<{ key: PeriodKey; period: Period }>(() => ({ key: "lastMonth", period: periodFor("lastMonth") }));
}

export async function download(blob: Promise<Blob>, filename: string) {
  const url = URL.createObjectURL(await blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export const pct = (v: number | null) => (v == null ? "" : `${Number(v).toFixed(2)}%`);
export const qty = (v: number) => Number(v).toLocaleString("en-GB", { maximumFractionDigits: 4 });

export function errorText(e: unknown, what: string) {
  const status = (e as { response?: { status?: number } })?.response?.status;
  return status === 403 ? "Only managers and admins can see cost and profit reports" : `Could not work out the ${what} for these dates`;
}

type Column = "rep" | "customer" | "order" | "product";

/**
 * Sales Profit Summary, Profit by Sales Representatives and Sales by Product Summary, like Cin7 Core's: invoices less
 * credit notes in the period (stock products), FIFO COGS within the dates or all COGS of the invoices, profit, gross
 * margin and markup. Each page offers the layouts of its Cin7 report.
 */
export function SalesProfitPage({ title, subtitle, layouts, testId }: {
  title: string; subtitle: string; layouts: { value: ProfitGroupBy; label: string }[]; testId: string;
}) {
  const [period, setPeriod] = usePeriod();
  const [cogs, setCogs] = useState<"WITHIN_DATES" | "ALL">("WITHIN_DATES");
  const [groupBy, setGroupBy] = useState<ProfitGroupBy>(layouts[0].value);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const [csvError, setCsvError] = useState("");
  const params = { ...period.period, cogs, groupBy };
  const { data, isLoading, error } = useQuery<SalesProfitReport>({ queryKey: ["sales-profit", params], queryFn: () => getSalesProfit(params) });

  const cols: Column[] = groupBy === "PRODUCT" ? ["product"]
    : groupBy === "REP" ? ["rep"]
    : groupBy === "REP_CUSTOMER" ? ["rep", "customer"]
    : groupBy === "REP_ORDER" ? ["rep", "customer", "order"]
    : groupBy === "ORDER" ? ["customer", "rep", "order"]
    : ["customer", "rep"];
  const q = search.trim().toLowerCase();
  const rows = (data?.rows ?? []).filter(r =>
    (!category || (r.category ?? "") === category) &&
    (!q || [r.customer, r.salesRep, r.orderNumber, r.invoices, r.creditNotes, r.sku, r.productName].some(v => v?.toLowerCase().includes(q))));
  const categories = groupBy === "PRODUCT" ? [...new Set((data?.rows ?? []).map(r => r.category ?? ""))].sort() : [];
  const filtered = rows.length !== (data?.rows.length ?? 0);
  const sum = (k: "invoiced" | "sale" | "cogs" | "profit") => rows.reduce((s, r) => s + Number(r[k]), 0);
  const t = filtered ? (() => {
    const sale = sum("sale"), c = sum("cogs");
    return { invoiced: sum("invoiced"), sale, cogs: c, profit: sale - c,
      grossMargin: sale && c ? ((sale - c) * 100) / sale : null, markup: sale && c ? ((sale - c) * 100) / c : null,
      quantity: rows.reduce((s, r) => s + Number(r.quantity), 0) };
  })() : data?.totals;

  const exportCsv = () => download(getSalesProfitCsv(params), `SalesProfit_${groupBy.toLowerCase()}_${params.from}_${params.to}.csv`)
    .catch(() => setCsvError("Could not export"));

  return (
    <div data-testid={testId}>
      <PageHeader title={title} subtitle={subtitle}>
        <button type="button" className={BTN_SECONDARY} onClick={exportCsv}>Export CSV</button>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-2 mb-3">
        <PeriodPicker value={period} onChange={setPeriod} />
        <label className="text-xs text-gray-500 flex flex-col">Show COGS
          <select aria-label="Show COGS" className={INPUT} value={cogs} onChange={e => setCogs(e.target.value as "WITHIN_DATES" | "ALL")}>
            <option value="WITHIN_DATES">Only COGS within the date range</option>
            <option value="ALL">All COGS</option>
          </select>
        </label>
        {layouts.length > 1 && (
          <label className="text-xs text-gray-500 flex flex-col">Layout
            <select aria-label="Layout" className={INPUT} value={groupBy} onChange={e => setGroupBy(e.target.value as ProfitGroupBy)}>
              {layouts.map(l => <option key={l.value} value={l.value}>{l.label}</option>)}
            </select>
          </label>
        )}
        {groupBy === "PRODUCT" && (
          <select aria-label="Category" className={INPUT} value={category} onChange={e => setCategory(e.target.value)}>
            <option value="">All categories</option>
            {categories.map(c => <option key={c} value={c}>{c || "No category"}</option>)}
          </select>
        )}
        <input type="search" aria-label="Search" placeholder="Type text to search" className={`${INPUT} ml-auto w-56`}
          value={search} onChange={e => setSearch(e.target.value)} />
      </div>
      {(csvError || error) && <p role="alert" className="mb-3 text-sm text-red-600">{csvError || errorText(error, "profit")}</p>}
      {isLoading && <p className="text-sm text-gray-400">Working it out…</p>}
      {data && (
        <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
          <table className="w-full text-sm" data-testid="profit-table">
            <thead>
              <tr className="text-left text-xs text-gray-500 border-b border-gray-200">
                {cols.includes("rep") && cols[0] === "rep" && <th className="px-3 py-2 font-medium">Sales representative</th>}
                {cols.includes("customer") && <th className="px-3 py-2 font-medium">Customer</th>}
                {cols.includes("rep") && cols[0] !== "rep" && <th className="px-3 py-2 font-medium">Sales representative</th>}
                {cols.includes("order") && <><th className="px-3 font-medium">Order #</th><th className="px-3 font-medium">Invoice #</th><th className="px-3 font-medium">Credit note #</th></>}
                {cols.includes("product") && <><th className="px-3 py-2 font-medium">SKU</th><th className="px-3 font-medium">Product</th><th className="px-3 font-medium">Category</th><th className="px-3 font-medium text-right">Quantity</th></>}
                <th className="px-3 font-medium text-right">Invoice</th>
                <th className="px-3 font-medium text-right">Sale</th>
                <th className="px-3 font-medium text-right">COGS</th>
                <th className="px-3 font-medium text-right">Profit</th>
                <th className="px-3 font-medium text-right">Gross margin</th>
                <th className="px-3 font-medium text-right">Markup</th>
              </tr>
            </thead>
            <tbody className="tabular-nums">
              {rows.map((r, i) => <ProfitRow key={i} r={r} cols={cols} />)}
              {rows.length === 0 && <tr><td colSpan={14} className="px-3 py-6 text-center text-gray-500">No sales in this period</td></tr>}
            </tbody>
            {t && (
              <tfoot>
                <tr className="bg-gray-50 font-semibold border-t border-gray-200" data-testid="profit-total">
                  <td className="px-3 py-2" colSpan={cols.includes("product") ? 3 : cols.length + (cols.includes("order") ? 2 : 0)}>Grand Total</td>
                  {cols.includes("product") && <td className="px-3 text-right">{qty(t.quantity)}</td>}
                  <td className="px-3 text-right">{formatGBP(t.invoiced)}</td>
                  <td className="px-3 text-right">{formatGBP(t.sale)}</td>
                  <td className="px-3 text-right">{formatGBP(t.cogs)}</td>
                  <td className="px-3 text-right">{formatGBP(t.profit)}</td>
                  <td className="px-3 text-right">{pct(t.grossMargin)}</td>
                  <td className="px-3 text-right">{pct(t.markup)}</td>
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
      <p className="mt-2 text-xs text-gray-500">
        Sale = invoices less credit notes in the period, without VAT; stock products only (charges and services left out, as in Cin7).
        COGS is FIFO. &ldquo;Only COGS within the date range&rdquo; counts goods shipped or returned in the period, so a sale shipped
        but not invoiced yet shows cost and no sale. &ldquo;All COGS&rdquo; gives each invoice the cost of what it invoices.
      </p>
    </div>
  );
}

function ProfitRow({ r, cols }: { r: SalesProfitRow; cols: Column[] }) {
  const customer = r.customerId ? <Link href={`/customers/${r.customerId}`} className="text-brand-700 hover:underline">{r.customer}</Link> : r.customer;
  const rep = r.salesRep || <span className="text-gray-400">No rep</span>;
  return (
    <tr className="border-b border-gray-100">
      {cols.includes("rep") && cols[0] === "rep" && <td className="px-3 py-1.5">{rep}</td>}
      {cols.includes("customer") && <td className="px-3 py-1.5">{customer}</td>}
      {cols.includes("rep") && cols[0] !== "rep" && <td className="px-3 py-1.5">{rep}</td>}
      {cols.includes("order") && <>
        <td className="px-3">{r.saleOrderId ? <Link href={`/sale-orders/${r.saleOrderId}`} className="text-brand-700 hover:underline">{r.orderNumber}</Link> : ""}</td>
        <td className="px-3">{r.invoices}</td>
        <td className="px-3">{r.creditNotes}</td>
      </>}
      {cols.includes("product") && <>
        <td className="px-3 py-1.5">{r.productId ? <Link href={`/products/${r.productId}`} className="text-brand-700 hover:underline">{r.sku}</Link> : r.sku}</td>
        <td className="px-3">{r.productName}</td>
        <td className="px-3 text-gray-600">{r.category}</td>
        <td className="px-3 text-right">{qty(r.quantity)}</td>
      </>}
      <td className="px-3 text-right">{formatGBP(r.invoiced)}</td>
      <td className="px-3 text-right">{formatGBP(r.sale)}</td>
      <td className="px-3 text-right">{formatGBP(r.cogs)}</td>
      <td className={`px-3 text-right ${Number(r.profit) < 0 ? "text-red-600" : ""}`}>{formatGBP(r.profit)}</td>
      <td className="px-3 text-right">{pct(r.grossMargin)}</td>
      <td className="px-3 text-right">{pct(r.markup)}</td>
    </tr>
  );
}
