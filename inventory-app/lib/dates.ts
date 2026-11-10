// UK dates everywhere (review R8): 09/11/2026, never the browser's own language (a US-English browser showed 11/9/2026).

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;
const two = (n: number) => String(n).padStart(2, "0");

/** "2026-11-09" or a timestamp → "09/11/2026". A plain date is never shifted by the time zone. */
export function formatDate(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  if (typeof iso === "string") {
    const m = DATE_ONLY.exec(iso);
    if (m) return `${m[3]}/${m[2]}/${m[1]}`;
  }
  const d = iso instanceof Date ? iso : new Date(iso);
  if (isNaN(d.getTime())) return "—";
  return `${two(d.getDate())}/${two(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** A timestamp → "09/11/2026 14:05" (24-hour clock, the time of the person's computer). */
export function formatDateTime(iso: string | Date | null | undefined): string {
  if (!iso) return "—";
  const d = iso instanceof Date ? iso : new Date(iso);
  if (isNaN(d.getTime())) return "—";
  return `${formatDate(d)} ${two(d.getHours())}:${two(d.getMinutes())}`;
}

/** True for a plain date ("2026-11-09"), which reads the same in every time zone. */
export const isDateOnly = (iso: string) => DATE_ONLY.test(iso);

/** A date as the date boxes hold it ("2026-11-09"), in the computer's own time zone. */
export const isoDay = (d: Date) => `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;

/** Today for a date box. New documents start on it; toISOString() would give yesterday just after midnight in summer time. */
export const todayIso = () => isoDay(new Date());

/** Today plus a number of days, for a date box (e.g. a quote valid for 30 days). */
export const daysFromToday = (days: number) => { const d = new Date(); d.setDate(d.getDate() + days); return isoDay(d); };
