"use client";

import { useRouter } from "next/navigation";
import { BackLink, RecordHeader } from "@/components/DetailParts";
import JournalForm from "../journal-form";

// A new manual journal on its own page (review R2, rule D1); saving opens the journal's page
export default function NewManualJournalPage() {
  const router = useRouter();
  return (
    <div className="max-w-6xl">
      <BackLink href="/accounting/manual-journals" />
      <RecordHeader title="New manual journal" />
      <JournalForm onSaved={j => router.push(`/accounting/manual-journals/${j.id}`)} onCancel={() => router.push("/accounting/manual-journals")} />
    </div>
  );
}
