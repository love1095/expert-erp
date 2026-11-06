"use client";

import { use, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getCoreReports, runCoreReport } from "@/lib/api";
import { CoreReport, CoreReportColumn, CoreReportInfo } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ukDate } from "@/lib/periods";
import { PageHeader } from "@/components/ListToolbar";
import { BTN_SECONDARY } from "@/components/DetailParts";
import { PeriodPicker, errorText, pct, qty, usePeriod } from "../../report-parts";

const INPUT = "border border-gray-300 rounded-md px-2 py-1.5 text-sm bg-white";
const RIGHT = new Set(["NUMBER", "MONEY", "PERCENT"]);

function show(c: CoreReportColumn, v: string | number | null | undefined) {
  if (v == null || v === "") return "";
  switch (c.type) {
    case "MONEY": return formatGBP(Number(v));
    case "PERCENT": return pct(Number(v));
    case "NUMBER": return qty(Number(v));
    case "DATE": return ukDate(String(v));
    default: return String(v);
  }
}

// What goes in the CSV: plain numbers and dates as shown
function csvValue(c: CoreReportColumn, v: string | number | null | undefined) {
  if (v == null) return "";
  const s = c.type === "DATE" ? ukDate(String(v)) : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/**
 * Phase I3: one of Cin7's everyday reports (Sales Overview, Sale Invoices & Credit Notes, Sales Backorder Details,
 * Pending Purchase Orders, Stock Received Not Invoiced, Stock Value Inventory Aging): period if it has one, search,
 * totals and CSV export.
 */
export default function CoreReportPage({ params }: { params: Promise<{ key: string }> }) {
  const { key } = use(params);
  const [period, setPeriod] = usePeriod();
  const [search, setSearch] = useState("");
  const { data: catalogue = [] } = useQuery<CoreReportInfo[]>({ queryKey: ["core-reports"], queryFn: getCoreReports });
  const info = catalogue.find(r => r.key === key);
  const usesPeriod = info?.period ?? false;
  const { data, isLoading, error } = useQuery<CoreReport>({
    queryKey: ["core-report", key, usesPeriod ? period.period : null],
    queryFn: () => runCoreReport(key, usesPeriod ? period.period.from : undefined, usesPeriod ? period.period.to : undefined),
    enabled: !!info,
  });

  const q = search.trim().toLowerCase();
  const rows = (data?.rows ?? []).filter(r => !q || data!.columns.some(c => String(r[c.key] ?? "").toLowerCase().includes(q)));
  const total = (k: string) => (q ? rows.reduce((s, r) => s + (Number(r[k]) || 0), 0) : data?.totals[k]);

  const exportCsv = () => {
    if (!data) return;
    const lines = [data.columns.map(c => csvValue(c, c.label)).join(","),
      ...rows.map(r => data.columns.map(c => csvValue(c, r[c.key])).join(","))];
    const blob = new Blob(["﻿" + lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${data.title.replace(/[^A-Za-z0-9]+/g, "_")}${usesPeriod ? `_${period.period.from}_${period.period.to}` : ""}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (catalogue.length > 0 && !info) {
    return <p role="alert" className="text-sm text-red-600">This report is not there, or your role does not allow it. <Link href="/reports" className="text-brand-700 hover:underline">All reports</Link></p>;
  }
  return (
    <div data-testid={`report-${key}`}>
      <PageHeader title={info?.title ?? "Report"} subtitle={info?.description}>
        <Link href="/reports" className={BTN_SECONDARY}>All reports</Link>
        <button type="button" className={BTN_SECONDARY} onClick={exportCsv} disabled={!data}>Export CSV</button>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-2 mb-3">
        {usesPeriod && <PeriodPicker value={period} onChange={setPeriod} />}
        <input type="search" aria-label="Search the report" placeholder="Type text to search" className={`${INPUT} ml-auto w-56`}
          value={search} onChange={e => setSearch(e.target.value)} />
      </div>
      {error && <p role="alert" className="mb-3 text-sm text-red-600">{errorText(error, "report")}</p>}
      {isLoading && <p className="text-sm text-gray-400">Working it out…</p>}
      {data && (
        <div className="bg-white border border-gray-200 rounded-lg overflow-x-auto">
          <table className="w-full text-sm" data-testid="core-report-table">
            <thead>
              <tr className="text-left text-xs text-gray-500 border-b border-gray-200">
                {data.columns.map(c => <th key={c.key} className={`px-3 py-2 font-medium whitespace-nowrap ${RIGHT.has(c.type) ? "text-right" : ""}`}>{c.label}</th>)}
              </tr>
            </thead>
            <tbody className="tabular-nums">
              {rows.map((r, i) => (
                <tr key={i} className="border-b border-gray-100">
                  {data.columns.map(c => {
                    const id = r[`${c.key}Id`];
                    const text = show(c, r[c.key]);
                    return (
                      <td key={c.key} className={`px-3 py-1.5 ${RIGHT.has(c.type) ? "text-right" : ""} ${c.type === "MONEY" && Number(r[c.key]) < 0 ? "text-red-600" : ""}`}>
                        {c.type === "LINK" && c.link && id != null && text
                          ? <Link href={c.link.replace("{id}", String(id))} className="text-brand-700 hover:underline">{text}</Link> : text}
                      </td>
                    );
                  })}
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={data.columns.length} className="px-3 py-6 text-center text-gray-500">Nothing to show</td></tr>}
            </tbody>
            {Object.keys(data.totals).length > 0 && (
              <tfoot>
                <tr className="bg-gray-50 font-semibold border-t border-gray-200" data-testid="core-report-total">
                  {data.columns.map((c, i) => (
                    <td key={c.key} className={`px-3 py-2 ${RIGHT.has(c.type) ? "text-right" : ""}`}>
                      {i === 0 ? `Grand Total (${rows.length})` : c.key in data.totals ? show(c, total(c.key) ?? 0) : ""}
                    </td>
                  ))}
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
    </div>
  );
}
