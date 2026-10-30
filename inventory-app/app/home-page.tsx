"use client";

import Link from "next/link";
import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getDashboard, getDashboardReorder } from "@/lib/api";
import { Dashboard, DashboardSeller, Page, ReorderLine, SaleOrderStatus } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import Pagination from "@/components/Pagination";
import { PERIOD_LABELS, Period, PeriodKey, periodFor, ukDate } from "@/lib/periods";
import { DailyBars, InOutBars, RankedBars, dayLabel } from "./dashboard-charts";

const STATUS_COLOR: Record<SaleOrderStatus, string> = {
  DRAFT:     "bg-amber-100 text-amber-700",
  CONFIRMED: "bg-sky-100 text-sky-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-gray-100 text-gray-500",
};

function Tile({ label, value, hint, href, testId, warn }: {
  label: string; value: string; hint?: string; href?: string; testId: string; warn?: boolean;
}) {
  const body = (
    <>
      <p className="text-xs font-medium text-gray-500">{label}</p>
      <p className={`text-2xl font-semibold tabular-nums mt-1 ${warn ? "text-red-600" : "text-gray-900"}`}>{value}</p>
      {hint && <p className="text-xs text-gray-400 mt-0.5">{hint}</p>}
    </>
  );
  const cls = "block bg-white border border-gray-200 rounded-xl px-5 py-4";
  return href
    ? <Link href={href} data-testid={testId} className={`${cls} hover:shadow-md transition-shadow`}>{body}</Link>
    : <div data-testid={testId} className={cls}>{body}</div>;
}

