"use client";

import { useEffect } from "react";

/**
 * Manage dashboard, like Cin7's: which parts the dashboard shows, their order, folded or not, half or full width.
 * Saved per user on the server (GET/PUT/DELETE /api/dashboard/layout); an admin can make theirs the organisation default.
 * A part needs its permission (an admin sees all), so a role is never offered what it may not see.
 */
export interface PartDef { key: string; title: string; perm?: string; wide?: boolean }
export interface PartState { key: string; visible: boolean; collapsed: boolean; wide: boolean }
export interface LayoutResult { layout: { parts: PartState[] } | null; source: "USER" | "ORGANISATION" | "STANDARD"; organisationDefault: boolean }

export const PARTS: PartDef[] = [
  { key: "kpis", title: "Key performance indicators", wide: true },
  { key: "sales", title: "Sales — waiting now", perm: "SALE_VIEW" },
  { key: "purchases", title: "Purchases — waiting now", perm: "PURCHASE_VIEW" },
  { key: "revenue", title: "Revenue per day", perm: "SALE_VIEW" },
  { key: "stock-in-out", title: "Units in and out per day", perm: "PRODUCT_VIEW" },
  { key: "sales-by-rep", title: "Sales by rep", perm: "SALE_VIEW", wide: true },
  { key: "sellers", title: "Top and worst sellers", perm: "SALE_VIEW" },
  { key: "reorder", title: "Reorder", perm: "PURCHASE_VIEW" },
  { key: "recent-orders", title: "Recent sale orders", perm: "SALE_VIEW" },
];

export const standardLayout = (): PartState[] =>
  PARTS.map(p => ({ key: p.key, visible: true, collapsed: false, wide: !!p.wide }));

/** The saved layout in its order, parts added to the app since then at the end, unknown keys dropped. */
export function mergeLayout(saved: PartState[] | undefined | null): PartState[] {
  const std = standardLayout();
  if (!saved?.length) return std;
  const known = new Set(std.map(p => p.key));
  const kept = saved.filter(p => known.has(p.key));
  const have = new Set(kept.map(p => p.key));
  return [...kept, ...std.filter(p => !have.has(p.key))];
}

const ICON = "p-1 rounded text-gray-400 hover:text-gray-700 hover:bg-gray-100";

/** The small icons on each part's header, like Cin7's: half/full width, fold, hide. */
export function PartControls({ title, state, onChange }: { title: string; state: PartState; onChange: (s: PartState) => void }) {
  return (
    <div className="flex items-center gap-0.5">
      <button type="button" className={`${ICON} hidden lg:inline-flex`} aria-label={`${state.wide ? "Half width" : "Full width"}: ${title}`}
        title={state.wide ? "Half width" : "Full width"} onClick={() => onChange({ ...state, wide: !state.wide })}>
        <svg viewBox="0 0 20 20" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden><path d="M3 10h14M6 7l-3 3 3 3M14 7l3 3-3 3" /></svg>
      </button>
      <button type="button" className={ICON} aria-label={`${state.collapsed ? "Unfold" : "Fold"}: ${title}`} aria-expanded={!state.collapsed}
        title={state.collapsed ? "Unfold" : "Fold"} onClick={() => onChange({ ...state, collapsed: !state.collapsed })}>
        <svg viewBox="0 0 20 20" className={`w-4 h-4 transition-transform ${state.collapsed ? "rotate-180" : ""}`} fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden><path d="M5 12l5-5 5 5" /></svg>
      </button>
      <button type="button" className={ICON} aria-label={`Hide: ${title}`} title="Hide (Manage dashboard brings it back)"
        onClick={() => onChange({ ...state, visible: false })}>
        <svg viewBox="0 0 20 20" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden><path d="M6 6l8 8M14 6l-8 8" /></svg>
      </button>
    </div>
  );
}

