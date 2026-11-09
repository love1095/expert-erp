"use client";

import { useEffect, useRef, useState } from "react";
import { getProductPicker } from "@/lib/api";
import { PickerPage, PickerProduct, Product } from "@/types";
import { formatGBP } from "@/lib/currency";

const qty = (n: number) => Number(n).toLocaleString();

/**
 * Review R9: the product selection list on order lines, like Cin7's. A click opens the list (A-Z by SKU, the chosen
 * supplier's products first); typing narrows it by SKU, name or barcode; scrolling loads 20 more, so all products can be
 * reached without loading them all. Keyboard: up/down to move, Enter or Tab to pick, Esc to close.
 * mode "sale": available stock and selling price; "purchase": on hand, on order and cost.
 */
export default function ProductPicker({ label, selected, onSelect, mode = "sale", supplierId, required = true }: {
  label: string;
  selected?: Pick<Product, "id" | "sku" | "name">;
  onSelect: (p: PickerProduct) => void;
  mode?: "sale" | "purchase";
  supplierId?: number;
  required?: boolean;
}) {
  const show = (p?: Pick<Product, "sku" | "name">) => (p ? `${p.sku} — ${p.name}` : "");
  const [chosen, setChosen] = useState(show(selected));
  const [text, setText] = useState(show(selected));
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<PickerProduct[]>([]);
  const [more, setMore] = useState(false);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [active, setActive] = useState(-1);
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const listId = `${label.replace(/\W+/g, "-").toLowerCase()}-list`;

  // While the box still shows the chosen product, the list is not narrowed by it
  const filter = text === chosen ? "" : text.trim();

  // First page whenever the list opens or the filter changes
  useEffect(() => {
    if (!open) return;
    let stale = false;
    const t = setTimeout(() => {
      setLoading(true);
      getProductPicker({ q: filter || undefined, supplierId, page: 0 })
        .then((r: PickerPage) => { if (!stale) { setRows(r.rows); setMore(r.more); setPage(0); setActive(r.rows.length ? 0 : -1); list.current?.scrollTo(0, 0); } })
        .catch(() => { if (!stale) { setRows([]); setMore(false); } })
        .finally(() => { if (!stale) setLoading(false); });
    }, filter ? 200 : 0);
    return () => { stale = true; clearTimeout(t); };
  }, [open, filter, supplierId]);

  const loadMore = () => {
    if (!more || loading) return;
    setLoading(true);
    getProductPicker({ q: filter || undefined, supplierId, page: page + 1 })
      .then((r: PickerPage) => { setRows(prev => [...prev, ...r.rows]); setMore(r.more); setPage(page + 1); })
      .finally(() => setLoading(false));
  };

  // Close when clicking elsewhere; the box goes back to the chosen product
  useEffect(() => {
    const close = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) { setOpen(false); setText(t => (t === chosen ? t : chosen)); }
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [chosen]);

  const pick = (p: PickerProduct) => {
    const label = show(p);
    setChosen(label); setText(label); setOpen(false);
    onSelect(p);
  };

  const move = (to: number) => {
    const i = Math.max(0, Math.min(rows.length - 1, to));
    setActive(i);
    list.current?.querySelectorAll("li")[i]?.scrollIntoView({ block: "nearest" });
    if (i >= rows.length - 3) loadMore();
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); if (!open) setOpen(true); else move(active + 1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); if (open) move(active - 1); }
    else if (e.key === "Enter") { if (open) { e.preventDefault(); if (rows[active]) pick(rows[active]); } }
    else if (e.key === "Tab") { if (open && filter && rows[active]) pick(rows[active]); else setOpen(false); }
    else if (e.key === "Escape") { if (open) { e.preventDefault(); setOpen(false); setText(chosen); } }
  };

  return (
    <div ref={box} className="relative">
      <input ref={input} role="combobox" aria-label={label} aria-expanded={open} aria-controls={listId} aria-autocomplete="list"
        aria-activedescendant={open && active >= 0 ? `${listId}-${active}` : undefined}
        value={text} required={required} autoComplete="off" placeholder="Select a product"
        onChange={e => { setText(e.target.value); setOpen(true); }}
        onFocus={e => { setOpen(true); e.currentTarget.select(); }} onClick={() => setOpen(true)} onKeyDown={onKey}
        className="w-full border border-gray-300 rounded-md pl-2 pr-12 py-1.5 text-sm bg-white cursor-pointer focus:cursor-text focus:outline-none focus:ring-1 focus:ring-brand-400" />
      {chosen && text === chosen && !required && (
        <button type="button" aria-label="Clear the product" tabIndex={-1}
          onClick={() => { setChosen(""); setText(""); input.current?.focus(); }}
          className="absolute right-6 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700 text-sm px-1">×</button>
      )}
      <button type="button" aria-label="Show the product list" tabIndex={-1}
        onMouseDown={e => e.preventDefault()} onClick={() => { setOpen(o => !o); input.current?.focus(); }}
        className="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700 px-1">
        <svg viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4" aria-hidden><path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.17l3.71-3.94a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" /></svg>
      </button>
      {open && (
        <div className="absolute z-30 mt-1 w-[40rem] max-w-[90vw] bg-white border border-gray-200 rounded-md shadow-lg text-sm erp-pop-in">
          <div className="flex items-center gap-3 px-3 py-1.5 border-b border-gray-100 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
            <span className="flex-1">Product</span>
            {mode === "sale" ? <>
              <span className="w-20 text-right shrink-0">Available</span><span className="w-20 text-right shrink-0">Price</span>
            </> : <>
              <span className="w-16 text-right shrink-0">On hand</span><span className="w-16 text-right shrink-0">On order</span><span className="w-20 text-right shrink-0">Cost</span>
            </>}
          </div>
          <ul ref={list} id={listId} role="listbox" aria-label={`${label} matches`}
            onScroll={e => { const el = e.currentTarget; if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40) loadMore(); }}
            className="max-h-72 overflow-y-auto">
            {rows.map((p, i) => (
              <li key={p.id} id={`${listId}-${i}`} role="option" aria-selected={i === active}>
                <button type="button" tabIndex={-1} onMouseDown={e => e.preventDefault()} onClick={() => pick(p)} onMouseEnter={() => setActive(i)}
                  className={`w-full text-left px-3 py-1 flex items-center gap-3 ${i === active ? "bg-brand-50" : ""}`}>
                  {/* Name first, the SKU small under it */}
                  <span className="flex-1 min-w-0">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-gray-800">{p.name}</span>
                      {p.supplierProduct && <span className="shrink-0 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-700">Supplier&apos;s</span>}
                    </span>
                    <span data-testid="picker-sku" className="block font-mono text-[11px] text-gray-400 truncate">{p.sku}</span>
                  </span>
                  {mode === "sale" ? <>
                    <span data-testid="picker-available" className={`w-20 text-right text-xs tabular-nums shrink-0 ${Number(p.available) > 0 ? "text-emerald-700" : "text-red-600"}`}>{qty(p.available)}</span>
                    <span className="w-20 text-right text-xs tabular-nums text-gray-600 shrink-0">{p.sellPrice != null ? formatGBP(p.sellPrice) : "—"}</span>
                  </> : <>
                    <span data-testid="picker-on-hand" className="w-16 text-right text-xs tabular-nums text-gray-600 shrink-0">{qty(p.onHand)}</span>
                    <span data-testid="picker-on-order" className="w-16 text-right text-xs tabular-nums text-gray-600 shrink-0">{Number(p.onOrder) > 0 ? qty(p.onOrder) : "—"}</span>
                    <span className="w-20 text-right text-xs tabular-nums text-gray-600 shrink-0">{p.costPrice != null ? formatGBP(p.costPrice) : "—"}</span>
                  </>}
                </button>
              </li>
            ))}
          </ul>
          {(loading || rows.length === 0) && (
            <p className="px-3 py-2 text-xs text-gray-400">{loading ? "Loading…" : filter ? `No active product matches "${filter}"` : "No active products"}</p>
          )}
        </div>
      )}
    </div>
  );
}
