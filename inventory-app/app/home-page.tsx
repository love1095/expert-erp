"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getDashboard } from "@/lib/api";
import { Dashboard, SaleOrderStatus } from "@/types";
import { formatGBP } from "@/lib/currency";
import { ClientDate } from "@/components/ClientDate";
import { DailyBars, InOutBars, RankedBars, dayLabel } from "./dashboard-charts";

const PERIODS = [7, 30, 90];

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
  const [days, setDays] = useState(30);
  const { data, isLoading, isError } = useQuery<Dashboard>({
    queryKey: ["dashboard", days],
    queryFn: () => getDashboard(days),
  });

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Dashboard</h1>
          <p className="text-gray-500 text-sm mt-0.5">Stock, orders and sales at a glance</p>
        </div>
        <div role="group" aria-label="Period" className="inline-flex rounded-lg border border-gray-200 bg-white p-0.5">
          {PERIODS.map(p => (
            <button key={p} onClick={() => setDays(p)} aria-pressed={days === p}
              className={`px-3 py-1.5 text-xs font-medium rounded-md ${days === p ? "bg-indigo-50 text-indigo-700" : "text-gray-500 hover:text-gray-800"}`}>
              {p} days
            </button>
          ))}
        </div>
      </div>

      {isError && <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">Could not load the dashboard</p>}
      {isLoading || !data ? <p className="text-gray-400 text-sm">Loading...</p> : (
        <DashboardBody data={data} />
      )}
    </div>
  );
}

function DashboardBody({ data }: { data: Dashboard }) {
  const k = data.kpis;
  const [showTable, setShowTable] = useState(false);

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Tile testId="kpi-sales" label={`Sales, last ${data.days} days`} value={formatGBP(k.salesValueInPeriod)} hint={`${k.ordersInPeriod} order${k.ordersInPeriod !== 1 ? "s" : ""}`} href="/sale-orders" />
        <Tile testId="kpi-to-ship" label="Orders to ship" value={String(k.ordersToShip)} hint={`${k.draftOrders} draft${k.draftOrders !== 1 ? "s" : ""} to confirm`} href="/sale-orders" />
        <Tile testId="kpi-stock-value" label="Stock value (at cost)" value={formatGBP(k.stockValue)} hint={`${k.products} products · ${k.warehouses} warehouses`} href="/stock-overview" />
        <Tile testId="kpi-low-stock" label="Low stock products" value={String(k.lowStockProducts)} hint="Below their minimum" href="/stock-overview" warn={k.lowStockProducts > 0} />
        <Tile testId="kpi-shipped" label={`Units shipped, last ${data.days} days`} value={k.unitsShippedInPeriod.toLocaleString()} />
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
        <Panel title={`Top products shipped, last ${data.days} days`}>
          {data.topProducts.length === 0 ? <p className="text-sm text-gray-400">Nothing shipped in this period</p> : (
            <div data-testid="top-products">
              <RankedBars items={data.topProducts.map(p => ({ key: p.productId, label: p.name, sub: p.sku, value: p.unitsShipped }))} />
            </div>
          )}
        </Panel>

        <Panel title="Low stock" action={<Link href="/stock-overview" className="text-xs text-indigo-600 hover:text-indigo-800">Stock overview</Link>}>
          {data.lowStock.length === 0 ? <p className="text-sm text-gray-400">Every product is at or above its minimum</p> : (
            <table className="w-full text-sm" data-testid="low-stock">
              <thead className="text-xs text-gray-500 text-left"><tr><th className="pb-1.5">Product</th><th className="text-right">Stock</th><th className="text-right">Min</th></tr></thead>
              <tbody className="divide-y divide-gray-100">
                {data.lowStock.map(l => (
                  <tr key={l.productId}>
                    <td className="py-1.5"><div className="text-gray-800">{l.name}</div><div className="text-xs text-gray-400">{l.sku}</div></td>
                    <td className="text-right tabular-nums font-semibold text-red-600">{l.stock.toLocaleString()}</td>
                    <td className="text-right tabular-nums text-gray-500">{l.minStock.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>

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
