"use client";

import { useState } from "react";

interface Props {
  page: number;              // 0-based
  totalPages: number;
  totalElements?: number;
  size?: number;             // rows per page; with onSizeChange shows "Show: 20"
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
}

const SIZES = [10, 20, 50, 100];

// Page numbers around the current one, e.g. 1 … 4 5 6 … 21
function pageList(page: number, total: number): (number | "…")[] {
  const out: (number | "…")[] = [];
  for (let i = 0; i < total; i++) {
    if (i === 0 || i === total - 1 || Math.abs(i - page) <= 1) out.push(i);
    else if (out[out.length - 1] !== "…") out.push("…");
  }
  return out;
}

// Cin7-style list footer: pages · "Displaying 1 - 20 of 209" · rows per page · go to page
export default function Pagination({ page, totalPages, totalElements, size, onPageChange, onSizeChange }: Props) {
  const [goTo, setGoTo] = useState("");
  if (totalPages <= 1 && totalElements == null) return null;

  const btn = "min-w-8 h-8 px-2 text-sm rounded-md border border-transparent disabled:opacity-40";
  const from = totalElements && size ? page * size + 1 : null;
  const to = totalElements && size ? Math.min((page + 1) * size, totalElements) : null;

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 mt-3 text-sm text-gray-600">
      {totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center gap-1">
          <button type="button" onClick={() => onPageChange(page - 1)} disabled={page === 0}
            aria-label="Previous page" className={`${btn} hover:bg-gray-100`}>‹</button>
          {pageList(page, totalPages).map((p, i) => p === "…"
            ? <span key={`gap-${i}`} className="px-1 text-gray-400">…</span>
            : (
              <button key={p} type="button" onClick={() => onPageChange(p)} aria-current={p === page ? "page" : undefined}
                className={`${btn} ${p === page ? "bg-brand-600 text-white font-medium" : "hover:bg-gray-100"}`}>
                {p + 1}
              </button>
            ))}
          <button type="button" onClick={() => onPageChange(page + 1)} disabled={page >= totalPages - 1}
            aria-label="Next page" className={`${btn} hover:bg-gray-100`}>›</button>
        </nav>
      )}

      {totalElements != null && (
        <span className="text-gray-500">
          {totalElements === 0 ? "Nothing to display"
            : from != null ? `Displaying ${from} - ${to} of ${totalElements.toLocaleString("en-GB")}`
            : `${totalElements.toLocaleString("en-GB")} total · page ${page + 1} of ${Math.max(totalPages, 1)}`}
        </span>
      )}

      <div className="ml-auto flex items-center gap-3">
        {onSizeChange && size != null && (
          <label className="flex items-center gap-1.5 text-gray-500">
            Show
            <select aria-label="Rows per page" value={size} onChange={e => onSizeChange(Number(e.target.value))}
              className="border border-gray-300 rounded-md px-2 py-1 text-sm bg-white">
              {SIZES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
        )}
        {totalPages > 3 && (
          <form className="flex items-center gap-1.5 text-gray-500"
            onSubmit={e => {
              e.preventDefault();
              const n = parseInt(goTo, 10);
              if (n >= 1 && n <= totalPages) { onPageChange(n - 1); setGoTo(""); }
            }}>
            <label htmlFor="go-to-page">Go to page</label>
            <input id="go-to-page" value={goTo} onChange={e => setGoTo(e.target.value.replace(/\D/g, ""))}
              inputMode="numeric" className="w-14 border border-gray-300 rounded-md px-2 py-1 text-sm" />
            <button type="submit" className="px-2 py-1 text-sm font-medium text-brand-700 hover:underline">Go</button>
          </form>
        )}
      </div>
    </div>
  );
}
