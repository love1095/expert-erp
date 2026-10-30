"use client";

import { useEffect, useState } from "react";

// List page title with a count and the page's main actions (e.g. "+ New SO") on the right
export function PageHeader({ title, count, noun, subtitle, children }: {
  title: string;
  count?: number;
  noun?: string;            // "orders" → "209 orders"
  subtitle?: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4 mb-5">
      <div className="min-w-0">
        <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
        {(subtitle || count != null) && (
          <p className="text-sm text-gray-500 mt-0.5">
            {subtitle ?? `${count!.toLocaleString("en-GB")} ${noun ?? ""}`.trim()}
          </p>
        )}
      </div>
      {children && <div className="flex items-center gap-2 shrink-0">{children}</div>}
    </div>
  );
}

// Cin7-style toolbar on top of a list: search on the left; filters, refresh and extra actions on the right.
// The search applies as you type (after a short pause) or at once on Enter.
export function ListToolbar({ search, onSearch, placeholder = "Search…", searchLabel = "Search", filters, onRefresh, refreshing, actions }: {
  search?: string;                  // the search that is applied
  onSearch?: (q: string) => void;   // set → shows the search box
  placeholder?: string;
  searchLabel?: string;
  filters?: React.ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  actions?: React.ReactNode;
}) {
  const [text, setText] = useState(search ?? "");
  // A search set from outside (e.g. a link to /invoices?q=INV-1) shows in the box
  useEffect(() => { setText(t => (t.trim() === (search ?? "") ? t : search ?? "")); }, [search]);
  useEffect(() => {
    if (!onSearch || text.trim() === (search ?? "")) return;
    const t = setTimeout(() => onSearch(text.trim()), 300);
    return () => clearTimeout(t);
  }, [text]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-wrap items-center gap-2 mb-3">
      {onSearch && (
        <form role="search" className="relative" onSubmit={e => { e.preventDefault(); onSearch(text.trim()); }}>
          <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true"
            className="w-4 h-4 text-gray-400 absolute left-2.5 top-1/2 -translate-y-1/2">
            <circle cx="9" cy="9" r="5.5" /><path d="M13 13l4 4" strokeLinecap="round" />
          </svg>
          <input type="search" value={text} onChange={e => setText(e.target.value)} placeholder={placeholder}
            aria-label={searchLabel}
            className="w-80 border border-gray-300 rounded-md pl-8 pr-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand-400" />
        </form>
      )}
      <div className="ml-auto flex flex-wrap items-center gap-2">
        {filters}
        {onRefresh && (
          <button type="button" onClick={onRefresh} title="Refresh"
            className="flex items-center gap-1.5 px-3 py-2 text-sm border border-gray-300 rounded-md bg-white hover:bg-gray-50">
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true"
              className={`w-4 h-4 ${refreshing ? "animate-spin" : ""}`}>
              <path d="M16 10a6 6 0 11-1.76-4.24M16 4v3.5h-3.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            Refresh
          </button>
        )}
        {actions}
      </div>
    </div>
  );
}

// Shared look for the selects in a toolbar
export const FILTER_SELECT = "border border-gray-300 rounded-md px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand-400";

// Column headings of a list-table; `right` = number columns (right-aligned like Cin7); `lead` = e.g. the select-all box
export function ListHead({ cols, right = [], lead }: { cols: string[]; right?: string[]; lead?: React.ReactNode }) {
  return (
    <thead className="bg-gray-50 border-b border-gray-200">
      <tr>
        {lead}
        {cols.map((h, i) => (
          <th key={h || `col-${i}`} className={`${right.includes(h) ? "text-right" : "text-left"} text-xs font-semibold text-gray-500 uppercase tracking-wide`}>{h}</th>
        ))}
      </tr>
    </thead>
  );
}

// Long text in a list cell: one line, cut with "…", the full text on hover
export function Clip({ text, width = "18rem", className = "" }: { text?: string | null; width?: string; className?: string }) {
  if (!text) return <span className="text-gray-300">—</span>;
  return <span title={text} style={{ maxWidth: width }} className={`block truncate ${className}`}>{text}</span>;
}
