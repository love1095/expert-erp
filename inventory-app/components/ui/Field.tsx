import { useId } from "react";

/**
 * Form fields that look the same on every form (review R8): the label above, the box, then a hint or the error.
 *   <FormField label="Customer reference" hint="Their PO number" error={errors.ref}>
 *     {p => <Input {...p} value={ref} onChange={e => setRef(e.target.value)} />}
 *   </FormField>
 * The label, hint and error are tied to the box for screen readers; a required field shows a red star.
 */
export const LABEL_CLASS = "block text-xs font-medium text-gray-600 mb-1";

const BOX = "w-full border rounded-md bg-white text-sm text-gray-900 placeholder:text-gray-400 "
  + "focus:outline-none focus:ring-2 disabled:bg-gray-50 disabled:text-gray-500";
const BOX_OK = "border-gray-300 focus:ring-brand-400 focus:border-brand-400";
const BOX_BAD = "border-red-400 focus:ring-red-300";
const PAD = { sm: "px-2 py-1", md: "px-3 py-2" } as const;

/** The same box look for a field that is drawn by hand. `sm` = inside table rows (order lines). */
export function inputClass(size: "sm" | "md" = "md", invalid = false) {
  return `${BOX} ${invalid ? BOX_BAD : BOX_OK} ${PAD[size]}`;
}

type FieldProps = { id: string; "aria-describedby"?: string; "aria-invalid"?: boolean; required?: boolean };

export function FormField({ label, hint, error, required, className = "", children }: {
  label: string;
  hint?: React.ReactNode;
  error?: string | null;
  required?: boolean;
  className?: string;                                  // width in a grid, e.g. "col-span-2"
  children: (props: FieldProps) => React.ReactNode;     // spread the props onto the Input / Select / Textarea
}) {
  const id = useId();
  const noteId = `${id}-note`;
  const note = error || hint;
  return (
    <div className={className}>
      <label htmlFor={id} className={LABEL_CLASS}>
        {label}{required && <span className="text-red-500" aria-hidden="true"> *</span>}
      </label>
      {children({ id, "aria-describedby": note ? noteId : undefined, "aria-invalid": error ? true : undefined, required })}
      {note && (
        <p id={noteId} role={error ? "alert" : undefined} className={`text-xs mt-1 ${error ? "text-red-600" : "text-gray-500"}`}>
          {note}
        </p>
      )}
    </div>
  );
}

type Sized = { fieldSize?: "sm" | "md" };

export function Input({ fieldSize = "md", className = "", ...rest }: Sized & React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`${inputClass(fieldSize, !!rest["aria-invalid"])} ${className}`} {...rest} />;
}

export function Select({ fieldSize = "md", className = "", children, ...rest }: Sized & React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={`${inputClass(fieldSize, !!rest["aria-invalid"])} ${className}`} {...rest}>{children}</select>;
}

export function Textarea({ fieldSize = "md", className = "", rows = 3, ...rest }: Sized & React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={rows} className={`${inputClass(fieldSize, !!rest["aria-invalid"])} ${className}`} {...rest} />;
}

/** A tick box with its label on the right; the whole line can be clicked. */
export function Checkbox({ label, hint, className = "", ...rest }: { label: React.ReactNode; hint?: React.ReactNode } & Omit<React.InputHTMLAttributes<HTMLInputElement>, "type">) {
  return (
    <label className={`flex items-start gap-2 text-sm text-gray-800 cursor-pointer ${className}`}>
      <input type="checkbox" className="mt-0.5 h-4 w-4 rounded border-gray-300 accent-brand-600" {...rest} />
      <span>{label}{hint && <span className="block text-xs text-gray-500">{hint}</span>}</span>
    </label>
  );
}
