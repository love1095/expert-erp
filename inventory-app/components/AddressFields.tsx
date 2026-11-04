"use client";

import { PostalAddress } from "@/types";

// Phase G4: an address in Cin7's fields, and how it reads on a page

export const ADDRESS_TYPES = [
  { value: "BILLING", label: "Billing" },
  { value: "BUSINESS", label: "Business" },
  { value: "SHIPPING", label: "Shipping" },
] as const;

const t = (s?: string | null) => (s ?? "").trim();

export function addressLines(a?: PostalAddress | null): string[] {
  if (!a) return [];
  const town = [t(a.city), t(a.state), t(a.postcode)].filter(Boolean).join(" ");
  return [t(a.line1), t(a.line2), town, t(a.country)].filter(Boolean);
}

export const oneLine = (a?: PostalAddress | null) => addressLines(a).join(", ");
export const isBlankAddress = (a?: PostalAddress | null) => addressLines(a).length === 0;
export const sameAddress = (a?: PostalAddress | null, b?: PostalAddress | null) => oneLine(a) === oneLine(b);
export const EMPTY_ADDRESS: PostalAddress = { line1: "", line2: "", city: "", state: "", postcode: "", country: "" };

export function AddressText({ address, empty = "—" }: { address?: PostalAddress | null; empty?: string }) {
  const lines = addressLines(address);
  if (lines.length === 0) return <span className="text-gray-400">{empty}</span>;
  return <span className="whitespace-pre-line">{lines.join("\n")}</span>;
}

// The six inputs; `label` names them for screen readers and tests, e.g. "Shipping address line 1"
export function AddressFields({ value, onChange, label, inputClass }: {
  value: PostalAddress;
  onChange: (a: PostalAddress) => void;
  label: string;
  inputClass: string;
}) {
  const field = (key: keyof PostalAddress, placeholder: string, span: string) => (
    <input aria-label={`${label} ${placeholder.toLowerCase()}`} placeholder={placeholder} value={value[key] ?? ""}
      onChange={e => onChange({ ...value, [key]: e.target.value })} autoComplete="off" className={`${inputClass} ${span}`} />
  );
  return (
    <div className="grid grid-cols-6 gap-2">
      {field("line1", "Line 1", "col-span-6")}
      {field("line2", "Line 2", "col-span-6")}
      {field("city", "City", "col-span-2")}
      {field("state", "State", "col-span-2")}
      {field("postcode", "Postcode", "col-span-2")}
      {field("country", "Country", "col-span-6")}
    </div>
  );
}
