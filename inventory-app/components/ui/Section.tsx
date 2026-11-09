import { useId } from "react";

/**
 * A titled part of a form or page, like Cin7's "Customer details" / "Accounting details" boxes (review R8): a white box,
 * a bold title with a thin line under it, and the part's own buttons ("+ Add line") on the right of the title.
 * Every form uses it, so the parts are easy to tell apart and look the same everywhere.
 */
export function Section({ title, hint, actions, children, testId, className = "", flush }: {
  title: string;
  hint?: React.ReactNode;        // one short line under the title
  actions?: React.ReactNode;     // buttons on the right of the title
  children: React.ReactNode;
  testId?: string;
  className?: string;
  flush?: boolean;               // no inner padding (tables)
}) {
  const id = useId();
  return (
    <section aria-labelledby={id} data-testid={testId} className={`bg-white border border-gray-200 rounded-xl shadow-sm ${className}`}>
      <header className="flex items-center justify-between gap-3 px-5 py-3 border-b border-gray-100">
        <div className="min-w-0">
          <h2 id={id} className="text-[15px] font-semibold text-gray-900">{title}</h2>
          {hint && <p className="text-xs text-gray-500 mt-0.5">{hint}</p>}
        </div>
        {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
      </header>
      <div className={flush ? "" : "p-5"}>{children}</div>
    </section>
  );
}

/** A small bold heading inside a section, e.g. "Billing address" under Customer details. */
export function SubHeading({ children, right }: { children: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-2 mt-4 mb-2 first:mt-0">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">{children}</h3>
      {right}
    </div>
  );
}

/**
 * The form's Save bar, fixed to the bottom of the screen while the form is open (review R8), with the total on the left.
 */
export function StickyActions({ children, summary }: { children: React.ReactNode; summary?: React.ReactNode }) {
  return (
    <div className="sticky -bottom-6 z-20 -mx-6 px-6 pt-3 pb-9 -mb-6 bg-white/95 backdrop-blur border-t border-gray-200 flex flex-wrap items-center gap-3">
      <div className="flex-1 min-w-0 text-sm text-gray-600">{summary}</div>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

// Buttons for forms: one main action, quiet cancel
export const ADD_BUTTON = "text-sm font-medium text-brand-700 hover:text-brand-900 px-2 py-1 rounded-md hover:bg-gray-100";
