"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getCutoverCheck } from "@/lib/api";
import { CutoverCheck, CutoverFigure } from "@/types";
import { formatGBP } from "@/lib/currency";
import { PageHeader } from "@/components/ListToolbar";

const show = (f: CutoverFigure, v: number) => f.money ? formatGBP(v) : Number(v).toLocaleString("en-GB", { maximumFractionDigits: 4 });

/**
 * Phase J: the go-live check. The ERP's figures next to the old system's (typed in from Cin7's reports) on the cutover
 * day: stock per warehouse, money owed both ways, open orders, number series. Each line is green when they match.
 * The Cin7 figures typed in are kept only in this browser tab.
 */
export default function GoLivePage() {
  const { data, isLoading, refetch, isFetching } = useQuery<CutoverCheck>({ queryKey: ["cutover-check"], queryFn: getCutoverCheck });
  const [cin7, setCin7] = useState<Record<string, string>>({});

  if (isLoading || !data) return <div className="p-6 text-sm text-gray-500">Loading…</div>;
  const groups = [...new Set(data.figures.map(f => f.group))];
  const checked = data.figures.filter(f => f.group !== "Numbers" && cin7[f.key]?.trim());
  const matching = checked.filter(f => Math.abs(Number(cin7[f.key].replace(/[£,]/g, "")) - f.value) < 0.005);

  return (
    <div className="p-6 max-w-5xl space-y-6">
      <PageHeader title="Go-live check" />
      <p className="text-sm text-gray-600">
        On the cutover day, after the imports: type in Cin7&apos;s figure for each line (where to find it is under the line).
        Go live when every line is green. Then set the Xero export start date to the first day here and turn off Cin7&apos;s Xero and Shopify links.
      </p>
      {data.warnings.length > 0 && (
        <ul className="text-sm text-amber-800 bg-amber-50 rounded-lg px-4 py-3 list-disc ml-0 pl-8 space-y-1" data-testid="go-live-warnings">
          {data.warnings.map((w, i) => <li key={i}>{w}</li>)}
        </ul>
      )}
      <div className="flex items-center gap-4 text-sm">
        <span data-testid="go-live-score">{matching.length} of {checked.length} checked lines match</span>
        <button onClick={() => refetch()} disabled={isFetching} className="border border-gray-300 px-3 py-1 rounded-md hover:bg-gray-50 disabled:opacity-50">
          {isFetching ? "Refreshing…" : "Refresh figures"}
        </button>
      </div>
      {groups.map(g => (
        <section key={g} className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <h2 className="px-4 py-2 bg-gray-50 text-sm font-semibold text-gray-700">{g}</h2>
          <table className="w-full text-sm">
            <thead className="text-xs text-gray-500 text-left">
              <tr><th className="px-4 py-2">Figure</th><th className="text-right">In the ERP</th>{g !== "Numbers" && <><th className="text-right px-4">In Cin7</th><th className="text-right px-4">Difference</th></>}</tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.figures.filter(f => f.group === g).map(f => {
                const typed = cin7[f.key]?.trim();
                const other = typed ? Number(typed.replace(/[£,]/g, "")) : null;
                const diff = other == null || Number.isNaN(other) ? null : f.value - other;
                const ok = diff != null && Math.abs(diff) < 0.005;
                return (
                  <tr key={f.key} data-testid={`figure-${f.key}`} className={diff == null ? "" : ok ? "bg-green-50" : "bg-red-50"}>
                    <td className="px-4 py-2">
                      <div>{f.label}</div>
                      <div className="text-xs text-gray-400">Cin7: {f.cin7}</div>
                    </td>
                    <td className="text-right tabular-nums font-medium">{g === "Numbers" ? "" : show(f, f.value)}</td>
                    {g !== "Numbers" && <>
                      <td className="text-right px-4">
                        <input aria-label={`Cin7: ${f.label}`} value={cin7[f.key] ?? ""} inputMode="decimal"
                          onChange={e => setCin7(c => ({ ...c, [f.key]: e.target.value }))}
                          className="w-32 border border-gray-300 rounded-md px-2 py-1 text-right text-sm tabular-nums" />
                      </td>
                      <td className={`text-right px-4 tabular-nums ${ok ? "text-green-700" : "text-red-700"}`}>
                        {diff == null ? "" : ok ? "✓ matches" : show(f, diff)}
                      </td>
                    </>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  );
}
