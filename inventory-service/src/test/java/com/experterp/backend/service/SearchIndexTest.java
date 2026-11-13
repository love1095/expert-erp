package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review R1: every column a "contains" search looks in has a trigram index Postgres can use, and the searches are
 * written so that it does (no coalesce() around a searched column).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SearchIndexTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    // table, the expression exactly as the searches write it
    static final String[][] SEARCHED = {
            {"sale_orders", "lower(order_number)"}, {"sale_orders", "lower(customer_name)"},
            {"sale_orders", "lower(customer_reference)"}, {"sale_orders", "lower(external_reference)"},
            {"invoices", "lower(invoice_number)"}, {"invoices", "lower(party_name)"},
            {"purchase_orders", "lower(order_number)"}, {"purchase_orders", "lower(supplier_name)"},
            {"purchase_orders", "lower(supplier_reference)"},
            {"products", "lower(sku)"}, {"products", "lower(name)"}, {"products", "lower(barcode)"},
            {"products", "lower(supplier_product_code)"},
            {"customers", "lower(name)"},
            {"customer_contacts", "lower(name)"}, {"customer_contacts", "lower(email)"},
            {"customer_contacts", "phone"}, {"customer_contacts", "mobile_phone"},
            {"suppliers", "lower(name)"}, {"suppliers", "lower(contact_name)"}, {"suppliers", "lower(email)"},
            {"tenants", "lower(name)"}, {"tenants", "lower(slug)"},
    };

    @Test
    void everySearchedColumnHasATrigramIndexOnTheExpressionTheSearchesUse() {
        // Read from the catalogue, not from a plan: on the tiny test tables Postgres may rightly prefer another index
        List<String> notIndexed = new ArrayList<>();
        for (String[] s : SEARCHED) {
            boolean lower = s[1].startsWith("lower(");
            String column = lower ? s[1].substring(6, s[1].length() - 1) : s[1];
            // e.g. "USING gin (lower((order_number)::text) gin_trgm_ops)" or "USING gin (phone gin_trgm_ops)"
            Pattern on = Pattern.compile(lower
                    ? "gin \\(lower\\(\\(?" + column + "\\)?(::text)?\\) gin_trgm_ops\\)"
                    : "gin \\(" + column + " gin_trgm_ops\\)");
            List<String> defs = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ?", String.class, s[0]);
            if (defs.stream().noneMatch(d -> on.matcher(d).find())) notIndexed.add(s[0] + "." + s[1]);
        }
        assertThat(notIndexed).as("searched columns without a trigram index on the searched expression").isEmpty();
    }

    @Test
    void anOrOfSearchedColumnsUsesAllTheirIndexes() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            jdbc.execute("SET LOCAL enable_indexscan = off");
            String plan = String.join("\n", jdbc.queryForList("""
                    EXPLAIN SELECT id FROM sale_orders WHERE lower(order_number) LIKE '%abcdef%' OR lower(customer_name) LIKE '%abcdef%'
                      OR lower(customer_reference) LIKE '%abcdef%' OR lower(external_reference) LIKE '%abcdef%'""", String.class));
            assertThat(plan).contains("BitmapOr", "idx_sale_orders_number_trgm", "idx_sale_orders_customer_trgm",
                    "idx_sale_orders_customer_ref_trgm", "idx_sale_orders_external_ref_trgm");
            status.setRollbackOnly();
        });
    }

    @Test
    void noSearchWrapsASearchedColumnInCoalesce() throws IOException {
        // lower(coalesce(col, '')) LIKE ... cannot use the index on lower(col); a NULL never matches a LIKE anyway.
        // (Sorting may keep coalesce: ORDER BY lines are left out.)
        Pattern wrapped = Pattern.compile("(?i)coalesce\\([^)]*\\)\\s*\\)?\\s*LIKE");
        List<String> found = new ArrayList<>();
        for (String dir : List.of("repository", "service")) {
            try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/experterp/backend", dir))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).strip();
                        if (!line.toUpperCase().startsWith("ORDER BY") && wrapped.matcher(line).find()) {
                            found.add(f.getFileName() + ":" + (i + 1) + " " + line);
                        }
                    }
                }
            }
        }
        assertThat(found).as("searches with coalesce() around the searched column").isEmpty();
    }
}
