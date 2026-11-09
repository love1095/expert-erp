"use client";

import { createContext, useCallback, useContext, useState } from "react";

/**
 * Review R8: a short message in the corner after an action ("Saved ✓"), that slides in and goes away after 3 s.
 *   const toast = useToast();  toast("Customer saved");
 */
type Toast = { id: number; text: string; kind: "ok" | "error" };
const ToastContext = createContext<((text: string, kind?: "ok" | "error") => void) | null>(null);

export function useToast() {
  const show = useContext(ToastContext);
  return show ?? (() => {});
}

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const show = useCallback((text: string, kind: "ok" | "error" = "ok") => {
    const id = Date.now() + Math.random();
    setToasts(t => [...t.slice(-2), { id, text, kind }]);
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 3000);
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div aria-live="polite" className="fixed bottom-20 right-6 z-[110] flex flex-col items-end gap-2 pointer-events-none">
        {toasts.map(t => (
          <div key={t.id} role="status" data-testid="toast"
            className={`erp-toast-in pointer-events-auto rounded-lg shadow-lg px-4 py-2.5 text-sm font-medium ${
              t.kind === "ok" ? "bg-gray-900 text-white" : "bg-red-600 text-white"}`}>
            {t.kind === "ok" ? "✓ " : "✕ "}{t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
