package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The organisation's payment terms list, like Cin7's: the terms added by hand ("+" on a Terms field: a name, how the
 * due date is worked out, the number of days) together with every term already used on customers, suppliers and orders.
 * A term added by hand gives the due date by its own rule; any other term is read from its name ({@link PaymentTerms}).
 */
@Service
@RequiredArgsConstructor
public class PaymentTermService {

    // Offered to an organisation that has no terms yet (Cin7's usual ones)
    static final List<String> STANDARD_TERMS = List.of("Cash", "7 Days", "14 Days", "30 Days", "30 Day EOM", "60 Day EOM");

    public enum Method { ON_INVOICE, DAYS, DAYS_EOM }

    public record Term(String name, String meaning) {}

    private record Stored(String name, Method method, int days) {}

    private final JdbcTemplate jdbc;

    /** Every term to choose from (once each, ignoring case), A-Z, each with what it means for the due date. */
    @Transactional(readOnly = true)
    public List<Term> list() {
        Long t = TenantContext.get();
        Map<String, Term> byKey = new LinkedHashMap<>();
        for (Stored s : stored(t)) byKey.put(key(s.name()), new Term(s.name(), describe(s)));
        // Most-used spelling first, so "30 Day EOM" wins over a stray "30 day eom"
        List<String> used = jdbc.queryForList(
                "SELECT name FROM (SELECT trim(payment_term) AS name FROM customers WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM suppliers WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM sale_orders WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM purchase_orders WHERE tenant_id = ? AND payment_term IS NOT NULL) u"
                        + " WHERE name <> '' GROUP BY name ORDER BY count(*) DESC, name",
                String.class, t, t, t, t);
        for (String name : used.isEmpty() ? STANDARD_TERMS : used) {
            if (name != null && !name.isBlank()) byKey.putIfAbsent(key(name), new Term(name, PaymentTerms.describe(name)));
        }
        List<Term> terms = new ArrayList<>(byKey.values());
        terms.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        return terms;
    }

    /** Adds a term to the list. The name must be new (ignoring case); days are needed unless it is due on the invoice date. */
    @Transactional
    public Term add(String name, Method method, Integer days) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Enter the name of the payment term");
        if (n.length() > 100) throw new IllegalArgumentException("The name is too long (100 characters at most)");
        if (method == null) throw new IllegalArgumentException("Choose how the due date is worked out");
        int d = method == Method.ON_INVOICE ? 0 : days == null ? -1 : days;
        if (d < 0 || d > 365) throw new IllegalArgumentException("Enter the number of days (0 to 365)");
        if (list().stream().anyMatch(t -> key(t.name()).equals(key(n)))) {
            throw new IllegalArgumentException("There is already a payment term called " + n);
        }
        jdbc.update("INSERT INTO payment_terms (tenant_id, name, method, days) VALUES (?, ?, ?, ?)", TenantContext.get(), n, method.name(), d);
        return new Term(n, describe(new Stored(n, method, d)));
    }

    /** Due date of an invoice dated {@code invoiceDate} on these terms. */
    @Transactional(readOnly = true)
    public LocalDate dueDate(String term, LocalDate invoiceDate) {
        if (term == null || term.isBlank()) return invoiceDate;
        Long t = TenantContext.get();
        if (t != null) {
            for (Stored s : stored(t)) {
                if (!key(s.name()).equals(key(term))) continue;
                return switch (s.method()) {
                    case ON_INVOICE -> invoiceDate;
                    case DAYS -> invoiceDate.plusDays(s.days());
                    case DAYS_EOM -> invoiceDate.withDayOfMonth(invoiceDate.lengthOfMonth()).plusDays(s.days());
                };
            }
        }
        return PaymentTerms.dueDate(term, invoiceDate);
    }

    private List<Stored> stored(Long tenantId) {
        return jdbc.query("SELECT name, method, days FROM payment_terms WHERE tenant_id = ? ORDER BY lower(name)",
                (rs, i) -> new Stored(rs.getString("name"), Method.valueOf(rs.getString("method")), rs.getInt("days")), tenantId);
    }

    private static String describe(Stored s) {
        return switch (s.method()) {
            case ON_INVOICE -> "Due on the invoice date";
            case DAYS -> s.days() + " days after the invoice date";
            case DAYS_EOM -> s.days() + " days after the end of the invoice month";
        };
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
