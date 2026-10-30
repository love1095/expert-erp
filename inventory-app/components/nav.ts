// The app's modules, grouped like Cin7 (Sale, Purchase, Inventory …). Used by the sidebar and the top bar trail.
export interface NavItem { href: string; label: string }
export interface NavModule { key: string; label: string; icon: string; href?: string; items?: NavItem[] }

export const NAV: NavModule[] = [
  { key: "dashboard", label: "Dashboard", icon: "dashboard", href: "/" },
  { key: "sale", label: "Sale", icon: "sale", items: [
    { href: "/sale-orders", label: "Sale Orders" },
    { href: "/invoices",    label: "Invoices" },
    { href: "/customers",   label: "Customers" },
  ]},
  { key: "purchase", label: "Purchase", icon: "purchase", items: [
    { href: "/purchase-orders", label: "Purchase Orders" },
    { href: "/reorder",         label: "Reorder" },
    { href: "/suppliers",       label: "Suppliers" },
  ]},
  { key: "inventory", label: "Inventory", icon: "inventory", items: [
    { href: "/stock-overview",  label: "Stock Overview" },
    { href: "/products",        label: "Products" },
    { href: "/warehouses",      label: "Warehouses" },
    { href: "/stock-movements", label: "Movements Log" },
    { href: "/stock-transfer",  label: "Transfer" },
    { href: "/stock-history",   label: "History" },
  ]},
  { key: "admin", label: "Admin", icon: "admin", items: [
    { href: "/users",     label: "Users" },
    { href: "/audit-log", label: "Audit Log" },
  ]},
  { key: "settings", label: "Settings", icon: "settings", items: [
    { href: "/settings/organisation", label: "Organisation" },
    { href: "/settings/subscription", label: "Subscription" },
    { href: "/settings/integrations", label: "Integrations" },
  ]},
];

export function isActive(href: string, pathname: string) {
  return pathname === href || (href !== "/" && pathname.startsWith(href + "/"));
}

// Module and page for the current path, e.g. Sale › Invoices
export function locate(pathname: string): { module?: NavModule; item?: NavItem } {
  for (const m of NAV) {
    if (m.href && isActive(m.href, pathname)) return { module: m };
    const item = m.items?.find(i => isActive(i.href, pathname));
    if (item) return { module: m, item };
  }
  return {};
}
