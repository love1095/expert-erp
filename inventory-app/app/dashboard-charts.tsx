"use client";

import { useState } from "react";

// Validated categorical slots (light surface): blue = in, orange = out
export const SERIES_IN = "#2a78d6";
export const SERIES_OUT = "#eb6834";

/** "2026-10-29" → "29 Oct" without a timezone shift */
export function dayLabel(iso: string) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

/** Rounds the max up to a tidy axis value (1, 2, 2.5, 5 × 10^n). */
function niceMax(v: number) {
  if (v <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / exp;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * exp;
}

const compact = new Intl.NumberFormat("en-GB", { notation: "compact", maximumFractionDigits: 1 });

function XLabels({ dates }: { dates: string[] }) {
  if (dates.length === 0) return null;
  const mid = Math.floor((dates.length - 1) / 2);
  return (
    <div className="flex justify-between text-[11px] text-gray-400 mt-1.5 pl-10">
      <span>{dayLabel(dates[0])}</span>
      {dates.length > 2 && <span>{dayLabel(dates[mid])}</span>}
      <span>{dayLabel(dates[dates.length - 1])}</span>
    </div>
  );
}

function Tooltip({ leftPct, children }: { leftPct: number; children: React.ReactNode }) {
  // Keep the tooltip inside the plot near the edges
  const align = leftPct < 15 ? "translate-x-0" : leftPct > 85 ? "-translate-x-full" : "-translate-x-1/2";
  return (
    <div role="tooltip" style={{ left: `${leftPct}%` }}
      className={`pointer-events-none absolute top-1 ${align} z-10 whitespace-nowrap rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-700 shadow-md`}>
      {children}
    </div>
  );
}

/** One series per day, bars from the baseline. */
export function DailyBars({ data, format, label }: {
  data: { date: string; value: number }[]; format: (v: number) => string; label: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const max = niceMax(Math.max(0, ...data.map(d => d.value)));

  return (
    <div>
      <div className="relative h-44 flex">
        <div className="w-10 flex flex-col justify-between text-[11px] text-gray-400 tabular-nums text-right pr-2 -my-1.5">
          <span>{compact.format(max)}</span><span>{compact.format(max / 2)}</span><span>0</span>
        </div>
        <div className="relative flex-1" onMouseLeave={() => setHover(null)}>
          {[0, 50, 100].map(p => <div key={p} className="absolute inset-x-0 border-t border-gray-100" style={{ top: `${p}%` }} />)}
          <div className="absolute inset-0 flex items-end gap-[2px]" role="img" aria-label={label}>
            {data.map((d, i) => (
              <div key={d.date} className="relative flex-1 h-full flex items-end" onMouseEnter={() => setHover(i)}>
                <div className={`w-full rounded-t-[4px] ${hover === i ? "opacity-80" : ""}`}
                  style={{ height: `${(d.value / max) * 100}%`, background: SERIES_IN, minHeight: d.value > 0 ? 2 : 0 }} />
              </div>
            ))}
          </div>
          {hover != null && (
            <Tooltip leftPct={((hover + 0.5) / data.length) * 100}>
              <div className="text-gray-500">{dayLabel(data[hover].date)}</div>
              <div className="font-semibold tabular-nums">{format(data[hover].value)}</div>
            </Tooltip>
          )}
        </div>
      </div>
      <XLabels dates={data.map(d => d.date)} />
    </div>
  );
}

/** Units in above the baseline, units out below it, on one shared scale. */
export function InOutBars({ data }: { data: { date: string; unitsIn: number; unitsOut: number }[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const max = niceMax(Math.max(0, ...data.map(d => Math.max(d.unitsIn, d.unitsOut))));

  return (
    <div>
      <div className="flex items-center gap-4 text-xs text-gray-600 mb-2">
        <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: SERIES_IN }} />Units in (above)</span>
        <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: SERIES_OUT }} />Units out (below)</span>
      </div>
      <div className="relative h-44 flex">
        <div className="w-10 flex flex-col justify-between text-[11px] text-gray-400 tabular-nums text-right pr-2 -my-1.5">
          <span>{compact.format(max)}</span><span>0</span><span>{compact.format(max)}</span>
        </div>
        <div className="relative flex-1" onMouseLeave={() => setHover(null)}>
          {[0, 100].map(p => <div key={p} className="absolute inset-x-0 border-t border-gray-100" style={{ top: `${p}%` }} />)}
          <div className="absolute inset-x-0 border-t border-gray-300" style={{ top: "50%" }} />
          <div className="absolute inset-0 flex gap-[2px]" role="img" aria-label="Units in and out per day">
            {data.map((d, i) => (
              <div key={d.date} className={`flex-1 h-full flex flex-col ${hover === i ? "opacity-80" : ""}`} onMouseEnter={() => setHover(i)}>
                <div className="h-1/2 flex items-end pb-px">
                  <div className="w-full rounded-t-[4px]" style={{ height: `${(d.unitsIn / max) * 100}%`, background: SERIES_IN }} />
                </div>
                <div className="h-1/2 flex items-start pt-px">
                  <div className="w-full rounded-b-[4px]" style={{ height: `${(d.unitsOut / max) * 100}%`, background: SERIES_OUT }} />
                </div>
              </div>
            ))}
          </div>
          {hover != null && (
            <Tooltip leftPct={((hover + 0.5) / data.length) * 100}>
              <div className="text-gray-500 mb-0.5">{dayLabel(data[hover].date)}</div>
              <div className="flex items-center gap-1.5 tabular-nums">
                <span className="w-2 h-2 rounded-sm" style={{ background: SERIES_IN }} />In <b className="ml-auto pl-3">{data[hover].unitsIn.toLocaleString()}</b>
              </div>
              <div className="flex items-center gap-1.5 tabular-nums">
                <span className="w-2 h-2 rounded-sm" style={{ background: SERIES_OUT }} />Out <b className="ml-auto pl-3">{data[hover].unitsOut.toLocaleString()}</b>
              </div>
            </Tooltip>
          )}
        </div>
      </div>
      <XLabels dates={data.map(d => d.date)} />
    </div>
  );
}

/** Horizontal bars for a short ranked list (single series). */
export function RankedBars({ items }: { items: { key: string | number; label: string; sub?: string; value: number }[] }) {
  const max = Math.max(1, ...items.map(i => i.value));
  return (
    <ul className="space-y-2.5">
      {items.map(i => (
        <li key={i.key}>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-gray-700 truncate pr-2">{i.label}{i.sub && <span className="text-gray-400"> · {i.sub}</span>}</span>
            <span className="tabular-nums font-semibold text-gray-700">{i.value.toLocaleString()}</span>
          </div>
          <div className="h-2 rounded-full bg-gray-100">
            <div className="h-2 rounded-full" style={{ width: `${(i.value / max) * 100}%`, background: SERIES_IN }} />
          </div>
        </li>
      ))}
    </ul>
  );
}
