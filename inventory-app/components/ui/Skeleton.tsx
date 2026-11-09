/** Grey placeholder rows while a list loads (review R8), so the page does not jump when the data arrives. */
export function SkeletonRows({ rows = 8, cols = 6, label = "Loading" }: { rows?: number; cols?: number; label?: string }) {
  return (
    <div role="status" aria-label={label} className="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <span className="sr-only">{label}…</span>
      <div className="flex gap-4 px-4 py-3 bg-gray-50 border-b border-gray-200">
        {Array.from({ length: cols }, (_, c) => <div key={c} className="erp-skeleton h-3 flex-1" />)}
      </div>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex gap-4 px-4 py-3 border-b border-gray-100 last:border-0">
          {Array.from({ length: cols }, (_, c) => (
            <div key={c} className="erp-skeleton h-3 flex-1" style={{ maxWidth: c === 0 ? "7rem" : undefined, opacity: 1 - r * 0.07 }} />
          ))}
        </div>
      ))}
    </div>
  );
}
