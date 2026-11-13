package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings → Organisation → Payment terms and Sales representatives: the lists behind the "+" buttons, with how often
 * each entry is used, renaming (the customers, suppliers and orders using it follow, so nothing is left on the old
 * name) and removing (only an entry nothing uses: what is in use is never taken off a document).
 */
@Service
@RequiredArgsConstructor
public class PickListService {

    public record Entry(String name, String meaning, boolean added, long uses) {}

    private final JdbcTemplate jdbc;
    private final PaymentTermService paymentTerms;
    private final CustomerService customers;
    private final AuditService auditService;

    // Where a payment term is written, and where a sales representative is
    private static final String[][] TERM_USES = {{"customers", "payment_term"}, {"suppliers", "payment_term"},
            {"sale_orders", "payment_term"}, {"purchase_orders", "payment_term"}};
    private static final String[][] REP_USES = {{"customers", "sales_representative"}, {"sale_orders", "sales_rep"}};

    @Transactional(readOnly = true)
    public List<Entry> paymentTerms() {
        List<Entry> out = new ArrayList<>();
        for (PaymentTermService.Term t : paymentTerms.list()) {
            out.add(new Entry(t.name(), t.meaning(), stored("payment_terms", t.name()), uses(TERM_USES, t.name())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Entry> salesReps() {
        String web = webSalesRep();
        List<Entry> out = new ArrayList<>();
        for (String name : customers.salesReps()) {
            out.add(new Entry(name, name.equalsIgnoreCase(web) ? "Sales rep for Shopify orders" : null, stored("sales_reps", name),
                    uses(REP_USES, name)));
        }
        return out;
    }

    /** The term keeps its rule under the new name; every customer, supplier and order on the old name follows. */
    @Transactional
    public void renamePaymentTerm(String from, String to) {
        String old = required(from), name = newName(to);
        List<PaymentTermService.Term> list = paymentTerms.list();
        PaymentTermService.Term current = list.stream().filter(t -> t.name().equalsIgnoreCase(old)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There is no payment term called " + old));
        if (list.stream().anyMatch(t -> t.name().equalsIgnoreCase(name) && !t.name().equalsIgnoreCase(old))) {
            throw new IllegalArgumentException("There is already a payment term called " + name);
        }
        Long t = TenantContext.get();
        if (stored("payment_terms", current.name())) {
            jdbc.update("UPDATE payment_terms SET name = ? WHERE tenant_id = ? AND lower(name) = lower(?)", name, t, current.name());
        } else {
            // A term read from its name (e.g. Cin7's "30 Day EOM"): its rule is written down, so the new name keeps it
            Rule rule = ruleOf(current.name());
            jdbc.update("INSERT INTO payment_terms (tenant_id, name, method, days) VALUES (?, ?, ?, ?)", t, name, rule.method().name(), rule.days());
        }
        long moved = 0;
        for (String[] u : TERM_USES) {
            moved += jdbc.update("UPDATE " + u[0] + " SET " + u[1] + " = ? WHERE tenant_id = ? AND lower(trim(" + u[1] + ")) = lower(?)", name, t, current.name());
        }
        auditService.log("PAYMENT_TERM", 0L, "RENAME", Map.of("from", current.name(), "to", name, "changed", moved));
    }

    @Transactional
    public void removePaymentTerm(String name) {
        String n = required(name);
        long uses = uses(TERM_USES, n);
        if (uses > 0) throw new IllegalStateException(n + " is used on " + uses + " customers, suppliers or orders: give them another term first");
        int gone = jdbc.update("DELETE FROM payment_terms WHERE tenant_id = ? AND lower(name) = lower(?)", TenantContext.get(), n);
        if (gone == 0) throw new IllegalArgumentException("There is no payment term called " + n + " to remove");
        auditService.log("PAYMENT_TERM", 0L, "DELETE", Map.of("name", n));
    }

    /** Customers, sale orders and the Shopify sales rep setting on the old name follow the new one. */
    @Transactional
    public void renameSalesRep(String from, String to) {
        String old = required(from), name = newName(to);
        List<String> list = customers.salesReps();
        String current = list.stream().filter(old::equalsIgnoreCase).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There is no sales representative called " + old));
        if (list.stream().anyMatch(r -> r.equalsIgnoreCase(name) && !r.equalsIgnoreCase(old))) {
            throw new IllegalArgumentException("There is already a sales representative called " + name);
        }
        Long t = TenantContext.get();
        jdbc.update("UPDATE sales_reps SET name = ? WHERE tenant_id = ? AND lower(name) = lower(?)", name, t, current);
        long moved = 0;
        for (String[] u : REP_USES) {
            moved += jdbc.update("UPDATE " + u[0] + " SET " + u[1] + " = ? WHERE tenant_id = ? AND lower(trim(" + u[1] + ")) = lower(?)", name, t, current);
        }
        jdbc.update("UPDATE tenants SET web_sales_rep = ? WHERE id = ? AND lower(trim(web_sales_rep)) = lower(?)", name, t, current);
        auditService.log("SALES_REP", 0L, "RENAME", Map.of("from", current, "to", name, "changed", moved));
    }

    @Transactional
    public void removeSalesRep(String name) {
        String n = required(name);
        if (n.equalsIgnoreCase(webSalesRep())) {
            throw new IllegalStateException(n + " is the sales rep for Shopify orders (Settings, Organisation): choose another there first");
        }
        long uses = uses(REP_USES, n);
        if (uses > 0) throw new IllegalStateException(n + " is on " + uses + " customers or sale orders: give them another sales representative first");
        int gone = jdbc.update("DELETE FROM sales_reps WHERE tenant_id = ? AND lower(name) = lower(?)", TenantContext.get(), n);
        if (gone == 0) throw new IllegalArgumentException("There is no sales representative called " + n + " to remove");
        auditService.log("SALES_REP", 0L, "DELETE", Map.of("name", n));
    }

    private record Rule(PaymentTermService.Method method, int days) {}

    // The rule a name-read term follows, worked out from the due dates it gives
    private Rule ruleOf(String term) {
        LocalDate a = LocalDate.of(2026, 1, 10), b = LocalDate.of(2026, 2, 20);
        LocalDate dueA = paymentTerms.dueDate(term, a), dueB = paymentTerms.dueDate(term, b);
        long daysA = java.time.temporal.ChronoUnit.DAYS.between(a, dueA), daysB = java.time.temporal.ChronoUnit.DAYS.between(b, dueB);
        if (daysA == 0 && daysB == 0) return new Rule(PaymentTermService.Method.ON_INVOICE, 0);
        if (daysA == daysB) return new Rule(PaymentTermService.Method.DAYS, (int) daysA);
        long afterMonthEnd = java.time.temporal.ChronoUnit.DAYS.between(a.withDayOfMonth(a.lengthOfMonth()), dueA);
        return new Rule(PaymentTermService.Method.DAYS_EOM, (int) Math.max(afterMonthEnd, 0));
    }

    private boolean stored(String table, String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND lower(name) = lower(?)", Long.class, TenantContext.get(), name);
        return n != null && n > 0;
    }

    private long uses(String[][] where, String name) {
        long total = 0;
        for (String[] u : where) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + u[0] + " WHERE tenant_id = ? AND lower(trim(" + u[1] + ")) = lower(?)",
                    Long.class, TenantContext.get(), name);
            total += n == null ? 0 : n;
        }
        return total;
    }

    private String webSalesRep() {
        List<String> web = jdbc.queryForList("SELECT trim(web_sales_rep) FROM tenants WHERE id = ?", String.class, TenantContext.get());
        return web.isEmpty() || web.get(0) == null ? "" : web.get(0);
    }

    private static String required(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Choose the entry");
        return name.trim();
    }

    private static String newName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Enter the new name");
        if (n.length() > 100) throw new IllegalArgumentException("The name is too long (100 characters at most)");
        return n;
    }
}
