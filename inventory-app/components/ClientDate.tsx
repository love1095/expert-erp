"use client";

import { DateText } from "@/components/ui/DateText";

/** Older name of DateText, kept for the pages that use it: the same UK date (09/11/2026) on every computer. */
export function ClientDate({ iso, showTime = false }: { iso: string; showTime?: boolean }) {
  return <DateText iso={iso} showTime={showTime} />;
}
