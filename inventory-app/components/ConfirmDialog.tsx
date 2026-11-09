"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

/**
 * Review R7 / rule D2: "ask before doing" in the app's own pop-up, instead of the browser's confirm() box.
 *
 *   const ask = useConfirm();
 *   if (await ask({ title: "Ship SO-12?", message: "12 units leave Main warehouse.", action: "Ship" })) ship.mutate();
 *
 * danger: red button and Cancel focused first (delete, cancel, void, disconnect...).
 * typeToConfirm: the user must type this text first (the worst ones).
 * Escape, a click outside or Cancel means no.
 */
export interface ConfirmOptions {
  title: string;
  message?: React.ReactNode;
  // The figures that change, e.g. "3 lines · 12 units · £1,240.00"
  details?: React.ReactNode;
  // The button's word, e.g. "Ship", "Delete" (default "Confirm")
  action?: string;
  danger?: boolean;
  typeToConfirm?: string;
}

type Ask = (o: ConfirmOptions) => Promise<boolean>;
const ConfirmContext = createContext<Ask | null>(null);

export function useConfirm(): Ask {
  const ask = useContext(ConfirmContext);
  if (!ask) throw new Error("useConfirm needs <ConfirmProvider>");
  return ask;
}

export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null);
  const ask = useCallback<Ask>(o => new Promise<boolean>(resolve => setOpen({ ...o, resolve })), []);
  const close = (ok: boolean) => { open?.resolve(ok); setOpen(null); };
  return (
    <ConfirmContext.Provider value={ask}>
      {children}
      {open && <Dialog options={open} onClose={close} />}
    </ConfirmContext.Provider>
  );
}

// "Cancel SO-12? Quantities not shipped are released." -> title "Cancel SO-12?", the rest under it
function splitTitle(title: string, message?: React.ReactNode): [string, React.ReactNode] {
  const i = title.indexOf("? ");
  if (i < 0 || i === title.length - 2) return [title, message];
  const rest = title.slice(i + 2);
  return [title.slice(0, i + 1), message ? <>{rest} {message}</> : rest];
}

function Dialog({ options, onClose }: { options: ConfirmOptions; onClose: (ok: boolean) => void }) {
  const { details, action = "Confirm", danger, typeToConfirm } = options;
  const [title, message] = splitTitle(options.title, options.message);
  const [typed, setTyped] = useState("");
  const cancelRef = useRef<HTMLButtonElement>(null);
  const okRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const blocked = !!typeToConfirm && typed.trim() !== typeToConfirm;

  useEffect(() => {
    // Dangerous: start on Cancel, so Enter does not delete by accident
    (typeToConfirm ? inputRef : danger ? cancelRef : okRef).current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.preventDefault(); onClose(false); } };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/40 erp-fade-in"
      onMouseDown={e => { if (e.target === e.currentTarget) onClose(false); }}>
      <div role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-message" data-testid="confirm-dialog"
        className="w-full max-w-md bg-white rounded-xl shadow-xl erp-pop-in">
        <div className="px-6 pt-5 pb-4 space-y-2">
          <h2 id="confirm-title" className="text-base font-semibold text-gray-900">{title}</h2>
          {message && <div id="confirm-message" className="text-sm text-gray-600">{message}</div>}
          {details && <div data-testid="confirm-details" className="text-sm bg-gray-50 border border-gray-200 rounded-md px-3 py-2 text-gray-800">{details}</div>}
          {typeToConfirm && (
            <label className="block text-sm text-gray-600 pt-1">
              Type <b className="font-mono text-gray-900">{typeToConfirm}</b> to confirm
              <input ref={inputRef} aria-label="Type to confirm" value={typed} onChange={e => setTyped(e.target.value)}
                onKeyDown={e => { if (e.key === "Enter" && !blocked) onClose(true); }}
                className="mt-1 w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-red-300" />
            </label>
          )}
        </div>
        <div className="flex justify-end gap-2 px-6 py-3 bg-gray-50 border-t border-gray-100 rounded-b-xl">
          <button ref={cancelRef} type="button" data-testid="confirm-cancel" onClick={() => onClose(false)}
            className="px-4 py-2 rounded-md text-sm font-medium text-gray-700 border border-gray-300 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-300">
            Cancel
          </button>
          <button ref={okRef} type="button" data-testid="confirm-ok" disabled={blocked} onClick={() => onClose(true)}
            className={`px-4 py-2 rounded-md text-sm font-medium text-white disabled:opacity-40 focus:outline-none focus:ring-2 focus:ring-offset-1 ${
              danger ? "bg-red-600 hover:bg-red-700 focus:ring-red-400" : "bg-brand-600 hover:bg-brand-700 focus:ring-brand-400"}`}>
            {action}
          </button>
        </div>
      </div>
    </div>
  );
}
