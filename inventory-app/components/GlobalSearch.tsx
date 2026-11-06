"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { searchEverything } from "@/lib/api";
import { SearchHit, SearchKind } from "@/types";

const KIND: Record<SearchKind, string> = {
  SALE_ORDER: "Sale orders", INVOICE: "Invoices and credit notes", BILL: "Bills", PURCHASE_ORDER: "Purchase orders",
  CUSTOMER: "Customers", SUPPLIER: "Suppliers", PRODUCT: "Products",
};

/** Where a hit opens (suppliers have no page of their own yet: their list, searched). */
export function hrefOf(h: SearchHit) {
  switch (h.kind) {
    case "SALE_ORDER": return `/sale-orders/${h.id}`;
    case "INVOICE": case "BILL": return `/invoices/${h.id}`;
    case "PURCHASE_ORDER": return `/purchase-orders/${h.id}`;
    case "CUSTOMER": return `/customers/${h.id}`;
    case "PRODUCT": return `/products/${h.id}`;
    case "SUPPLIER": return `/suppliers?q=${encodeURIComponent(h.title)}`;
  }
}

/**
 * Phase I2: one box in the top bar for orders, invoices, bills, customers, suppliers and products (by number, name,
 * reference, SKU or barcode). "/" puts the cursor in it; arrows move, Enter opens, Esc closes.
 */
export default function GlobalSearch() {
  const router = useRouter();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);

  // Wait for a pause in typing before searching
  useEffect(() => {
    const t = setTimeout(() => { setQ(text.trim()); setActive(0); }, 250);
    return () => clearTimeout(t);
  }, [text]);
  const { data: hits = [], isFetching } = useQuery<SearchHit[]>({
    queryKey: ["search", q], queryFn: () => searchEverything(q), enabled: q.length >= 2, staleTime: 10_000,
  });

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      const typing = el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable;
      if (e.key === "/" && !typing) { e.preventDefault(); input.current?.focus(); setOpen(true); }
    };
    const click = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("keydown", key);
    document.addEventListener("mousedown", click);
    return () => { document.removeEventListener("keydown", key); document.removeEventListener("mousedown", click); };
  }, []);

  const go = (h: SearchHit) => { setOpen(false); setText(""); router.push(hrefOf(h)); };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setActive(a => Math.min(a + 1, hits.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive(a => Math.max(a - 1, 0)); }
    else if (e.key === "Enter" && hits[active]) { e.preventDefault(); go(hits[active]); }
    else if (e.key === "Escape") { setOpen(false); input.current?.blur(); }
  };

  const showing = open && q.length >= 2 && q === text.trim();
  return (
    <div ref={box} className="relative w-full max-w-md" data-testid="global-search">
      <input ref={input} type="search" role="combobox" aria-expanded={showing} aria-controls="global-search-results"
        aria-label="Search everything" placeholder="Search orders, invoices, customers, products…  ( / )"
        className="w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm bg-gray-50 focus:bg-white focus:outline-none focus:ring-2 focus:ring-brand-400"
        value={text} onChange={e => { setText(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} onKeyDown={onKey} />
      {showing && (
        <div id="global-search-results" role="listbox" className="absolute left-0 right-0 mt-1 bg-white border border-gray-200 rounded-md shadow-lg z-50 max-h-[70vh] overflow-y-auto text-sm">
          {hits.length === 0 && <p className="px-3 py-3 text-gray-500">{isFetching ? "Searching…" : `Nothing found for “${q}”`}</p>}
          {(Object.keys(KIND) as SearchKind[]).filter(k => hits.some(h => h.kind === k)).map(k => (
            <div key={k}>
              <p className="px-3 pt-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{KIND[k]}</p>
              {hits.map((h, i) => h.kind !== k ? null : (
                <button key={`${h.kind}-${h.id}`} type="button" role="option" aria-selected={i === active}
                  className={`block w-full text-left px-3 py-1.5 ${i === active ? "bg-brand-50" : "hover:bg-gray-50"}`}
                  onMouseEnter={() => setActive(i)} onClick={() => go(h)}>
                  <span className="font-medium text-gray-900">{h.title}</span>
                  {h.detail && <span className="block text-xs text-gray-500 truncate">{h.detail}</span>}
                </button>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
