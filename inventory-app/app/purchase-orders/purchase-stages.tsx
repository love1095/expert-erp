import { PurchaseOrder } from "@/types";
import { StageIcons, StageState, closeStages } from "../sale-orders/sale-stages";

// Cin7's icons on the purchase list: ordered → received → billed → paid (green done, amber in part, grey not yet,
// red where it was cancelled)
export function purchaseStagesOf(po: PurchaseOrder): { key: string; label: string; state: StageState }[] {
  const cancelled = po.status === "CANCELLED";
  const part = (s: string, partial: string, full: string): StageState => (s === full ? "done" : s === partial ? "part" : "todo");
  return closeStages<{ key: string; label: string; state: StageState }>([
    { key: "order", label: "Ordered", state: cancelled ? "off" : po.status === "DRAFT" ? "part" : "done" },
    { key: "receive", label: "Received", state: part(po.receivingStatus, "PARTIALLY_RECEIVED", "RECEIVED") },
    { key: "invoice", label: "Billed", state: part(po.billingStatus, "PARTIALLY_BILLED", "BILLED") },
    { key: "paid", label: "Paid", state: po.paymentStatus ? part(po.paymentStatus, "PARTIALLY_PAID", "PAID") : "todo" },
  ], cancelled);
}

export default function PurchaseStages({ po }: { po: PurchaseOrder }) {
  return <StageIcons stages={purchaseStagesOf(po)} testId={`po-stages-${po.id}`} />;
}
