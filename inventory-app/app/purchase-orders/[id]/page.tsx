"use client";

import { use } from "react";
import PurchaseOrderView from "../purchase-order-view";

export default function PurchaseOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <PurchaseOrderView key={id} orderId={Number(id)} />;
}
