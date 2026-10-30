// Dashboard periods, the same presets as Cin7 Core. "This …" periods cover the whole month / quarter /
// year, days still to come included; quarters are calendar quarters.

// Expert Safety's financial year starts on 1 December (Cin7: This financial year = 01/12/2025 – 30/11/2026)
export const FINANCIAL_YEAR_START_MONTH = 12;

export type PeriodKey =
  | "today" | "yesterday" | "last7" | "last30" | "last90"
  | "thisMonth" | "lastMonth" | "thisQuarter" | "lastQuarter"
  | "thisFinancialYear" | "lastFinancialYear" | "custom";

export const PERIOD_LABELS: Record<PeriodKey, string> = {
  today: "Today",
  yesterday: "Yesterday",
  last7: "Last 7 days",
  last30: "Last 30 days",
  last90: "Last 90 days",
  thisMonth: "This month",
  lastMonth: "Last month",
  thisQuarter: "This quarter",
  lastQuarter: "Last quarter",
  thisFinancialYear: "This financial year",
  lastFinancialYear: "Last financial year",
  custom: "Custom",
};

export interface Period { from: string; to: string }

/** Local date → "2026-10-30" (no timezone shift, unlike toISOString). */
export function isoDate(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const day = (y: number, m: number, d: number) => new Date(y, m, d);   // month 0-based; overflow rolls over

export function periodFor(key: Exclude<PeriodKey, "custom">, today = new Date()): Period {
  const y = today.getFullYear(), m = today.getMonth(), d = today.getDate();
  const range = (from: Date, to: Date) => ({ from: isoDate(from), to: isoDate(to) });
  const q = Math.floor(m / 3) * 3;                                  // first month of this quarter
  const fyStart = FINANCIAL_YEAR_START_MONTH - 1;
  const fyYear = m >= fyStart ? y : y - 1;                          // year this financial year started
  switch (key) {
    case "today":             return range(today, today);
    case "yesterday":         return range(day(y, m, d - 1), day(y, m, d - 1));
    case "last7":             return range(day(y, m, d - 6), today);
    case "last30":            return range(day(y, m, d - 29), today);
    case "last90":            return range(day(y, m, d - 89), today);
    case "thisMonth":         return range(day(y, m, 1), day(y, m + 1, 0));
    case "lastMonth":         return range(day(y, m - 1, 1), day(y, m, 0));
    case "thisQuarter":       return range(day(y, q, 1), day(y, q + 3, 0));
    case "lastQuarter":       return range(day(y, q - 3, 1), day(y, q, 0));
    case "thisFinancialYear": return range(day(fyYear, fyStart, 1), day(fyYear + 1, fyStart, 0));
    case "lastFinancialYear": return range(day(fyYear - 1, fyStart, 1), day(fyYear, fyStart, 0));
  }
}

/** "2026-10-01" → "01/10/2026" */
export function ukDate(iso: string) {
  const [y, m, d] = iso.split("-");
  return `${d}/${m}/${y}`;
}