function Panel({ title, children, action }: { title: string; children: React.ReactNode; action?: React.ReactNode }) {
  return (
    <section className="bg-white border border-gray-200 rounded-xl p-5">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-semibold text-gray-800">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

export default function DashboardPage() {
  const [periodKey, setPeriodKey] = useState<PeriodKey>("last30");
  const [period, setPeriod] = useState<Period>(() => periodFor("last30"));
  const { data, isLoading, isError } = useQuery<Dashboard>({
    queryKey: ["dashboard", period.from, period.to],
    queryFn: () => getDashboard(period),
    // Keep the figures (and the panels' state) on screen while another period loads
    placeholderData: keepPreviousData,
  });

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="text-2xl font-bold">Dashboard</h1>
          <p className="text-gray-500 text-sm mt-0.5">Stock, orders and sales at a glance</p>
        </div>
        <PeriodPicker periodKey={periodKey} period={period}
          onChange={(key, p) => { setPeriodKey(key); setPeriod(p); }} />
      </div>

      {isError && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">Could not load the dashboard</p>}
      {isLoading || !data ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <DashboardBody data={data} periodLabel={periodKey === "custom" ? `${ukDate(data.from)} – ${ukDate(data.to)}` : PERIOD_LABELS[periodKey]} />
      )}
    </div>
  );
}

function PeriodPicker({ periodKey, period, onChange }: {
  periodKey: PeriodKey; period: Period; onChange: (key: PeriodKey, p: Period) => void;
}) {
  const [from, setFrom] = useState(period.from);
  const [to, setTo] = useState(period.to);
  const customInvalid = !from || !to || from > to;

  return (
    <div className="flex flex-wrap items-center gap-2">
      <label className="sr-only" htmlFor="dashboard-period">Period</label>
      <select id="dashboard-period" value={periodKey}
        onChange={e => {
          const key = e.target.value as PeriodKey;
          if (key === "custom") { setFrom(period.from); setTo(period.to); onChange(key, period); }
          else onChange(key, periodFor(key));
        }}
        className="border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm bg-white">
        {(Object.keys(PERIOD_LABELS) as PeriodKey[]).map(k => <option key={k} value={k}>{PERIOD_LABELS[k]}</option>)}
      </select>
      {periodKey === "custom" ? (
        <form className="flex items-center gap-1.5"
          onSubmit={e => { e.preventDefault(); if (!customInvalid) onChange("custom", { from, to }); }}>
          <input type="date" aria-label="From" value={from} onChange={e => setFrom(e.target.value)}
            className="border border-gray-300 rounded-lg px-2 py-1 text-sm" />
          <span className="text-gray-400 text-sm">–</span>
          <input type="date" aria-label="To" value={to} onChange={e => setTo(e.target.value)}
            className="border border-gray-300 rounded-lg px-2 py-1 text-sm" />
          <button type="submit" disabled={customInvalid}
            className="px-3 py-1.5 text-xs font-medium rounded-lg bg-indigo-600 text-white disabled:opacity-40">Apply</button>
        </form>
      ) : (
        <span data-testid="period-dates" className="text-xs text-gray-400 tabular-nums">{ukDate(period.from)} – {ukDate(period.to)}</span>
      )}
    </div>
  );
}

function DashboardBody({ data, periodLabel }: { data: Dashboard; periodLabel: string }) {
  const k = data.kpis;
  const [showTable, setShowTable] = useState(false);
  const [worst, setWorst] = useState(false);
  const sellers = worst ? data.worstSellers : data.topSellers;

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Tile testId="kpi-sales" label={`Sales, ${periodLabel}`} value={formatGBP(k.salesValueInPeriod)} hint={`${k.ordersInPeriod} order${k.ordersInPeriod !== 1 ? "s" : ""}`} href="/sale-orders" />
        <Tile testId="kpi-to-ship" label="Orders to ship" value={String(k.ordersToShip)} hint={`${k.draftOrders} draft${k.draftOrders !== 1 ? "s" : ""} to confirm`} href="/sale-orders" />
        <Tile testId="kpi-stock-value" label="Stock value (at cost)" value={formatGBP(k.stockValue)} hint={`${k.products} products · ${k.warehouses} warehouses`} href="/stock-overview" />
        <Tile testId="kpi-reorder" label="Products to reorder" value={String(k.productsToReorder)} hint="Stock + on order at or below minimum" warn={k.productsToReorder > 0} />
        <Tile testId="kpi-shipped" label={`Units shipped, ${periodLabel}`} value={k.unitsShippedInPeriod.toLocaleString()} />
        <Tile testId="kpi-receivables" label="Unpaid sales invoices" value={formatGBP(k.receivablesOutstanding)} href="/invoices" />
        <Tile testId="kpi-customers" label="Active customers" value={k.customers.toLocaleString()} href="/customers" />
        <div data-testid="orders-by-status" className="bg-white border border-gray-200 rounded-xl px-5 py-4">
          <p className="text-xs font-medium text-gray-500 mb-2">Sale orders by status</p>
          <div className="flex flex-wrap gap-1.5">
            {(Object.keys(data.ordersByStatus) as SaleOrderStatus[]).map(s => (
              <span key={s} className={`text-xs font-semibold px-2 py-0.5 rounded tabular-nums ${STATUS_COLOR[s]}`}>
                {s} {data.ordersByStatus[s]}
              </span>
            ))}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <Panel title="Sales — waiting now">
          <div className="grid grid-cols-2 sm:grid-cols-5 gap-2" data-testid="sales-status">
            <Count label="Draft orders" value={data.sales.draftOrders} href="/sale-orders" />
            <Count label="To ship" value={data.sales.toShip} href="/sale-orders" />
            <Count label="Draft invoices" value={data.sales.draftInvoices} href="/invoices" />
            <Count label="Awaiting payment" value={data.sales.awaitingPayment} href="/invoices" />
            <Count label="Overdue invoices" value={data.sales.overdueInvoices} href="/invoices" warn />
          </div>
        </Panel>
        <Panel title="Purchases — waiting now">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2" data-testid="purchase-status">
            <Count label="Awaiting delivery" value={data.purchases.awaitingDelivery} href="/purchase-orders" />
            <Count label="Draft bills" value={data.purchases.draftInvoices} href="/invoices" />
            <Count label="Bills to pay" value={data.purchases.awaitingPayment} href="/invoices" />
            <Count label="Overdue bills" value={data.purchases.overdueInvoices} href="/invoices" warn />
          </div>
        </Panel>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <Panel title="Sales value per day (£, excl. cancelled)"
          action={<button onClick={() => setShowTable(!showTable)} className="text-xs text-indigo-600 hover:text-indigo-800">{showTable ? "Hide table" : "Show as table"}</button>}>
          <DailyBars label="Sales value per day" format={formatGBP}
            data={data.daily.map(d => ({ date: d.date, value: d.salesValue }))} />
        </Panel>
        <Panel title="Units in and out per day (transfers excluded)">
          <InOutBars data={data.daily} />
        </Panel>
      </div>

      {showTable && (
        <Panel title="Per day">
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-sm" data-testid="daily-table">
              <thead className="text-xs text-gray-500 text-left sticky top-0 bg-white">
                <tr><th className="py-1.5">Day</th><th>Orders</th><th>Sales</th><th>Units in</th><th>Units out</th></tr>
              </thead>
              <tbody className="divide-y divide-gray-100 tabular-nums">
                {[...data.daily].reverse().map(d => (
                  <tr key={d.date}>
                    <td className="py-1.5 text-gray-600">{dayLabel(d.date)}</td><td>{d.orders}</td><td>{formatGBP(d.salesValue)}</td>
                    <td>{d.unitsIn.toLocaleString()}</td><td>{d.unitsOut.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        <Panel title={`${worst ? "Worst" : "Top"} sellers, ${periodLabel}`}
          action={
            <div role="group" aria-label="Sellers" className="inline-flex rounded-md border border-gray-200 p-0.5">
              {[false, true].map(w => (
                <button key={String(w)} onClick={() => setWorst(w)} aria-pressed={worst === w}
                  className={`px-2 py-0.5 text-xs rounded ${worst === w ? "bg-indigo-50 text-indigo-700" : "text-gray-500 hover:text-gray-800"}`}>
                  {w ? "Worst" : "Top"}
                </button>
              ))}
            </div>
          }>
          {sellers.length === 0 ? <p className="text-sm text-gray-400">Nothing ordered in this period</p> : (
            <div data-testid="sellers">
              <RankedBars format={formatGBP}
                items={sellers.map((p: DashboardSeller) => ({ key: p.productId, label: p.name, sub: `${p.sku} · ${p.quantity.toLocaleString()} sold`, value: p.value }))} />
            </div>
          )}
        </Panel>

        <ReorderPanel />

        <Panel title="Recent sale orders" action={<Link href="/sale-orders" className="text-xs text-indigo-600 hover:text-indigo-800">All orders</Link>}>
          {data.recentOrders.length === 0 ? <p className="text-sm text-gray-400">No sale orders yet</p> : (
            <ul className="divide-y divide-gray-100" data-testid="recent-orders">
              {data.recentOrders.map(o => (
                <li key={o.id} className="py-2 flex items-center justify-between gap-3 text-sm">
                  <div className="min-w-0">
                    <div className="truncate text-gray-800"><span className="font-mono text-xs text-gray-400 mr-1.5">SO-{o.id}</span>{o.customerName}</div>
                    <div className="text-xs text-gray-400"><ClientDate iso={o.createdAt} /></div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className="tabular-nums text-gray-700">{formatGBP(o.total)}</div>
                    <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${STATUS_COLOR[o.status]}`}>{o.status}</span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}

function Count({ label, value, href, warn }: { label: string; value: number; href: string; warn?: boolean }) {
  return (
    <Link href={href} className="block rounded-lg bg-gray-50 hover:bg-gray-100 px-3 py-2">
      <p className="text-[11px] text-gray-500">{label}</p>
      <p className={`text-lg font-semibold tabular-nums ${warn && value > 0 ? "text-red-600" : "text-gray-900"}`}>{value.toLocaleString()}</p>
    </Link>
  );
}

// Stock + on order at or below the minimum (Cin7's rule); its own query so searching and paging don't reload the dashboard
function ReorderPanel() {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const { data } = useQuery<Page<ReorderLine>>({
    queryKey: ["dashboard-reorder", query, page],
    queryFn: () => getDashboardReorder({ search: query || undefined, page, size: 10 }),
    placeholderData: keepPreviousData,
  });

  return (
    <Panel title="Reorder">
      <form className="flex gap-1.5 mb-3" onSubmit={e => { e.preventDefault(); setQuery(search.trim()); setPage(0); }}>
        <input value={search} onChange={e => setSearch(e.target.value)} placeholder="SKU, product or supplier"
          aria-label="Search the reorder list" className="flex-1 min-w-0 border border-gray-300 rounded-lg px-2.5 py-1 text-sm" />
        <button type="submit" className="px-3 py-1 text-xs font-medium rounded-lg border border-gray-300 hover:bg-gray-50">Search</button>
      </form>
      {!data ? <p className="text-sm text-gray-400">Loading...</p> : data.content.length === 0 ? (
        <p className="text-sm text-gray-400">{query ? "No match" : "Nothing to reorder"}</p>
      ) : (
        <>
          <table className="w-full text-sm" data-testid="reorder">
            <thead className="text-xs text-gray-500 text-left">
              <tr><th className="pb-1.5">Product</th><th className="text-right" title="Stock + on order">Avail. + ord.</th><th className="text-right">Min</th><th className="text-right">Order</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.content.map(l => (
                <tr key={l.productId}>
                  <td className="py-1.5 pr-2">
                    <div className="text-gray-800 truncate max-w-[14rem]" title={l.name}>{l.name}</div>
                    <div className="text-xs text-gray-400">{l.sku}{l.supplierName && ` · ${l.supplierName}`}</div>
                  </td>
                  <td className="text-right tabular-nums font-semibold text-red-600"
                    title={`${l.stock.toLocaleString()} in stock + ${l.onOrder.toLocaleString()} on order`}>
                    {(l.stock + l.onOrder).toLocaleString()}
                  </td>
                  <td className="text-right tabular-nums text-gray-500">{l.minStock.toLocaleString()}</td>
                  <td className="text-right tabular-nums text-gray-700">{l.suggestedQuantity.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage} />
        </>
      )}
    </Panel>
  );
}
