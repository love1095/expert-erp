"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { getCustomers, getWarehouses } from "@/lib/api";
import { Customer, SaleOrder, Warehouse } from "@/types";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import SaleOrderForm from "../sale-order-form";

// A new quote or sale order on its own page (review R2, rule D1); saving opens the order's page
export default function NewSaleOrderPage() {
  const qc = useQueryClient();
  const router = useRouter();
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  const { data: customers = [] } = useQuery<Customer[]>({ queryKey: ["customers"], queryFn: getCustomers });

  const onCreated = (so: SaleOrder) => {
    qc.invalidateQueries({ queryKey: ["sale-orders"] });
    qc.invalidateQueries({ queryKey: ["customers"] });
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    router.push(`/sale-orders/${so.id}`);
  };

  return (
    <div>
      <BackLink href="/sale-orders" />
      <RecordHeader title="New sale" subtitle="quote or order" />
      <SaleOrderForm customers={customers} warehouses={warehouses} onCreated={onCreated} onCancel={() => router.push("/sale-orders")} />
    </div>
  );
}
