"use client";

import { useRef } from "react";

/**
 * The tabs on top of a list or page (review R8): All · Draft · Confirmed …, each with how many there are, so the
 * numbers are seen without opening a filter. Left / right arrows move between tabs; on a phone the row scrolls sideways.
 *   <StatusTabs label="Order status" value={status} onChange={setStatus}
 *     tabs={[{ key: "", label: "All", count: 209 }, { key: "DRAFT", label: "Draft", count: 4 }]} />
 */
export type StatusTab<K extends string = string> = {
  key: K;
  label: string;
  count?: number | null;     // left out or null = no number shown
  tone?: "plain" | "warn";   // warn = red number when above 0 (overdue, failed)
};

export function StatusTabs<K extends string>({ tabs, value, onChange, label, className = "" }: {
  tabs: StatusTab<K>[];
  value: K;
  onChange: (key: K) => void;
  label: string;             // read out by screen readers, e.g. "Order status"
  className?: string;
}) {
  const row = useRef<HTMLDivElement>(null);

  const onKeyDown = (e: React.KeyboardEvent) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    const at = tabs.findIndex(t => t.key === value);
    const to = e.key === "Home" ? 0 : e.key === "End" ? tabs.length - 1 : step ? (at + step + tabs.length) % tabs.length : -1;
    if (to < 0) return;
    e.preventDefault();
    onChange(tabs[to].key);
    row.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[to]?.focus();
  };

  return (
    <div ref={row} role="tablist" aria-label={label} onKeyDown={onKeyDown}
      className={`flex gap-1 border-b border-gray-200 mb-4 overflow-x-auto ${className}`}>
      {tabs.map(t => {
        const on = t.key === value;
        const warn = t.tone === "warn" && !!t.count;
        return (
          <button key={t.key} type="button" role="tab" aria-selected={on} tabIndex={on ? 0 : -1}
            data-testid={`tab-${t.key || "all"}`} onClick={() => onChange(t.key)}
            className={`-mb-px shrink-0 flex items-center gap-1.5 px-3 pb-2 pt-1.5 text-sm font-medium border-b-2 ${
              on ? "border-brand-600 text-gray-900" : "border-transparent text-gray-500 hover:text-gray-800 hover:border-gray-300"}`}>
            {t.label}
            {t.count != null && (
              <span className={`min-w-5 px-1.5 py-0.5 rounded-full text-[11px] leading-none font-semibold tabular-nums text-center ${
                warn ? "bg-red-100 text-red-700" : on ? "bg-brand-600 text-white" : "bg-gray-100 text-gray-600"}`}>
                {t.count.toLocaleString("en-GB")}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
