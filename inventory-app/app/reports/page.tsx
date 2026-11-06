"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getCoreReports } from "@/lib/api";
import { CoreReportInfo } from "@/types";
import { useAuth } from "@/contexts/AuthContext";
import { PageHeader } from "@/components/ListToolbar";

// Reports built before I3 (their own pages), by Cin7's groups
const OTHER: (CoreReportInfo & { href: string; perm: string })[] = [
  { key: "sales-profit", group: "Sale", title: "Sales Profit Summary", description: "Amount invoiced, COGS, profit, gross margin and markup per customer or per order.", period: true, href: "/reports/sales-profit", perm: "REPORTS_FINANCIAL" },
  { key: "profit-by-rep", group: "Sale", title: "Profit by Sales Representatives", description: "Profit and margin of each sales representative's sales.", period: true, href: "/reports/profit-by-rep", perm: "REPORTS_FINANCIAL" },
  { key: "sales-by-product", group: "Sale", title: "Sales by Product Summary", description: "Quantity, amount invoiced, COGS and profit per product.", period: true, href: "/reports/sales-by-product", perm: "REPORTS_FINANCIAL" },
  { key: "inventory-movement", group: "Inventory", title: "Inventory Movement Summary", description: "Stock and its FIFO value at the start, in, out and at the end of a period.", period: true, href: "/reports/inventory-movement", perm: "REPORTS_FINANCIAL" },
  { key: "aged-debtors", group: "Financial", title: "Aged Debtors", description: "What each customer owes, by days overdue.", period: false, href: "/aged-debtors", perm: "REPORTS_FINANCIAL" },
  { key: "aged-creditors", group: "Financial", title: "Aged Creditors", description: "What is owed to each supplier, by days overdue.", period: false, href: "/aged-creditors", perm: "REPORTS_FINANCIAL" },
  { key: "vat-return", group: "Financial", title: "VAT Return", description: "HMRC's nine boxes for a VAT period, with the documents behind them.", period: true, href: "/vat-return", perm: "REPORTS_FINANCIAL" },
];
const GROUPS = ["Sale", "Purchase", "Inventory", "Financial"];

/** Phase I3: every report in one place, by Cin7's groups (Sale, Purchase, Inventory, Financial reports). */
export default function ReportsPage() {
  const { can } = useAuth();
  const { data: core = [] } = useQuery<CoreReportInfo[]>({ queryKey: ["core-reports"], queryFn: getCoreReports });
  const all = [...core.map(r => ({ ...r, href: `/reports/core/${r.key}` })), ...OTHER.filter(r => can(r.perm))];
  return (
    <div data-testid="reports">
      <PageHeader title="Reports" subtitle="Sale, purchase, inventory and financial reports, like Cin7's" />
      <div className="grid lg:grid-cols-2 gap-6">
        {GROUPS.map(g => {
          const list = all.filter(r => r.group === g);
          if (list.length === 0) return null;
          return (
            <section key={g} className="bg-white border border-gray-200 rounded-xl">
              <h2 className="px-5 py-3 border-b border-gray-200 font-semibold text-gray-800">{g} reports</h2>
              <ul className="divide-y divide-gray-100">
                {list.map(r => (
                  <li key={r.key} className="px-5 py-3">
                    <Link href={r.href} className="font-medium text-brand-700 hover:underline">{r.title}</Link>
                    <p className="text-xs text-gray-500 mt-0.5">{r.description}</p>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}
