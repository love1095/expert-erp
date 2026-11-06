"use client";

import { SalesProfitPage } from "../report-parts";

/** Phase H4: Cin7's Sales by Product Summary: quantity, amount invoiced, COGS and profit per product. */
export default function Page() {
  return <SalesProfitPage testId="sales-by-product" title="Sales by Product Summary"
    subtitle="Quantity, amount invoiced, COGS and profit per product"
    layouts={[{ value: "PRODUCT", label: "Per product" }]} />;
}
