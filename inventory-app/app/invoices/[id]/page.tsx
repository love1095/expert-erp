"use client";

import { use } from "react";
import InvoiceView from "../invoice-view";

export default function InvoicePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <InvoiceView key={id} invoiceId={Number(id)} />;
}
