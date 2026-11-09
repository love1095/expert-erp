package com.experterp.backend.controller;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.PaymentTerms;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Short pick lists for forms (names only, so any signed-in user may read them). */
@RestController
@RequestMapping("/api/lists")
@RequiredArgsConstructor
public class ListsController {

    // Offered to an organisation that has no terms yet (Cin7's usual ones)
    static final List<String> STANDARD_TERMS = List.of("Cash", "7 Days", "14 Days", "30 Days", "30 Day EOM", "60 Day EOM");

    private final JdbcTemplate jdbc;

    public record Term(String name, String meaning) {}

    /**
     * The payment terms to choose from, like Cin7's terms list: every term already used on customers, suppliers and
     * orders (once each, ignoring case), A-Z, each with what it means for the due date.
     */
    @GetMapping("/payment-terms")
    @Transactional(readOnly = true)
    public List<Term> paymentTerms() {
        Long t = TenantContext.get();
        // Most-used spelling first, so "30 Day EOM" wins over a stray "30 day eom"
        List<String> used = jdbc.queryForList(
                "SELECT name FROM (SELECT trim(payment_term) AS name FROM customers WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM suppliers WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM sale_orders WHERE tenant_id = ? AND payment_term IS NOT NULL"
                        + " UNION ALL SELECT trim(payment_term) FROM purchase_orders WHERE tenant_id = ? AND payment_term IS NOT NULL) u"
                        + " WHERE name <> '' GROUP BY name ORDER BY count(*) DESC, name",
                String.class, t, t, t, t);
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String name : used.isEmpty() || used.stream().allMatch(String::isBlank) ? STANDARD_TERMS : used) {
            if (name != null && !name.isBlank()) byKey.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        List<String> names = new ArrayList<>(byKey.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names.stream().map(n -> new Term(n, PaymentTerms.describe(n))).toList();
    }
}
