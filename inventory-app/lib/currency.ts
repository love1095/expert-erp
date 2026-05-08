export const CURRENCY = {
  code: "GBP",
  symbol: "£",
  locale: "en-GB",
} as const;

export function formatGBP(amount: number | null | undefined): string {
  if (amount == null) return "—";
  return new Intl.NumberFormat(CURRENCY.locale, {
    style: "currency",
    currency: CURRENCY.code,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}
