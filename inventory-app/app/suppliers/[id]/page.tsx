"use client";

import { use } from "react";
import SupplierView from "../supplier-view";

// One supplier on its own page (review S6), like the customer page
export default function SupplierPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <SupplierView key={id} supplierId={Number(id)} />;
}
