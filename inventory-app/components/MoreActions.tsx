"use client";

import { useEffect, useRef, useState } from "react";

export type MoreAction = {
  label: string;
  onClick: () => void;
  // Shown greyed out with this reason (e.g. "Tick one product first")
  disabledReason?: string;
  danger?: boolean;
  // A line above this item
  divider?: boolean;
};

// Cin7's "More actions" dropdown at the top right of a list or a record
export default function MoreActions({ actions, label = "More actions" }: { actions: MoreAction[]; label?: string }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  return (
    <div ref={box} className="relative">
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} aria-haspopup="menu"
        className="flex items-center gap-1.5 border border-gray-300 bg-white text-gray-700 px-3 py-2 rounded-md text-sm font-medium hover:bg-gray-50">
        {label}
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true" className={`w-3.5 h-3.5 transition-transform ${open ? "rotate-180" : ""}`}>
          <path d="M5 8l5 5 5-5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {open && (
        <div role="menu" className="absolute right-0 mt-1 w-60 bg-white border border-gray-200 rounded-md shadow-lg py-1 z-40 text-sm">
          {actions.map(a => (
            <div key={a.label}>
              {a.divider && <div className="my-1 border-t border-gray-100" />}
              <button type="button" role="menuitem" disabled={a.disabledReason != null} title={a.disabledReason}
                onClick={() => { setOpen(false); a.onClick(); }}
                className={`block w-full text-left px-3 py-2 disabled:text-gray-300 disabled:cursor-not-allowed ${
                  a.danger ? "text-red-600 hover:bg-red-50" : "text-gray-700 hover:bg-gray-50"}`}>
                {a.label}
                {a.disabledReason && <span className="block text-[11px] text-gray-400">{a.disabledReason}</span>}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
