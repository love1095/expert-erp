"use client";

import { use } from "react";
import CustomerView from "../customer-view";

export default function CustomerPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <CustomerView key={id} customerId={Number(id)} />;
}
