"use client";

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";

/**
 * A pop-up with a small form in it, opened on top of the page (like Cin7's "New customer" from the "+" next to a
 * field). Escape, the cross or Cancel closes it; a click outside does not, so typed details are not lost by accident.
 * It is drawn outside the page's own form, and its Save never submits that form.
 */
export function Modal({ title, hint, onClose, children, footer, width = "max-w-lg", testId }: {
  title: string;
  hint?: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;      // the buttons, right-aligned
  width?: string;                // Tailwind max-width, e.g. "max-w-2xl"
  testId?: string;
}) {
  const id = useId();
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    // Start in the first field
    box.current?.querySelector<HTMLElement>("input:not([disabled]), select:not([disabled]), textarea:not([disabled])")?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); onClose(); return; }
      if (e.key !== "Tab" || !box.current) return;
      // Tab stays inside the pop-up
      const items = box.current.querySelectorAll<HTMLElement>("button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]");
      if (items.length === 0) return;
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", onKey, true);
    return () => { document.removeEventListener("keydown", onKey, true); before?.focus?.(); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (typeof document === "undefined") return null;
  return createPortal(
    // React passes events from a pop-up up to the form it was opened from: stop them here
    <div className="fixed inset-0 z-[90] flex items-start sm:items-center justify-center p-3 sm:p-4 bg-black/40 overflow-y-auto erp-fade-in"
      onSubmit={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()} onMouseDown={e => e.stopPropagation()}>
      <div ref={box} role="dialog" aria-modal="true" aria-labelledby={id} data-testid={testId}
        className={`w-full ${width} bg-white rounded-xl shadow-xl erp-pop-in my-auto`}>
        <div className="flex items-start justify-between gap-3 px-5 pt-4 pb-3 border-b border-gray-100">
          <div className="min-w-0">
            <h2 id={id} className="text-base font-semibold text-gray-900">{title}</h2>
            {hint && <p className="text-xs text-gray-500 mt-0.5">{hint}</p>}
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="p-1 -mr-1 rounded text-gray-400 hover:text-gray-700 hover:bg-gray-100">
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.8} className="w-4 h-4" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15" strokeLinecap="round" /></svg>
          </button>
        </div>
        <div className="px-5 py-4">{children}</div>
        {footer && <div className="flex flex-wrap justify-end gap-2 px-5 py-3 bg-gray-50 border-t border-gray-100 rounded-b-xl">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}
