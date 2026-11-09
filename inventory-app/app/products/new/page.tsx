"use client";

import { useRouter } from "next/navigation";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import ProductForm from "../product-form";

// A new product on its own page (review R2, rule D1); saving opens the product's page
export default function NewProductPage() {
  const router = useRouter();
  return (
    <div>
      <BackLink href="/products" />
      <RecordHeader title="New product" />
      <ProductForm onSaved={p => router.push(`/products/${p.id}`)} onCancel={() => router.push("/products")} />
    </div>
  );
}
