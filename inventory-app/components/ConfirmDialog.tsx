"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

/**
 * Review R7 / rule D2: "ask before doing" in the app's own pop-up, instead of the browser's confirm() box.
 *
 *   const ask = useConfirm();
 *   if (await ask({ title: "Ship SO-12?", message: "12 units leave Main warehouse.", action: "Ship" })) ship.mutate();
 *
 * danger: red button and Cancel focused first (delete, cancel, void, disconnect...).
 * typeToConfirm: the user must type this text first (the worst ones).
 * Escape, a click outside or Cancel means no.
 *
 * A reason, in the same pop-up instead of the browser's prompt() (R7):
 *   const askReason = useAskReason();
 *   const reason = await askReason({ title: "Void this payment?", reasonLabel: "Why is it voided?", action: "Void", danger: true });
 *   if (reason) voidIt.mutate(reason);
 *
 * Every save asks too (owner's choice a, 2026-11-13): any <form> sent is held, "Save as draft?" (the button's words) is
 * asked, and the form is sent again once confirmed. A form opts out with data-confirm="none" (search, filters, sign-in,
 * scanning) or "own" (it asks its own question, e.g. "Receive goods on PO-12?"). data-confirm-title / -message change
 * the question. A save button outside a form asks the same way with data-confirm-save (its value, or its words, name
 * the action).
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
  // Asks for a reason in the pop-up; the action button waits until one is typed
  reasonLabel?: string;
}

type Ask = (o: ConfirmOptions) => Promise<boolean>;
type AskReason = (o: ConfirmOptions & { reasonLabel: string }) => Promise<string | null>;
const ConfirmContext = createContext<{ ask: Ask; askReason: AskReason } | null>(null);

export function useConfirm(): Ask {
  const c = useContext(ConfirmContext);
  if (!c) throw new Error("useConfirm needs <ConfirmProvider>");
  return c.ask;
}

export function useAskReason(): AskReason {
  const c = useContext(ConfirmContext);
  if (!c) throw new Error("useAskReason needs <ConfirmProvider>");
  return c.askReason;
}

type Answer = boolean | string;

export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState<(ConfirmOptions & { resolve: (a: Answer) => void }) | null>(null);
  const ask = useCallback<Ask>(o => new Promise<boolean>(resolve => setOpen({ ...o, reasonLabel: undefined, resolve: a => resolve(a !== false) })), []);
  const askReason = useCallback<AskReason>(o => new Promise<string | null>(resolve =>
    setOpen({ ...o, resolve: a => resolve(typeof a === "string" ? a : null) })), []);
  const value = useMemo(() => ({ ask, askReason }), [ask, askReason]);
  const close = (a: Answer) => { open?.resolve(a); setOpen(null); };

  // Every save asks first: forms are held at the document, before React sees them, and sent again once confirmed
  useEffect(() => {
    const onSubmit = (e: SubmitEvent) => {
      const form = e.target as HTMLFormElement;
      const mode = form.dataset.confirm;
      if (mode === "none" || mode === "own") return;
      if (form.dataset.confirmed === "1") { delete form.dataset.confirmed; return; }
      e.preventDefault();
      e.stopPropagation();
      const submitter = e.submitter as HTMLButtonElement | HTMLInputElement | null;
      const words = (submitter?.dataset.confirmAction || submitter?.textContent || (submitter as HTMLInputElement | null)?.value || "Save").trim() || "Save";
      ask({
        title: form.dataset.confirmTitle ?? (/^save$/i.test(words) ? "Save the changes?" : `${words}?`),
        message: form.dataset.confirmMessage ?? "Check the details, then confirm to save them.",
        action: words,
      }).then(ok => {
        if (!ok || !form.isConnected) return;
        form.dataset.confirmed = "1";
        form.requestSubmit(submitter && submitter.form === form ? submitter : undefined);
      });
    };
    // A save button outside a form (e.g. "Save units") asks the same way when it carries data-confirm-save
    const onClick = (e: MouseEvent) => {
      const button = (e.target as HTMLElement | null)?.closest?.("button[data-confirm-save]") as HTMLButtonElement | null;
      if (!button || button.disabled) return;
      if (button.dataset.confirmed === "1") { delete button.dataset.confirmed; return; }
      e.preventDefault();
      e.stopPropagation();
      // A bare data-confirm-save is written as "true" by React: then the button's own words
      const named = button.dataset.confirmSave && button.dataset.confirmSave !== "true" ? button.dataset.confirmSave : "";
      const words = (named || button.textContent || "Save").trim() || "Save";
      ask({
        title: button.dataset.confirmTitle ?? (/^save$/i.test(words) ? "Save the changes?" : `${words}?`),
        message: button.dataset.confirmMessage ?? "Check the details, then confirm to save them.",
        action: words,
      }).then(ok => {
        if (!ok || !button.isConnected) return;
        button.dataset.confirmed = "1";
        button.click();
      });
    };
    document.addEventListener("submit", onSubmit, true);
    document.addEventListener("click", onClick, true);
    return () => { document.removeEventListener("submit", onSubmit, true); document.removeEventListener("click", onClick, true); };
  }, [ask]);

  return (
    <ConfirmContext.Provider value={value}>
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

function Dialog({ options, onClose }: { options: ConfirmOptions; onClose: (a: Answer) => void }) {
  const { details, action = "Confirm", danger, typeToConfirm, reasonLabel } = options;
  const [title, message] = splitTitle(options.title, options.message);
  const [typed, setTyped] = useState("");
  const [reason, setReason] = useState("");
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const answer = (): Answer => (reasonLabel ? reason.trim() : true);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const okRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const blocked = (!!typeToConfirm && typed.trim() !== typeToConfirm) || (!!reasonLabel && !reason.trim());

  useEffect(() => {
    // Dangerous: start on Cancel, so Enter does not delete by accident
    (reasonLabel ? reasonRef : typeToConfirm ? inputRef : danger ? cancelRef : okRef).current?.focus();
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
                onKeyDown={e => { if (e.key === "Enter" && !blocked) onClose(answer()); }}
                className="mt-1 w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-red-300" />
            </label>
          )}
          {reasonLabel && (
            <label className="block text-sm text-gray-700 pt-1">
              {reasonLabel} <span className="text-red-500">*</span>
              <textarea ref={reasonRef} aria-label={reasonLabel} rows={2} value={reason} maxLength={500} onChange={e => setReason(e.target.value)}
                className="mt-1 w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-300" />
            </label>
          )}
        </div>
        <div className="flex justify-end gap-2 px-6 py-3 bg-gray-50 border-t border-gray-100 rounded-b-xl">
          <button ref={cancelRef} type="button" data-testid="confirm-cancel" onClick={() => onClose(false)}
            className="px-4 py-2 rounded-md text-sm font-medium text-gray-700 border border-gray-300 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-300">
            Cancel
          </button>
          <button ref={okRef} type="button" data-testid="confirm-ok" disabled={blocked} onClick={() => onClose(answer())}
            className={`px-4 py-2 rounded-md text-sm font-medium text-white disabled:opacity-40 focus:outline-none focus:ring-2 focus:ring-offset-1 ${
              danger ? "bg-red-600 hover:bg-red-700 focus:ring-red-400" : "bg-brand-600 hover:bg-brand-700 focus:ring-brand-400"}`}>
            {action}
          </button>
        </div>
      </div>
    </div>
  );
}
