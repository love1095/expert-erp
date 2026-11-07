package com.experterp.backend.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a role lets its users see and do (phase I1), like Cin7 Core's Settings → Users &amp; Roles: each organisation
 * makes roles from these ticks and gives each user a role. Admins (Cin7: the owner) have every permission. Users
 * without a role get the defaults of their old role (Manager / Staff), so nothing changes until an admin sets roles.
 * The labels follow Cin7's where Cin7 has the same thing.
 */
public enum Permission {
    // Sale
    SALE_VIEW("Sale", "Sale - List and details", true, true),
    SALE_EDIT("Sale", "Sale - Add, edit and authorise (quotes, orders, shipping, invoices, credit notes, payments)", true, true),
    SALE_VOID("Sale", "Sale - Void, cancel and undo", true, true),
    SALE_CHANGE_PRICES("Sale", "Sale - Change prices and discounts", true, true),
    SALE_PROFIT("Sale", "Sale - View profit margin", true, false),
    SALE_IGNORE_CREDIT_LIMIT("Sale", "Sale - Ignore credit limit", true, false),
    CUSTOMERS("Sale", "Customers - List and details, add and edit", true, true),
    CUSTOMER_CREDIT("Sale", "Customer - Edit credit limit, credit hold and reminders", true, false),
    CUSTOMER_PRICES("Sale", "Customer - Contract prices", true, false),
    // Purchase
    PURCHASE_VIEW("Purchase", "Purchase - List and details, suppliers", true, true),
    PURCHASE_EDIT("Purchase", "Purchase - Add, edit and authorise (orders, receiving, bills, reorder, payments)", true, true),
    PURCHASE_VOID("Purchase", "Purchase - Void, cancel and undo", true, true),
    PURCHASE_PRICES("Purchase", "Purchase - View prices and totals", true, true),
    // Inventory
    PRODUCT_VIEW("Inventory", "Product - List and details, stock and movements", true, true),
    PRODUCT_EDIT("Inventory", "Product - Add and edit", true, true),
    PRODUCT_COST("Inventory", "Product - Prices: cost price", true, false),
    STOCK_VALUE("Inventory", "Product availability - View stock value", true, false),
    STOCK_ADJUST("Inventory", "Stock adjustments", true, true),
    STOCK_TRANSFER("Inventory", "Stock transfer", true, true),
    DATA_IMPORT("Inventory", "Import from CSV (products, customers, stock, sales, purchases)", true, false),
    // Reports, dashboard, audit (Cin7's report groups)
    REPORTS_SALE("Reports", "Sale reports (sales overview, invoices and credit notes, backorders)", true, true),
    REPORTS_PURCHASE("Reports", "Purchase reports (pending purchase orders, stock received not invoiced)", true, true),
    REPORTS_INVENTORY("Reports", "Inventory reports (stock value inventory aging)", true, false),
    REPORTS_FINANCIAL("Reports", "Financial reports (VAT return, aged debtors and creditors, sales profit, inventory movement, Xero export)", true, false),
    // Phase K: the ERP's own accounting (like Xero)
    ACCOUNTING("Accounting", "Accounting - Chart of accounts, manual journals, trial balance and ledger reports", true, false),
    // Xero's Adviser: the accountant's rights (K5)
    ADVISER("Accounting", "Accounting - Adviser: chart of accounts, ledger settings and lock dates, work up to the all-users lock date, file VAT returns", false, false),
    DASHBOARD("Dashboard", "Dashboard", true, true),
    // Phase Q5: settings no longer admin only — users and roles, API keys and FIFO rebuilds stay with admins
    SETTINGS("Settings", "Settings - Organisation, numbering, document templates, e-mail, price tiers, payment accounts, Xero export accounts", false, false),
    AUDIT_LOG("Audit", "Audit log - View", true, false);

    private final String group;
    private final String label;
    private final boolean manager;
    private final boolean staff;

    Permission(String group, String label, boolean manager, boolean staff) {
        this.group = group;
        this.label = label;
        this.manager = manager;
        this.staff = staff;
    }

    public String group() { return group; }
    public String label() { return label; }
    public String authority() { return "PERM_" + name(); }

    /** The old fixed roles as default permission sets. */
    public static Set<Permission> defaultsFor(com.experterp.backend.entity.User.Role role) {
        if (role == null) return EnumSet.noneOf(Permission.class);
        return switch (role) {
            case SUPER_ADMIN, ADMIN -> EnumSet.allOf(Permission.class);
            case MANAGER -> Arrays.stream(values()).filter(p -> p.manager)
                    .collect(() -> EnumSet.noneOf(Permission.class), Set::add, Set::addAll);
            case STAFF -> Arrays.stream(values()).filter(p -> p.staff)
                    .collect(() -> EnumSet.noneOf(Permission.class), Set::add, Set::addAll);
        };
    }

    /**
     * Whether the signed-in user has this permission. A login without permission authorities (jobs, tests) is
     * judged by its role's defaults; no login at all (scheduled jobs) may do everything.
     */
    public static boolean current(Permission p) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getPrincipal())) return true;
        java.util.List<String> a = auth.getAuthorities().stream().map(x -> x.getAuthority()).toList();
        if (a.stream().anyMatch(x -> x.startsWith("PERM_"))) return a.contains(p.authority());
        return a.stream().filter(x -> x.startsWith("ROLE_"))
                .anyMatch(x -> {
                    try { return defaultsFor(com.experterp.backend.entity.User.Role.valueOf(x.substring(5))).contains(p); }
                    catch (IllegalArgumentException e) { return false; }
                });
    }

    /** Stored as a comma-separated list; names no longer known are dropped. */
    public static Set<Permission> parse(String csv) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            try { out.add(valueOf(s.trim())); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }
}
