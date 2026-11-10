import Link from "next/link";

/**
 * One button for the whole app (review R8). Four kinds, so a page shows what matters:
 *   primary   — the one main action of a page or form (Save, Confirm, + New)
 *   secondary — every other action (Print, Export, Cancel)
 *   danger    — deletes, voids, cancels a document (always with a confirmation pop-up)
 *   ghost     — quiet actions inside a part ("+ Add line", "Clear")
 * `busy` shows the small spinner and stops a second click while the server works.
 */
export type ButtonKind = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "sm" | "md" | "lg";

const BASE = "inline-flex items-center justify-center gap-1.5 rounded-md font-medium whitespace-nowrap select-none "
  + "disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-1";

const KIND: Record<ButtonKind, string> = {
  primary: "bg-brand-600 text-white hover:bg-brand-700 focus-visible:ring-brand-400",
  secondary: "border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 focus-visible:ring-gray-300",
  danger: "border border-red-200 bg-white text-red-600 hover:bg-red-50 focus-visible:ring-red-300",
  ghost: "text-brand-700 hover:text-brand-900 hover:bg-gray-100 focus-visible:ring-gray-300",
};

// lg = the phone scanner and other touch screens (a finger needs 44 px)
const SIZE: Record<ButtonSize, string> = {
  sm: "px-2.5 py-1 text-xs",
  md: "px-3.5 py-2 text-sm",
  lg: "px-5 py-3 text-base",
};

/** The same look for something that is not a <button> (a file label, a plain <a>). */
export function buttonClass(kind: ButtonKind = "secondary", size: ButtonSize = "md", full = false) {
  return `${BASE} ${KIND[kind]} ${SIZE[size]}${full ? " w-full" : ""}`;
}

type Common = { kind?: ButtonKind; size?: ButtonSize; full?: boolean; icon?: React.ReactNode };

export function Button({ kind = "secondary", size = "md", full, icon, busy, className = "", children, type = "button", disabled, ...rest }:
  Common & { busy?: boolean } & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type={type} disabled={disabled || busy} aria-busy={busy || undefined}
      className={`${buttonClass(kind, size, full)} ${className}`} {...rest}>
      {icon}{children}
    </button>
  );
}

/** A link that looks like a button, e.g. "+ New order" going to its own page. */
export function LinkButton({ kind = "secondary", size = "md", full, icon, className = "", children, href, ...rest }:
  Common & { href: string } & Omit<React.AnchorHTMLAttributes<HTMLAnchorElement>, "href">) {
  return (
    <Link href={href} className={`${buttonClass(kind, size, full)} ${className}`} {...rest}>
      {icon}{children}
    </Link>
  );
}
