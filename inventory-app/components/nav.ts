// The app's modules, grouped like Cin7 (Sale, Purchase, Inventory …). Used by the sidebar and the top bar trail.
// roles: only these roles see the item (e.g. reports with cost and profit)
export interface NavItem { href: string; label: string; roles?: string[] }
export interface NavModule { key: string; label: string; icon: string; href?: string; items?: NavItem[] }

// Cost and profit: managers and admins (Cin7 sets report permissions per user; phase I)
const MANAGERS = ["MANAGER", "ADMIN"];

export const NAV: NavModule[] = [
  { key: "dashboard", label: "Dashboard", icon: "dashboard", href: "/" },
  { key: "sale", label: "Sale", icon: "sale", items: [
    { href: "/sale-orders", label: "Sale Orders" },
    { href: "/invoices",    label: "Invoices" },
    { href: "/customers",   label: "Customers" },
    { href: "/overdue",     label: "Overdue" },
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
  { key: "finance", label: "Finance", icon: "finance", items: [
    { href: "/vat-return",     label: "VAT return" },
    { href: "/aged-debtors",   label: "Aged debtors" },
    { href: "/aged-creditors", label: "Aged creditors" },
    { href: "/reports/sales-profit",       label: "Sales Profit Summary",           roles: MANAGERS },
    { href: "/reports/profit-by-rep",      label: "Profit by Sales Representatives", roles: MANAGERS },
    { href: "/reports/sales-by-product",   label: "Sales by Product Summary",       roles: MANAGERS },
    { href: "/reports/inventory-movement", label: "Inventory Movement Summary",     roles: MANAGERS },
    { href: "/accounting-export",          label: "Xero export",                    roles: MANAGERS },
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

// The platform's super admin: in no organisation, so no business modules, just the organisations it serves
export const PLATFORM_HOME = "/platform/organisations";
export const PLATFORM_NAV: NavModule[] = [
  { key: "platform", label: "Platform", icon: "admin", items: [
    { href: PLATFORM_HOME, label: "Organisations" },
  ]},
];

export function navFor(role?: string): NavModule[] {
  if (role === "SUPER_ADMIN") return PLATFORM_NAV;
  return NAV.map(m => m.items ? { ...m, items: m.items.filter(i => !i.roles || (role != null && i.roles.includes(role))) } : m);
}

export function isActive(href: string, pathname: string) {
  return pathname === href || (href !== "/" && pathname.startsWith(href + "/"));
}

// Module and page for the current path, e.g. Sale › Invoices
export function locate(pathname: string): { module?: NavModule; item?: NavItem } {
  for (const m of [...NAV, ...PLATFORM_NAV]) {
    if (m.href && isActive(m.href, pathname)) return { module: m };
    const item = m.items?.find(i => isActive(i.href, pathname));
    if (item) return { module: m, item };
  }
  return {};
}
