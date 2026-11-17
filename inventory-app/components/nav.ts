// The app's modules, grouped like Cin7 (Sale, Purchase, Inventory …). Used by the sidebar and the top bar trail.
// perm: only users whose role has this permission see the item (phase I1); a list: any one of them.
// "ADMIN" is no permission anyone is given: the item is for admins only (F8: the menu shows only what the role may open)
export type NavPerm = string | string[];
export interface NavItem { href: string; label: string; perm?: NavPerm }
export interface NavModule { key: string; label: string; icon: string; href?: string; perm?: NavPerm; items?: NavItem[] }

export const NAV: NavModule[] = [
  { key: "dashboard", label: "Dashboard", icon: "dashboard", href: "/", perm: "DASHBOARD" },
  { key: "sale", label: "Sale", icon: "sale", items: [
    { href: "/sale-orders", label: "Sale Orders", perm: "SALE_VIEW" },
    { href: "/picking",     label: "Pick & pack", perm: "SALE_VIEW" },
    { href: "/invoices",    label: "Invoices", perm: ["SALE_VIEW", "PURCHASE_VIEW"] },
    { href: "/customers",   label: "Customers", perm: "CUSTOMERS" },
    { href: "/overdue",     label: "Overdue", perm: "CUSTOMERS" },
  ]},
  { key: "purchase", label: "Purchase", icon: "purchase", items: [
    { href: "/purchase-orders", label: "Purchase Orders", perm: "PURCHASE_VIEW" },
    { href: "/reorder",         label: "Reorder", perm: "PURCHASE_VIEW" },
    { href: "/suppliers",       label: "Suppliers", perm: "PURCHASE_VIEW" },
  ]},
  { key: "inventory", label: "Inventory", icon: "inventory", items: [
    { href: "/stock-overview",  label: "Stock Overview", perm: "PRODUCT_VIEW" },
    { href: "/products",        label: "Products", perm: "PRODUCT_VIEW" },
    { href: "/warehouses",      label: "Warehouses" },
    { href: "/bins",            label: "Bins", perm: "PRODUCT_VIEW" },
    { href: "/stock-movements", label: "Movements Log", perm: "PRODUCT_VIEW" },
    { href: "/stock-transfer",  label: "Transfer", perm: "STOCK_TRANSFER" },
    { href: "/stock-adjustments", label: "Adjustments", perm: "PRODUCT_VIEW" },
    { href: "/stock-takes",     label: "Stock take", perm: "PRODUCT_VIEW" },
    { href: "/scan",            label: "Scanner (phone)", perm: ["SALE_VIEW", "PURCHASE_EDIT", "STOCK_ADJUST"] },
    { href: "/stock-history",   label: "History", perm: "PRODUCT_VIEW" },
    { href: "/certificates",    label: "Certificates expiring", perm: "PRODUCT_VIEW" },
  ]},
  { key: "finance", label: "Finance", icon: "finance", items: [
    { href: "/reports",        label: "Reports", perm: ["REPORTS_SALE", "REPORTS_PURCHASE", "REPORTS_INVENTORY", "REPORTS_FINANCIAL"] },
    { href: "/vat-return",     label: "VAT return", perm: "REPORTS_FINANCIAL" },
    { href: "/aged-debtors",   label: "Aged debtors", perm: "REPORTS_FINANCIAL" },
    { href: "/aged-creditors", label: "Aged creditors", perm: "REPORTS_FINANCIAL" },
    { href: "/reports/sales-profit",       label: "Sales Profit Summary",           perm: "REPORTS_FINANCIAL" },
    { href: "/reports/profit-by-rep",      label: "Profit by Sales Representatives", perm: "REPORTS_FINANCIAL" },
    { href: "/reports/sales-by-product",   label: "Sales by Product Summary",       perm: "REPORTS_FINANCIAL" },
    { href: "/reports/inventory-movement", label: "Inventory Movement Summary",     perm: "REPORTS_FINANCIAL" },
  ]},
  // Phase K: the ERP's own accounting, like Xero's Accounting menu
  { key: "accounting", label: "Accounting", icon: "accounting", items: [
    { href: "/accounting/bank",                 label: "Bank accounts",        perm: "ACCOUNTING" },
    { href: "/accounting/chart",                label: "Chart of accounts",    perm: "ACCOUNTING" },
    { href: "/accounting/manual-journals",      label: "Manual journals",      perm: "ACCOUNTING" },
    { href: "/accounting/profit-and-loss",      label: "Profit and loss",      perm: "ACCOUNTING" },
    { href: "/accounting/balance-sheet",        label: "Balance sheet",        perm: "ACCOUNTING" },
    { href: "/accounting/trial-balance",        label: "Trial balance",        perm: "ACCOUNTING" },
    { href: "/accounting/vat",                  label: "VAT returns (MTD)",    perm: "ACCOUNTING" },
    { href: "/accounting/account-transactions", label: "Account transactions", perm: "ACCOUNTING" },
    { href: "/accounting/journals",             label: "Journal report",       perm: "ACCOUNTING" },
    { href: "/accounting/settings",             label: "Ledger settings",      perm: "ACCOUNTING" },
  ]},
  { key: "admin", label: "Admin", icon: "admin", items: [
    { href: "/users",     label: "Users", perm: "ADMIN" },
    { href: "/audit-log", label: "Audit Log", perm: "AUDIT_LOG" },
  ]},
  { key: "settings", label: "Settings", icon: "settings", items: [
    { href: "/settings/organisation", label: "Organisation", perm: "SETTINGS" },
    { href: "/settings/subscription", label: "Subscription", perm: "SETTINGS" },
    { href: "/settings/integrations", label: "Integrations", perm: ["SETTINGS", "REPORTS_FINANCIAL"] },
    { href: "/settings/templates",    label: "Document templates", perm: "SETTINGS" },
    { href: "/settings/email",        label: "E-mail", perm: "SETTINGS" },
    { href: "/settings/data-import",  label: "Import sales & purchases", perm: "DATA_IMPORT" },
    { href: "/settings/go-live",      label: "Go-live check", perm: "DATA_IMPORT" },
  ]},
];

// The platform's super admin: in no organisation, so no business modules, just the organisations it serves
export const PLATFORM_HOME = "/platform/organisations";
export const PLATFORM_NAV: NavModule[] = [
  { key: "platform", label: "Platform", icon: "admin", items: [
    { href: PLATFORM_HOME, label: "Organisations" },
    { href: "/platform/integration-apps", label: "Integration apps" },
  ]},
];

export function navFor(role?: string, permissions?: string[]): NavModule[] {
  if (role === "SUPER_ADMIN") return PLATFORM_NAV;
  const has = (p: string) => role === "ADMIN" || (permissions ?? []).includes(p);
  const can = (p?: NavPerm) => !p || (Array.isArray(p) ? p.some(has) : has(p));
  return NAV.filter(m => can(m.perm)).map(m => m.items ? { ...m, items: m.items.filter(i => can(i.perm)) } : m)
    .filter(m => !m.items || m.items.length > 0);
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
