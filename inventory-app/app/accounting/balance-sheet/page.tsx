"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getBalanceSheet } from "@/lib/api";
import { BalanceSheet } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { CompareControls, ExportButton, INPUT, StatementSectionRows, StatementTotalRow, errorOf, today } from "../ledger-parts";

/**
 * Phase K2: Xero's Balance Sheet — assets, liabilities, net assets and equity, where Current Year Earnings and
 * Retained Earnings come from the financial year end (no closing journal).
 */
export default function BalanceSheetPage() {
  const [date, setDate] = useState(today());
  const [compare, setCompare] = useState(0);
  const [step, setStep] = useState("YEAR");
  const { data, isLoading, error } = useQuery<BalanceSheet>({
    queryKey: ["balance-sheet", date, compare, step], queryFn: () => getBalanceSheet({ date, compare, step }), enabled: !!date,
  });
  const from = "1900-01-01";
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Balance sheet" />
      <div className="flex flex-wrap gap-2 items-center text-sm">
        <label className="flex items-center gap-1">As at <input aria-label="As at" type="date" className={INPUT} value={date} onChange={e => setDate(e.target.value)} /></label>
        <CompareControls compare={compare} step={step} onCompare={setCompare} onStep={setStep} />
        <ExportButton name="balance-sheet.csv" params={{ date, compare, step }} filename={`Balance sheet ${date}.csv`} />
        <ExportButton name="balance-sheet.pdf" params={{ date, compare, step }} filename={`Balance sheet ${date}.pdf`} label="Export PDF" />
      </div>
      {error && <p role="alert" className="text-sm text-red-700">{errorOf(error)}</p>}
      {isLoading || !data ? (!error && <p className="text-sm text-gray-500">Loading…</p>) : (<>
        {data.warnings.length > 0 && <ul className="text-sm text-amber-800 bg-amber-50 rounded px-4 py-2">{data.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
        <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="balance-sheet">
          <thead className="text-xs text-gray-500 bg-gray-50">
            <tr><th className="px-4 py-2 text-left" />{data.columns.map(c => <th key={c.label} className="text-right px-4 py-2">{c.label}</th>)}</tr>
          </thead>
          {data.assets.map(s => <StatementSectionRows key={s.title} section={s} from={from} to={date} />)}
          <StatementTotalRow total={data.totalAssets} />
          {data.liabilities.map(s => <StatementSectionRows key={s.title} section={s} from={from} to={date} />)}
          <StatementTotalRow total={data.totalLiabilities} />
          <StatementTotalRow total={data.netAssets} strong />
          <StatementSectionRows section={data.equity} from={from} to={date} />
        </table>
      </>)}
    </div>
  );
}
