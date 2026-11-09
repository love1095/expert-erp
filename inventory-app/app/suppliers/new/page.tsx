"use client";

import { useRouter } from "next/navigation";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import SupplierForm from "../supplier-form";

// A new supplier on its own page (review R2, rule D1); saving opens the supplier's page
export default function NewSupplierPage() {
  const router = useRouter();
  return (
    <div>
      <BackLink href="/suppliers" />
      <RecordHeader title="New supplier" />
      <SupplierForm onSaved={s => router.push(`/suppliers/${s.id}`)} onCancel={() => router.push("/suppliers")} />
    </div>
  );
}
