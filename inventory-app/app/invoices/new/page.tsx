"use client";

import { useRouter } from "next/navigation";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import InvoiceForm from "../invoice-form";

// A hand-typed invoice or bill on its own page (review R2, rule D1); saving opens it
export default function NewInvoicePage() {
  const router = useRouter();
  return (
    <div>
      <BackLink href="/invoices" />
      <RecordHeader title="New invoice" subtitle="or supplier bill" />
      <InvoiceForm onCreated={inv => router.push(`/invoices/${inv.id}`)} onCancel={() => router.push("/invoices")} />
    </div>
  );
}
