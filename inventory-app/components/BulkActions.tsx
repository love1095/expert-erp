"use client";

import { useState } from "react";

// Cin7's list actions: tick rows, then Undo / Fill and authorise / Print / Email / Export / Void on all of them.
export type BulkAction<T> = {
  label: string;
  // Asked before anything is done
  confirm?: string;
  // Run for each ticked row, one after another; a row that fails is reported and the others carry on
  each?: (row: T) => Promise<unknown>;
  // Or one go with all the ticked rows (e.g. export)
  all?: (rows: T[]) => void;
  // Not available yet: shown greyed out with this reason
  unavailable?: string;
  danger?: boolean;
};

type Result = { label: string; done: number; failed: { name: string; error: string }[] };

// Ticked rows of a list page, by id; only rows on the current page count
export function useSelection<T extends { id: number }>(rows: T[]) {
  const [ids, setIds] = useState<Set<number>>(new Set());
  const selected = rows.filter(r => ids.has(r.id));
  const allTicked = rows.length > 0 && selected.length === rows.length;
  return {
    selected,
    isTicked: (id: number) => ids.has(id),
    toggle: (id: number) => setIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    }),
    allTicked,
    toggleAll: () => setIds(allTicked ? new Set() : new Set(rows.map(r => r.id))),
    clear: () => setIds(new Set()),
  };
}

// Header / row tick boxes; clicks don't open the row
export function TickBox({ checked, onChange, label, testId }: { checked: boolean; onChange: () => void; label: string; testId?: string }) {
  return (
    <input type="checkbox" checked={checked} onChange={onChange} onClick={e => e.stopPropagation()}
      aria-label={label} data-testid={testId} className="h-4 w-4 rounded border-gray-300 text-brand-600 cursor-pointer" />
  );
}

export function BulkActionBar<T>({ selected, name, actions, onClear, onDone }: {
  selected: T[];
  name: (row: T) => string;     // "SO-65975" in messages
  actions: BulkAction<T>[];
  onClear: () => void;
  onDone: () => void;           // refresh the list
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);

  const run = async (a: BulkAction<T>) => {
    if (a.confirm && !confirm(a.confirm)) return;
    setResult(null);
    if (a.all) { a.all(selected); return; }
    if (!a.each) return;
    setBusy(a.label);
    const r: Result = { label: a.label, done: 0, failed: [] };
    for (const row of selected) {
      try {
        await a.each(row);
        r.done++;
      } catch (e) {
        const err = e as { response?: { data?: { error?: string } }; message?: string };
        r.failed.push({ name: name(row), error: err.response?.data?.error || err.message || "Failed" });
      }
    }
    setBusy(null);
    setResult(r);
    onClear();
    onDone();
  };

  if (selected.length === 0 && !result) return null;
  return (
    <div className="mb-3 space-y-2">
      {selected.length > 0 && (
        <div data-testid="bulk-actions" className="flex flex-wrap items-center gap-2 bg-brand-50 border border-brand-200 rounded-lg px-3 py-2">
          <span className="text-sm font-medium text-gray-800 mr-2">{selected.length} selected</span>
          {actions.map(a => (
            <button key={a.label} type="button" disabled={busy != null || a.unavailable != null}
              title={a.unavailable} onClick={() => run(a)}
              className={`text-sm px-3 py-1.5 rounded-md border bg-white disabled:opacity-40 disabled:cursor-not-allowed ${
                a.danger ? "border-red-200 text-red-600 hover:bg-red-50" : "border-gray-300 text-gray-700 hover:bg-gray-50"}`}>
              {busy === a.label ? `${a.label}…` : a.label}
            </button>
          ))}
          <button type="button" onClick={onClear} disabled={busy != null} className="ml-auto text-xs text-gray-500 hover:text-gray-800">
            Clear selection
          </button>
        </div>
      )}
      {result && (
        <div role="status" data-testid="bulk-result"
          className={`flex items-start justify-between gap-4 text-sm px-3 py-2 rounded-lg ${result.failed.length ? "bg-amber-50 text-amber-900" : "bg-emerald-50 text-emerald-800"}`}>
          <div>
            <p>{result.label}: {result.done} done{result.failed.length > 0 && `, ${result.failed.length} not done`}</p>
            {result.failed.length > 0 && (
              <ul className="mt-1 text-xs list-disc pl-4">
                {result.failed.map(f => <li key={f.name}><span className="font-mono">{f.name}</span>: {f.error}</li>)}
              </ul>
            )}
          </div>
          <button type="button" onClick={() => setResult(null)} aria-label="Close" className="text-current opacity-60 hover:opacity-100">×</button>
        </div>
      )}
    </div>
  );
}

// The ticked rows as a CSV file (Cin7's Export)
export function downloadCsv(filename: string, header: string[], rows: (string | number | null | undefined)[][]) {
  const cell = (v: string | number | null | undefined) => {
    const s = v == null ? "" : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const text = [header, ...rows].map(r => r.map(cell).join(",")).join("\r\n");
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

// Cin7's warnings
export const UNDO_WARNING = "This will change the task status back to draft. Would you like to proceed anyway?";
export const NOT_YET_PRINT = "Comes with the PDF documents (D5)";
export const NOT_YET_EMAIL = "Comes with e-mail sending";
