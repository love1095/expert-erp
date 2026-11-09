"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { getWarehouses } from "@/lib/api";
import { Warehouse } from "@/types";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import PurchaseOrderForm from "../purchase-order-form";

// A new purchase order on its own page (review R2, rule D1); saving opens the PO's page
export default function NewPurchaseOrderPage() {
  const qc = useQueryClient();
  const router = useRouter();
  const { data: warehouses = [] } = useQuery<Warehouse[]>({ queryKey: ["warehouses"], queryFn: getWarehouses });
  return (
    <div>
      <BackLink href="/purchase-orders" />
      <RecordHeader title="New purchase order" />
      <PurchaseOrderForm warehouses={warehouses} onCancel={() => router.push("/purchase-orders")}
        onSaved={po => { qc.invalidateQueries({ queryKey: ["purchase-orders"] }); router.push(`/purchase-orders/${po.id}`); }} />
    </div>
  );
}
