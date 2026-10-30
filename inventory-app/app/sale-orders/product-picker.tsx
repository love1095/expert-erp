"use client";

import { useEffect, useRef, useState } from "react";
import { getProductsPage } from "@/lib/api";
import { Page, Product } from "@/types";
import { formatGBP } from "@/lib/currency";

/**
 * Product search for order lines: type part of a SKU or name, pick from the matches (active products, with
 * available stock and price). Replaces a dropdown of every product, which is unusable with ~14,000 of them.
 */
export default function ProductPicker({ label, selected, onSelect }: {
  label: string;
  selected?: Pick<Product, "id" | "sku" | "name">;
  onSelect: (p: Product) => void;
}) {
  const [text, setText] = useState(selected ? `${selected.sku} — ${selected.name}` : "");
  const [matches, setMatches] = useState<Product[]>([]);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const q = text.trim();
    if (!open || q.length < 2) { setMatches([]); return; }
    const t = setTimeout(() => {
      getProductsPage({ q, status: "ACTIVE", size: 15 }).then((p: Page<Product>) => setMatches(p.content)).catch(() => setMatches([]));
    }, 250);
    return () => clearTimeout(t);
  }, [text, open]);

  // Close the list when clicking elsewhere
  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  return (
    <div ref={box} className="relative">
      <input aria-label={label} value={text} required autoComplete="off" placeholder="Search SKU or name"
        onChange={e => { setText(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)}
        className="w-full border border-gray-300 rounded-md px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-brand-400" />
      {open && matches.length > 0 && (
        <ul role="listbox" aria-label={`${label} matches`}
          className="absolute z-20 mt-1 w-[36rem] max-w-[90vw] max-h-72 overflow-y-auto bg-white border border-gray-200 rounded-md shadow-lg text-sm">
          {matches.map(p => (
            <li key={p.id} role="option" aria-selected={selected?.id === p.id}>
              <button type="button" onClick={() => { onSelect(p); setText(`${p.sku} — ${p.name}`); setOpen(false); }}
                className="w-full text-left px-3 py-1.5 hover:bg-brand-50 flex items-center gap-3">
                <span className="font-mono text-xs text-gray-500 w-36 shrink-0 truncate">{p.sku}</span>
                <span className="flex-1 truncate text-gray-800">{p.name}</span>
                <span className={`text-xs tabular-nums shrink-0 ${Number(p.availableStock) > 0 ? "text-emerald-700" : "text-red-600"}`}>
                  {Number(p.availableStock).toLocaleString()} available
                </span>
                <span className="text-xs tabular-nums text-gray-500 w-16 text-right shrink-0">{p.sellPrice != null ? formatGBP(p.sellPrice) : ""}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
