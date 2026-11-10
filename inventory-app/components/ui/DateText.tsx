"use client";

import { useSyncExternalStore } from "react";
import { formatDate, formatDateTime, isDateOnly } from "@/lib/dates";

const noSubscribe = () => () => {};

/**
 * A date in the UK format on every computer (review R8): 09/11/2026, with showTime 09/11/2026 14:05.
 * A plain date shows at once; a timestamp is shown in the person's own time zone, worked out in the browser.
 */
export function DateText({ iso, showTime = false, empty = "—", className }: {
  iso?: string | null;
  showTime?: boolean;
  empty?: string;        // shown when there is no date
  className?: string;
}) {
  // A timestamp depends on the computer's time zone, so it is filled in once the page runs in the browser
  const inBrowser = useSyncExternalStore(noSubscribe, () => true, () => false);
  const label = !iso ? ""
    : isDateOnly(iso) ? formatDate(iso)
    : !inBrowser ? ""
    : showTime ? formatDateTime(iso) : formatDate(iso);

  if (!iso) return <span className={className ?? "text-gray-300"}>{empty}</span>;
  return <time dateTime={iso} className={className}>{label || empty}</time>;
}
