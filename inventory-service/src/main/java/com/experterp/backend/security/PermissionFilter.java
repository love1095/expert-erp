package com.experterp.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.experterp.backend.security.Permission.*;

/**
 * Checks each signed-in request against the user's permissions (phase I1): one table of rules, first match wins, in
 * the order below (specific actions before the module's general rule). A rule names permissions of which the user
 * needs one. Paths with no rule only need a login (settings, users and the like keep their admin checks).
 */
@Component
public class PermissionFilter extends OncePerRequestFilter {

    record Rule(String method, String pattern, Set<Permission> anyOf) {}

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static Rule r(String method, String pattern, Permission... anyOf) {
        return new Rule(method, pattern, Set.of(anyOf));
    }

    static final List<Rule> RULES = List.of(
            // Sale
            r("POST", "/api/sale-orders/*/cancel", SALE_VOID),
            r("POST", "/api/sale-orders/*/undo", SALE_VOID),
            r("POST", "/api/sale-orders/*/reject", SALE_VOID),
            r("GET", "/api/sale-orders/**", SALE_VIEW),
            r("*", "/api/sale-orders/**", SALE_EDIT),
            r("GET", "/api/sale-orders", SALE_VIEW),
            r("*", "/api/sale-orders", SALE_EDIT),
            r("GET", "/api/customers/*/credit", CUSTOMERS, SALE_VIEW),
            r("*", "/api/customers/*/credit/**", CUSTOMER_CREDIT),
            r("POST", "/api/customers/*/reminder", CUSTOMER_CREDIT),
            r("*", "/api/customers/*/contract-prices/**", CUSTOMER_PRICES),
            r("*", "/api/customers/*/contract-prices", CUSTOMER_PRICES),
            r("POST", "/api/customers/*/credits/*/void", SALE_VOID),
            r("POST", "/api/customers/import", DATA_IMPORT),
            r("GET", "/api/customers/**", CUSTOMERS, SALE_VIEW),
            r("GET", "/api/customers", CUSTOMERS, SALE_VIEW),
            r("*", "/api/customers/**", CUSTOMERS),
            r("*", "/api/customers", CUSTOMERS),
            r("GET", "/api/statements/**", CUSTOMERS),
            // Invoices hold sales invoices and supplier bills: either side's permission
            r("POST", "/api/invoices/*/cancel", SALE_VOID, PURCHASE_VOID),
            r("POST", "/api/invoices/*/payments/*/void", SALE_VOID, PURCHASE_VOID),
            r("GET", "/api/invoices/**", SALE_VIEW, PURCHASE_VIEW),
            r("GET", "/api/invoices", SALE_VIEW, PURCHASE_VIEW),
            r("*", "/api/invoices/**", SALE_EDIT, PURCHASE_EDIT),
            r("*", "/api/invoices", SALE_EDIT, PURCHASE_EDIT),
            // Purchase
            r("POST", "/api/purchase-orders/*/cancel", PURCHASE_VOID),
            r("POST", "/api/purchase-orders/*/undo", PURCHASE_VOID),
            r("GET", "/api/purchase-orders/**", PURCHASE_VIEW),
            r("GET", "/api/purchase-orders", PURCHASE_VIEW),
            r("*", "/api/purchase-orders/**", PURCHASE_EDIT),
            r("*", "/api/purchase-orders", PURCHASE_EDIT),
            r("POST", "/api/suppliers/*/credits/*/void", PURCHASE_VOID),
            r("GET", "/api/suppliers/**", PURCHASE_VIEW),
            r("GET", "/api/suppliers", PURCHASE_VIEW, PRODUCT_EDIT),
            r("*", "/api/suppliers/**", PURCHASE_EDIT),
            r("*", "/api/suppliers", PURCHASE_EDIT),
            r("GET", "/api/reorder/**", PURCHASE_VIEW),
            r("GET", "/api/reorder", PURCHASE_VIEW),
            r("*", "/api/reorder/**", PURCHASE_EDIT),
            // Inventory
            r("POST", "/api/products/import", DATA_IMPORT),
            r("POST", "/api/products/delete-deprecated", DATA_IMPORT),
            r("GET", "/api/products/options", PRODUCT_VIEW, SALE_EDIT, PURCHASE_EDIT),
            r("GET", "/api/products/**", PRODUCT_VIEW),
            r("GET", "/api/products", PRODUCT_VIEW),
            r("*", "/api/products/**", PRODUCT_EDIT),
            r("*", "/api/products", PRODUCT_EDIT),
            r("POST", "/api/stock/import", DATA_IMPORT),
            r("POST", "/api/stock-movements/transfer", STOCK_TRANSFER),
            r("GET", "/api/stock-movements/**", PRODUCT_VIEW),
            r("GET", "/api/stock-movements", PRODUCT_VIEW),
            r("POST", "/api/stock-movements", STOCK_ADJUST),
            r("GET", "/api/stock/**", PRODUCT_VIEW),
            r("GET", "/api/stock", PRODUCT_VIEW),
            r("*", "/api/categories/**", PRODUCT_EDIT),
            r("POST", "/api/categories", PRODUCT_EDIT),
            // Reports and the rest (PDFs follow their document's rule above)
            r("*", "/api/reports/**", REPORTS_FINANCIAL),
            r("*", "/api/accounting/**", REPORTS_FINANCIAL),
            r("*", "/api/dashboard/**", DASHBOARD),
            r("*", "/api/dashboard", DASHBOARD),
            r("*", "/api/audit-log/**", AUDIT_LOG),
            r("*", "/api/audit-log", AUDIT_LOG)
    );

    /** The permissions the rule for this request needs (one of them), or null when no rule applies. */
    static Set<Permission> required(String method, String path) {
        for (Rule rule : RULES) {
            if ((rule.method().equals("*") || rule.method().equalsIgnoreCase(method)) && MATCHER.match(rule.pattern(), path)) {
                return rule.anyOf();
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"OPTIONS".equals(request.getMethod())) {
            Set<Permission> need = required(request.getMethod(), request.getRequestURI());
            if (need != null) {
                Set<String> has = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                if (need.stream().noneMatch(p -> has.contains(p.authority()))) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    String what = need.stream().map(Permission::label).sorted().collect(Collectors.joining(" or "));
                    response.getWriter().write("{\"error\":\"Your role does not allow this: " + what.replace("\"", "'") + "\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
