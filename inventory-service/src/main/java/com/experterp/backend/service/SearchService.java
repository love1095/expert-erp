package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.security.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Search everything from the top bar (phase I2, spec §8). Cin7 Core has no such box (it searches inside each list);
 * this finds sale orders, invoices and credit notes, bills, purchase orders, customers, suppliers and products by
 * number, name, reference, SKU or barcode, "contains" in any case. Each kind only for users whose role can open it;
 * the closest matches first (the text exactly, then starting with it, then newest).
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    static final int PER_KIND = 6;

    private final JdbcTemplate jdbc;

    /** One hit: what it is, its id, the main text (number / name) and a second line. */
    public record Hit(String kind, Long id, String title, String detail) {}

    @Transactional(readOnly = true)
    public List<Hit> search(String q) {
        String text = q == null ? "" : q.trim();
        if (text.length() < 2) return List.of();
        String like = "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String exact = text.toLowerCase();
        Long t = TenantContext.get();
        List<Hit> hits = new ArrayList<>();
        if (Permission.current(Permission.SALE_VIEW)) {
            hits.addAll(jdbc.query("""
                    SELECT id, order_number, customer_name, customer_reference, external_reference, status
                    FROM sale_orders WHERE tenant_id = ? AND (lower(order_number) LIKE ? OR lower(customer_name) LIKE ?
                      OR lower(coalesce(customer_reference, '')) LIKE ? OR lower(coalesce(external_reference, '')) LIKE ?)
                    ORDER BY (lower(order_number) = ?) DESC, (lower(order_number) LIKE ?) DESC, id DESC LIMIT ?""",
                    (rs, n) -> new Hit("SALE_ORDER", rs.getLong(1), rs.getString(2), join(rs.getString(3),
                            rs.getString(4) == null ? null : "Ref " + rs.getString(4),
                            rs.getString(5) == null ? null : "Shopify " + rs.getString(5), cap(rs.getString(6)))),
                    t, like, like, like, like, exact, exact + "%", PER_KIND));
        }
        boolean sales = Permission.current(Permission.SALE_VIEW), purchases = Permission.current(Permission.PURCHASE_VIEW);
        if (sales || purchases) {
            hits.addAll(jdbc.query("""
                    SELECT id, invoice_number, party_name, type, kind, status, total_amount
                    FROM invoices WHERE tenant_id = ? AND type IN (?, ?) AND (lower(coalesce(invoice_number, '')) LIKE ? OR lower(coalesce(party_name, '')) LIKE ?)
                    ORDER BY (lower(coalesce(invoice_number, '')) = ?) DESC, (lower(coalesce(invoice_number, '')) LIKE ?) DESC, id DESC LIMIT ?""",
                    (rs, n) -> {
                        boolean sale = "SALES".equals(rs.getString(4)), credit = "CREDIT_NOTE".equals(rs.getString(5));
                        String what = sale ? (credit ? "Credit note" : "Invoice") : (credit ? "Supplier credit note" : "Bill");
                        return new Hit(sale ? "INVOICE" : "BILL", rs.getLong(1),
                                rs.getString(2) == null ? "Draft " + what.toLowerCase() : rs.getString(2),
                                join(what, rs.getString(3), cap(rs.getString(6))));
                    }, t, sales ? "SALES" : "-", purchases ? "PURCHASE" : "-", like, like, exact, exact + "%", PER_KIND));
        }
        if (purchases) {
            hits.addAll(jdbc.query("""
                    SELECT id, order_number, supplier_name, supplier_reference, status
                    FROM purchase_orders WHERE tenant_id = ? AND (lower(coalesce(order_number, '')) LIKE ? OR lower(coalesce(supplier_name, '')) LIKE ?
                      OR lower(coalesce(supplier_reference, '')) LIKE ?)
                    ORDER BY (lower(coalesce(order_number, '')) = ?) DESC, (lower(coalesce(order_number, '')) LIKE ?) DESC, id DESC LIMIT ?""",
                    (rs, n) -> new Hit("PURCHASE_ORDER", rs.getLong(1), rs.getString(2), join(rs.getString(3),
                            rs.getString(4) == null ? null : "Ref " + rs.getString(4), cap(rs.getString(5)))),
                    t, like, like, like, exact, exact + "%", PER_KIND));
            hits.addAll(jdbc.query("""
                    SELECT id, name, contact_name, email FROM suppliers WHERE tenant_id = ?
                      AND (lower(name) LIKE ? OR lower(coalesce(contact_name, '')) LIKE ? OR lower(coalesce(email, '')) LIKE ?)
                    ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, lower(name) LIMIT ?""",
                    (rs, n) -> new Hit("SUPPLIER", rs.getLong(1), rs.getString(2), join(rs.getString(3), rs.getString(4))),
                    t, like, like, like, exact, exact + "%", PER_KIND));
        }
        if (Permission.current(Permission.CUSTOMERS) || sales) {
            hits.addAll(jdbc.query("""
                    SELECT id, name, status, sales_representative FROM customers WHERE tenant_id = ? AND lower(name) LIKE ?
                    ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, lower(name) LIMIT ?""",
                    (rs, n) -> new Hit("CUSTOMER", rs.getLong(1), rs.getString(2), join(cap(rs.getString(3)), rs.getString(4))),
                    t, like, exact, exact + "%", PER_KIND));
        }
        if (Permission.current(Permission.PRODUCT_VIEW)) {
            hits.addAll(jdbc.query("""
                    SELECT id, sku, name, barcode, status FROM products WHERE tenant_id = ?
                      AND (lower(sku) LIKE ? OR lower(name) LIKE ? OR lower(coalesce(barcode, '')) LIKE ? OR lower(coalesce(supplier_product_code, '')) LIKE ?)
                    ORDER BY (lower(sku) = ? OR lower(coalesce(barcode, '')) = ?) DESC, (lower(sku) LIKE ?) DESC, (status = 'ACTIVE') DESC, lower(sku) LIMIT ?""",
                    (rs, n) -> new Hit("PRODUCT", rs.getLong(1), rs.getString(2), join(rs.getString(3),
                            "DEPRECATED".equals(rs.getString(5)) ? "Deprecated" : null)),
                    t, like, like, like, like, exact, exact, exact + "%", PER_KIND));
        }
        return hits;
    }

    private static String join(String... parts) {
        List<String> p = new ArrayList<>();
        for (String s : parts) if (s != null && !s.isBlank()) p.add(s);
        return String.join(" · ", p);
    }

    private static String cap(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
