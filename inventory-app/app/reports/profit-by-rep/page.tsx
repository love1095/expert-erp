"use client";

import { SalesProfitPage } from "../report-parts";

/** Phase H4: Cin7's Profit by Sales Representatives, per customer or per order. */
export default function Page() {
  return <SalesProfitPage testId="profit-by-rep" title="Profit by Sales Representatives"
    subtitle="Amount invoiced, COGS, profit, gross margin and markup of each sales representative's sales"
    layouts={[{ value: "REP_CUSTOMER", label: "Per customer" }, { value: "REP_ORDER", label: "Per order" }, { value: "REP", label: "Totals per rep" }]} />;
}
