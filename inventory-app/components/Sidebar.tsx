"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import NavIcon from "@/components/NavIcon";
import { NAV, isActive, locate } from "@/components/nav";

const OPEN_KEY = "nav.open";
const COLLAPSED_KEY = "nav.collapsed";

function load<T>(key: string, fallback: T): T {
  try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; } catch { return fallback; }
}
function save(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private window */ }
}

export default function Sidebar() {
  const pathname = usePathname();
  const current = locate(pathname).module?.key;

  // Open modules (remembered); going to a page opens its module
  const [open, setOpen] = useState<string[]>([]);
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    setOpen(load<string[]>(OPEN_KEY, []));
    setCollapsed(load(COLLAPSED_KEY, false));
  }, []);
  useEffect(() => {
    if (current) setOpen(o => o.includes(current) ? o : [...o, current]);
  }, [current]);

  const toggle = (key: string) => {
    const next = open.includes(key) ? open.filter(k => k !== key) : [...open, key];
    setOpen(next);
    save(OPEN_KEY, next);
  };
  const isOpen = (key: string) => open.includes(key);

  const toggleCollapsed = () => { setCollapsed(!collapsed); save(COLLAPSED_KEY, !collapsed); };

  return (
    <aside className={`${collapsed ? "w-16" : "w-60"} bg-shell text-slate-300 flex flex-col shrink-0 transition-[width] duration-150`}>
      <Link href="/" className="h-14 flex items-center gap-2.5 px-4 border-b border-white/10 shrink-0">
        <span className="w-8 h-8 rounded-md bg-accent-600 text-white font-bold text-sm flex items-center justify-center shrink-0">E</span>
        {!collapsed && (
          <span className="leading-tight">
            <span className="block text-sm font-semibold text-white">Expert ERP</span>
          </span>
        )}
      </Link>

      <nav aria-label="Main" className="flex-1 py-3 overflow-y-auto">
        {NAV.map(m => {
          if (m.href) {
            const active = isActive(m.href, pathname);
            return (
              <Link key={m.key} href={m.href} title={collapsed ? m.label : undefined}
                className={`mx-2 flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium ${
                  active ? "bg-shell-active text-white" : "hover:bg-shell-hover hover:text-white"}`}>
                <NavIcon name={m.icon} className={`w-5 h-5 shrink-0 ${active ? "text-accent-400" : ""}`} />
                {!collapsed && m.label}
              </Link>
            );
          }
          const expanded = isOpen(m.key);
          const here = m.key === current;
          return (
            <div key={m.key} className="mt-0.5">
              <button type="button" onClick={() => collapsed ? toggleCollapsed() : toggle(m.key)}
                aria-expanded={expanded} title={collapsed ? m.label : undefined}
                className={`mx-2 w-[calc(100%-1rem)] flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium ${
                  here ? "text-white" : "hover:bg-shell-hover hover:text-white"}`}>
                <NavIcon name={m.icon} className={`w-5 h-5 shrink-0 ${here ? "text-accent-400" : ""}`} />
                {!collapsed && <>
                  <span className="flex-1 text-left">{m.label}</span>
                  <NavIcon name="chevron" className={`w-3.5 h-3.5 transition-transform ${expanded ? "rotate-90" : ""}`} />
                </>}
              </button>
              {!collapsed && expanded && (
                <div className="mt-0.5 mb-1">
                  {m.items!.map(i => {
                    const active = isActive(i.href, pathname);
                    return (
                      <Link key={i.href} href={i.href}
                        className={`relative mx-2 flex items-center pl-11 pr-3 py-1.5 rounded-md text-[13px] ${
                          active ? "bg-shell-active text-white font-medium" : "text-slate-400 hover:bg-shell-hover hover:text-white"}`}>
                        {active && <span className="absolute left-[1.35rem] w-1.5 h-1.5 rounded-full bg-accent-500" />}
                        {i.label}
                      </Link>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </nav>

      <button type="button" onClick={toggleCollapsed}
        className="h-11 flex items-center gap-3 px-6 border-t border-white/10 text-xs text-slate-400 hover:text-white shrink-0">
        <NavIcon name="collapse" className={`w-4 h-4 shrink-0 ${collapsed ? "rotate-180" : ""}`} />
        {!collapsed && "Collapse menu"}
      </button>
    </aside>
  );
}