/** The Manage dashboard pop-up: a tick per part, up/down to order them, collapse/expand all, reset, and the admin default. */
export function ManageDashboard({ parts, offered, onChange, onReset, onMakeDefault, isAdmin, source, onClose }: {
  parts: PartState[]; offered: PartDef[]; onChange: (p: PartState[]) => void; onReset: () => void;
  onMakeDefault: () => void; isAdmin: boolean; source: LayoutResult["source"]; onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  const titleOf = (key: string) => offered.find(o => o.key === key)?.title ?? key;
  const shown = parts.filter(p => offered.some(o => o.key === p.key));
  const move = (key: string, by: -1 | 1) => {
    const i = parts.findIndex(p => p.key === key);
    // Step over parts this role cannot see, so a click always moves it past a visible neighbour
    let j = i + by;
    while (j >= 0 && j < parts.length && !offered.some(o => o.key === parts[j].key)) j += by;
    if (j < 0 || j >= parts.length) return;
    const next = [...parts];
    [next[i], next[j]] = [next[j], next[i]];
    onChange(next);
  };
  const link = "text-sm font-medium text-brand-700 hover:text-brand-900 hover:underline";

  return (
    <div className="fixed inset-0 z-[90] flex items-center justify-center p-4 bg-black/40 erp-fade-in"
      onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div role="dialog" aria-modal="true" aria-labelledby="manage-dashboard-title" data-testid="manage-dashboard"
        className="w-full max-w-md bg-white rounded-xl shadow-xl erp-pop-in">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 id="manage-dashboard-title" className="text-base font-semibold text-gray-900">Manage dashboard</h2>
          <button type="button" aria-label="Close" onClick={onClose} className="text-gray-400 hover:text-gray-700 text-lg leading-none">×</button>
        </div>
        <div className="px-6 py-4 space-y-4 max-h-[70vh] overflow-y-auto">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Dashboard actions</p>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              <button type="button" className={link} onClick={() => onChange(parts.map(p => ({ ...p, collapsed: true })))}>Collapse all</button>
              <button type="button" className={link} onClick={() => onChange(parts.map(p => ({ ...p, collapsed: false })))}>Expand all</button>
              <button type="button" className={link} onClick={onReset}>Reset layout</button>
            </div>
            <p className="text-xs text-gray-400 mt-1.5">
              {source === "USER" ? "Your own layout." : source === "ORGANISATION" ? "The organisation's layout: changes here become your own." : "The standard layout."}
            </p>
          </div>
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Parts to show, in this order</p>
            <ul className="divide-y divide-gray-100 border border-gray-200 rounded-lg">
              {shown.map((p, i) => (
                <li key={p.key} className="flex items-center gap-2 px-3 py-2" data-testid={`manage-part-${p.key}`}>
                  <label className="flex items-center gap-2 flex-1 min-w-0 text-sm text-gray-800 cursor-pointer">
                    <input type="checkbox" checked={p.visible}
                      onChange={e => onChange(parts.map(x => (x.key === p.key ? { ...x, visible: e.target.checked } : x)))}
                      className="h-4 w-4 rounded border-gray-300" />
                    <span className="truncate">{titleOf(p.key)}</span>
                  </label>
                  <button type="button" aria-label={`Move up: ${titleOf(p.key)}`} disabled={i === 0} onClick={() => move(p.key, -1)}
                    className="p-1 rounded text-gray-500 hover:bg-gray-100 disabled:opacity-30">↑</button>
                  <button type="button" aria-label={`Move down: ${titleOf(p.key)}`} disabled={i === shown.length - 1} onClick={() => move(p.key, 1)}
                    className="p-1 rounded text-gray-500 hover:bg-gray-100 disabled:opacity-30">↓</button>
                </li>
              ))}
            </ul>
          </div>
          {isAdmin && (
            <div className="pt-1">
              <button type="button" className={link} onClick={onMakeDefault}>Make this the default for everyone</button>
              <p className="text-xs text-gray-400 mt-0.5">For users who have not chosen their own layout.</p>
            </div>
          )}
        </div>
        <div className="flex justify-end px-6 py-3 bg-gray-50 border-t border-gray-100 rounded-b-xl">
          <button type="button" onClick={onClose}
            className="px-4 py-2 rounded-md text-sm font-medium text-white bg-brand-600 hover:bg-brand-700">Done</button>
        </div>
      </div>
    </div>
  );
}
