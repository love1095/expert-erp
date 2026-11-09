"use client";

import { useEffect, useRef, useState } from "react";

export interface ComboOption { key: string | number; name: string; sub?: string | null }

/**
 * A name box with the app's own list under it (like Cin7's customer and supplier fields), instead of the browser's
 * datalist: a click opens the list, typing narrows it, the name is on the left with a small second line under it.
 * A name that is not in the list can still be typed (e.g. a new customer). Keyboard: up/down, Enter or Tab, Esc.
 */
export default function NameCombobox({ id, value, onChange, options, placeholder, required, listLabel, className = "" }: {
  id: string;
  value: string;
  onChange: (name: string) => void;
  options: ComboOption[];
  placeholder?: string;
  required?: boolean;
  listLabel: string;          // e.g. "Customers" (the list's accessible name)
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  // Narrow by what was typed, but show the whole list when the box holds an exact name (just opened to change it)
  const [typed, setTyped] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const q = typed ? value.trim().toLowerCase() : "";
  const matches = (q ? options.filter(o => o.name.toLowerCase().includes(q) || (o.sub ?? "").toLowerCase().includes(q)) : options).slice(0, 200);
  const listId = `${id}-list`;

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const pick = (o: ComboOption) => { onChange(o.name); setOpen(false); setTyped(false); };
  const move = (to: number) => {
    const i = Math.max(0, Math.min(matches.length - 1, to));
    setActive(i);
    list.current?.querySelectorAll("li")[i]?.scrollIntoView({ block: "nearest" });
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); if (!open) { setOpen(true); setActive(0); } else move(active + 1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); if (open) move(active - 1); }
    else if (e.key === "Enter") { if (open && matches[active]) { e.preventDefault(); pick(matches[active]); } }
    else if (e.key === "Tab") { if (open && typed && q && matches[active]) pick(matches[active]); setOpen(false); }
    else if (e.key === "Escape") { if (open) { e.preventDefault(); setOpen(false); } }
  };

  return (
    <div ref={box} className="relative">
      <input ref={input} id={id} role="combobox" aria-expanded={open} aria-controls={listId} aria-autocomplete="list"
        aria-activedescendant={open && active >= 0 ? `${listId}-${active}` : undefined}
        value={value} required={required} autoComplete="off" placeholder={placeholder}
        onChange={e => { onChange(e.target.value); setTyped(true); setOpen(true); setActive(0); }}
        onFocus={() => setOpen(true)} onClick={() => setOpen(true)} onKeyDown={onKey}
        className={`${className} pr-8`} />
      <button type="button" aria-label="Show the list" tabIndex={-1}
        onMouseDown={e => e.preventDefault()} onClick={() => { setOpen(o => !o); setTyped(false); input.current?.focus(); }}
        className="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700 px-1">
        <svg viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4" aria-hidden><path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.17l3.71-3.94a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" /></svg>
      </button>
      {open && matches.length > 0 && (
        <ul ref={list} id={listId} role="listbox" aria-label={listLabel}
          className="absolute z-30 left-0 right-0 mt-1 max-h-72 overflow-y-auto bg-white border border-gray-200 rounded-md shadow-lg text-sm text-left erp-pop-in">
          {matches.map((o, i) => (
            <li key={o.key} id={`${listId}-${i}`} role="option" aria-selected={i === active}>
              <button type="button" tabIndex={-1} onMouseDown={e => e.preventDefault()} onClick={() => pick(o)} onMouseEnter={() => setActive(i)}
                className={`w-full text-left px-3 py-1.5 ${i === active ? "bg-brand-50" : ""}`}>
                <span className="block truncate text-gray-800">{o.name}</span>
                {o.sub && <span className="block truncate text-[11px] text-gray-400">{o.sub}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
