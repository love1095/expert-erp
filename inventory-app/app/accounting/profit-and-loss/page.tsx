"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getProfitAndLoss } from "@/lib/api";
import { ProfitAndLoss } from "@/types";
import { PageHeader } from "@/components/ListToolbar";
import { CompareControls, ExportButton, INPUT, StatementSectionRows, StatementTotalRow, errorOf, today } from "../ledger-parts";

const monthStart = () => `${today().slice(0, 7)}-01`;

/** Phase K2: Xero's Profit and Loss — trading income, cost of sales, gross profit, other income, operating expenses, net profit. */
export default function ProfitAndLossPage() {
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const [compare, setCompare] = useState(0);
  const [step, setStep] = useState("MONTH");
  const { data, isLoading, error } = useQuery<ProfitAndLoss>({
    queryKey: ["profit-and-loss", from, to, compare, step], queryFn: () => getProfitAndLoss({ from, to, compare, step }), enabled: !!from && !!to,
  });
  return (
    <div className="p-6 max-w-6xl space-y-4">
      <PageHeader title="Profit and loss" />
      <div className="flex flex-wrap gap-2 items-center text-sm">
        <input aria-label="From" type="date" className={INPUT} value={from} onChange={e => setFrom(e.target.value)} />
        <input aria-label="To" type="date" className={INPUT} value={to} onChange={e => setTo(e.target.value)} />
        <CompareControls compare={compare} step={step} onCompare={setCompare} onStep={setStep} />
        <ExportButton name="profit-and-loss.csv" params={{ from, to, compare, step }} filename={`Profit and loss ${from} to ${to}.csv`} />
        <ExportButton name="profit-and-loss.pdf" params={{ from, to, compare, step }} filename={`Profit and loss ${from} to ${to}.pdf`} label="Export PDF" />
      </div>
      {error && <p role="alert" className="text-sm text-red-700">{errorOf(error)}</p>}
      {isLoading || !data ? (!error && <p className="text-sm text-gray-500">Loading…</p>) : (
        <table className="w-full text-sm bg-white border border-gray-200 rounded-xl" data-testid="profit-and-loss">
          <thead className="text-xs text-gray-500 bg-gray-50">
            <tr><th className="px-4 py-2 text-left" />{data.columns.map(c => <th key={c.label} className="text-right px-4 py-2">{c.label}</th>)}</tr>
          </thead>
          <StatementSectionRows section={data.tradingIncome} from={from} to={to} />
          <StatementSectionRows section={data.costOfSales} from={from} to={to} />
          <StatementTotalRow total={data.grossProfit} />
          <StatementSectionRows section={data.otherIncome} from={from} to={to} />
          <StatementSectionRows section={data.operatingExpenses} from={from} to={to} />
          <StatementTotalRow total={data.netProfit} strong />
        </table>
      )}
    </div>
  );
}
