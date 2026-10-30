"use client";

import { use } from "react";
import { useQuery } from "@tanstack/react-query";
import { getWarehouses } from "@/lib/api";
import { Warehouse } from "@/types";
import SaleOrderView from "../sale-order-view";

// One sale order (or quote) on its own page, like Cin7's sale screen
export default function SaleOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  return <SaleOrderView key={id} orderId={Number(id)} warehouses={warehouses} />;
}
