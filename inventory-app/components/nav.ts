// The app's modules, grouped like Cin7 (Sale, Purchase, Inventory …). Used by the sidebar and the top bar trail.
// perm: only users whose role has this permission see the item (phase I1)
export interface NavItem { href: string; label: string; perm?: string }
export interface NavModule { key: string; label: string; icon: string; href?: string; items?: NavItem[] }

export const NAV: NavModule[] = [
  { key: "dashboard", label: "Dashboard", icon: "dashboard", href: "/" },
  { key: "sale", label: "Sale", icon: "sale", items: [
    { href: "/sale-orders", label: "Sale Orders", perm: "SALE_VIEW" },
    { href: "/picking",     label: "Pick & pack", perm: "SALE_VIEW" },
    { href: "/invoices",    label: "Invoices" },
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
    { href: "/stock-history",   label: "History", perm: "PRODUCT_VIEW" },
  ]},
  { key: "finance", label: "Finance", icon: "finance", items: [
    { href: "/reports",        label: "Reports" },
    { href: "/vat-return",     label: "VAT return", perm: "REPORTS_FINANCIAL" },
    { href: "/aged-debtors",   label: "Aged debtors", perm: "REPORTS_FINANCIAL" },
    { href: "/aged-creditors", label: "Aged creditors", perm: "REPORTS_FINANCIAL" },
    { href: "/reports/sales-profit",       label: "Sales Profit Summary",           perm: "REPORTS_FINANCIAL" },
    { href: "/reports/profit-by-rep",      label: "Profit by Sales Representatives", perm: "REPORTS_FINANCIAL" },
    { href: "/reports/sales-by-product",   label: "Sales by Product Summary",       perm: "REPORTS_FINANCIAL" },
    { href: "/reports/inventory-movement", label: "Inventory Movement Summary",     perm: "REPORTS_FINANCIAL" },
    { href: "/finance/xero",               label: "Xero",                           perm: "REPORTS_FINANCIAL" },
    { href: "/accounting-export",          label: "Xero export (CSV)",              perm: "REPORTS_FINANCIAL" },
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
    { href: "/users",     label: "Users" },
    { href: "/audit-log", label: "Audit Log", perm: "AUDIT_LOG" },
  ]},
  { key: "settings", label: "Settings", icon: "settings", items: [
    { href: "/settings/organisation", label: "Organisation" },
    { href: "/settings/subscription", label: "Subscription" },
    { href: "/settings/integrations", label: "Integrations" },
    { href: "/settings/templates",    label: "Document templates" },
    { href: "/settings/email",        label: "E-mail" },
    { href: "/settings/data-import",  label: "Import sales & purchases", perm: "DATA_IMPORT" },
    { href: "/settings/go-live",      label: "Go-live check", perm: "DATA_IMPORT" },
  ]},
];

// The platform's super admin: in no organisation, so no business modules, just the organisations it serves
export const PLATFORM_HOME = "/platform/organisations";
export const PLATFORM_NAV: NavModule[] = [
  { key: "platform", label: "Platform", icon: "admin", items: [
    { href: PLATFORM_HOME, label: "Organisations" },
  ]},
];

export function navFor(role?: string, permissions?: string[]): NavModule[] {
  if (role === "SUPER_ADMIN") return PLATFORM_NAV;
  const can = (p?: string) => !p || role === "ADMIN" || (permissions ?? []).includes(p);
  return NAV.map(m => m.items ? { ...m, items: m.items.filter(i => can(i.perm)) } : m)
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
