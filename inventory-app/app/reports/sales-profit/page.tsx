"use client";

import { SalesProfitPage } from "../report-parts";

/** Phase H4: Cin7's Sales Profit Summary, per customer (its default layout) or per order. */
export default function Page() {
  return <SalesProfitPage testId="sales-profit" title="Sales Profit Summary"
    subtitle="Amount invoiced, COGS, profit, gross margin and markup per customer or per order"
    layouts={[{ value: "CUSTOMER", label: "Per customer" }, { value: "ORDER", label: "Per order" }]} />;
}
