package com.experterp.backend.integration.xero;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.AccountingExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Phase L3: fills the Xero queue from the CSV export's own list of what is ready (H5): the same export start date,
 * the same documents, stock journals and payments, nothing already exported or sent. Each document waits for its
 * contact, each payment for its document; credit notes applied to invoices / bills are queued as allocations. Only
 * for an organisation whose Xero link is the API.
 */
@Component
@RequiredArgsConstructor
public class XeroEnqueuer {

    private final JdbcTemplate jdbc;
    private final AccountingExportService export;

    /** Queues what is new; returns how many items were added. */
    public int feed() {
        if (!"API".equals(export.xeroLink())) return 0;
        AccountingExportService.Pending pending = export.pending();
        if (pending.startDate() == null) return 0;
        Long t = TenantContext.get();
        Set<String> queued = new HashSet<>(jdbc.queryForList("SELECT item_key FROM xero_sync_items WHERE tenant_id = ?", String.class, t));
        int added = 0;
        for (AccountingExportService.Item i : pending.items()) {
            if (queued.contains(i.key())) continue;
            String label = label(i);
            switch (i.kind()) {
                case "PAYMENT" -> {
                    Long doc = jdbc.queryForObject("SELECT invoice_id FROM payments WHERE id = ?", Long.class, Long.valueOf(i.key().substring(4)));
                    added += add(t, i.key(), "PAYMENT", label, "DOC:" + doc, i.date(), i.amount(), queued);
                }
                case "JOURNAL" -> added += add(t, i.key(), "JOURNAL", label, null, i.date(), i.amount(), queued);
                default -> {
                    long id = Long.parseLong(i.key().substring(4));
                    Map<String, Object> d = jdbc.queryForMap("SELECT customer_id, supplier_id, party_name FROM invoices WHERE id = ?", id);
                    String contact = d.get("customer_id") != null ? XeroContactSender.customerKey(((Number) d.get("customer_id")).longValue())
                            : d.get("supplier_id") != null ? XeroContactSender.supplierKey(((Number) d.get("supplier_id")).longValue())
                            : XeroContactSender.nameKey(Objects.toString(d.get("party_name"), "Unknown contact"));
                    added += add(t, contact, "CONTACT", "Contact " + i.party(), null, null, null, queued);
                    added += add(t, i.key(), i.kind(), label, contact, i.date(), i.amount(), queued);
                }
            }
        }
        // Credit notes applied to invoices / bills since the start date (the CSV files could not carry them)
        List<Map<String, Object>> allocations = jdbc.queryForList("""
                SELECT p.id, p.invoice_id, p.credit_note_id, p.amount, p.paid_at::date AS d, i.invoice_number, cn.invoice_number AS cn_number
                  FROM payments p JOIN invoices i ON i.id = p.invoice_id JOIN invoices cn ON cn.id = p.credit_note_id
                 WHERE p.tenant_id = ? AND p.method = 'CREDIT_NOTE' AND p.credit_note_id IS NOT NULL AND p.paid_at >= ?""",
                t, java.sql.Timestamp.valueOf(pending.startDate().atStartOfDay()));
        for (Map<String, Object> a : allocations) {
            String key = "ALLOC:" + a.get("id");
            if (queued.contains(key)) continue;
            added += add(t, key, "ALLOCATION", "Credit note " + a.get("cn_number") + " applied to " + a.get("invoice_number"),
                    "DOC:" + a.get("credit_note_id") + ",DOC:" + a.get("invoice_id"), ((Date) a.get("d")).toLocalDate(), (BigDecimal) a.get("amount"), queued);
        }
        return added;
    }

    private int add(Long t, String key, String kind, String label, String dependsOn, LocalDate date, BigDecimal amount, Set<String> queued) {
        if (!queued.add(key)) return 0;
        return jdbc.update("""
                INSERT INTO xero_sync_items (tenant_id, item_key, kind, label, depends_on, doc_date, amount) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, item_key) DO NOTHING""", t, key, kind, label.length() > 255 ? label.substring(0, 255) : label, dependsOn,
                date == null ? null : Date.valueOf(date), amount);
    }

    private static String label(AccountingExportService.Item i) {
        String what = switch (i.kind()) {
            case "INVOICE" -> "Invoice";
            case "CREDIT_NOTE" -> "Credit note";
            case "BILL" -> "Bill";
            case "SUPPLIER_CREDIT" -> "Supplier credit";
            case "PAYMENT" -> "Payment";
            default -> "Journal";
        };
        return what + " " + i.number() + (i.party() == null || i.party().isBlank() ? "" : " — " + i.party());
    }
}
