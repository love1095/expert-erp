"use client";

import { use } from "react";
import ProductView from "../product-view";

export default function ProductPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <ProductView key={id} productId={Number(id)} />;
}
