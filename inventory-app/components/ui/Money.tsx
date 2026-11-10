import { formatGBP } from "@/lib/currency";

/**
 * An amount of money, the same everywhere (review R8): £1,240.00, digits lined up in columns, a minus sign and red
 * for negative amounts, a grey dash when there is none.
 *   <Money value={total} />            in a table cell with className="num"
 *   <Money value={due} tone="due" />   red when something is owed (above 0)
 *   <Money value={0} zero="dash" />    "—" instead of £0.00, for quiet columns
 */
export function Money({ value, tone = "auto", zero = "show", strong, className = "" }: {
  value: number | null | undefined;
  tone?: "auto" | "plain" | "due" | "good";   // auto: red only when negative
  zero?: "show" | "dash";
  strong?: boolean;                            // totals
  className?: string;
}) {
  if (value == null || (zero === "dash" && Number(value) === 0)) {
    return <span className={`text-gray-300 ${className}`}>—</span>;
  }
  const n = Number(value);
  const colour =
    tone === "due" ? (n > 0 ? "text-red-600" : "")
    : tone === "good" ? (n > 0 ? "text-emerald-700" : "")
    : tone === "auto" ? (n < 0 ? "text-red-600" : "")
    : "";
  return (
    <span className={`tabular-nums whitespace-nowrap ${strong ? "font-semibold" : ""} ${colour} ${className}`}>
      {formatGBP(n)}
    </span>
  );
}
