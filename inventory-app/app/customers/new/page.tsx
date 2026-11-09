"use client";

import { useRouter } from "next/navigation";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import CustomerForm from "../customer-form";

// A new customer on its own page (review R2, rule D1); saving opens the customer's page
export default function NewCustomerPage() {
  const router = useRouter();
  return (
    <div>
      <BackLink href="/customers" />
      <RecordHeader title="New customer" />
      <CustomerForm onSaved={c => router.push(`/customers/${c.id}`)} onCancel={() => router.push("/customers")} />
    </div>
  );
}
