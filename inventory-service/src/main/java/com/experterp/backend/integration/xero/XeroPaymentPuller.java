package com.experterp.backend.integration.xero;

import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.integration.xero.XeroApi.Payment;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.AccountingExportService;
import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.InvoiceService;
import com.experterp.backend.service.SalesLedgerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Phase L4: payments entered in Xero come back into the ERP. Polled (every 5 minutes, or at once when a Xero webhook
 * says something changed) with Xero's If-Modified-Since; each payment is recorded against its ERP invoice, bill or
 * credit note (refund) — found through the queue's mapping, or by its number — into the payment account with the same
 * code, and marked as already in Xero so it is never sent back. Payments the ERP sent itself are skipped; a payment
 * deleted in Xero voids the one recorded here; a payment whose document is not found is listed for a person.
 */
@Slf4j
@Service
public class XeroPaymentPuller {

    private final JdbcTemplate jdbc;
    private final XeroClient xero;
    private final XeroConnectionService connection;
    private final InvoiceService invoiceService;
    private final SalesLedgerService salesLedger;
    private final AccountingExportService export;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public XeroPaymentPuller(JdbcTemplate jdbc, XeroClient xero, XeroConnectionService connection, InvoiceService invoiceService,
                             SalesLedgerService salesLedger, AccountingExportService export, AuditService auditService,
                             PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.xero = xero;
        this.connection = connection;
        this.invoiceService = invoiceService;
        this.salesLedger = salesLedger;
        this.export = export;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(txManager);
    }

    public record Pulled(Long id, String xeroPaymentId, String xeroStatus, String docKey, String number, Long erpPaymentId, BigDecimal amount,
                         LocalDate paidOn, String status, String message, LocalDateTime pulledAt) {}

    public record PullResult(int recorded, int voided, int unmatched, int errors, int skipped) {}

    /** Every organisation on the API link: when a webhook asked, or when the polling time has come. */
    public int pullAll(boolean polling) {
        List<Long> tenants = jdbc.queryForList("""
                SELECT c.tenant_id FROM xero_connections c JOIN accounting_settings s ON s.tenant_id = c.tenant_id
                 WHERE c.status = 'CONNECTED' AND s.xero_link = 'API' AND (? OR c.pull_requested_at IS NOT NULL)""", Long.class, polling);
        int n = 0;
        for (Long t : tenants) {
            Long previous = TenantContext.get();
            TenantContext.set(t);
            try {
                n += pull().recorded();
            } catch (RuntimeException e) {
                log.warn("Xero payments for organisation {}: {}", t, e.getMessage());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
            }
        }
        return n;
    }

    /** Pulls the current organisation's payments changed in Xero since the last pull. */
    public PullResult pull() {
        if (!"API".equals(export.xeroLink())) return new PullResult(0, 0, 0, 0, 0);
        Long t = TenantContext.get();
        Timestamp last = jdbc.queryForObject("SELECT payments_pulled_at FROM xero_connections WHERE tenant_id = ?", Timestamp.class, t);
        OffsetDateTime since = last == null ? null : last.toLocalDateTime().atOffset(ZoneOffset.UTC).minusMinutes(1);
        Session s = connection.session(false);
        List<Payment> payments;
        try {
            payments = xero.payments(s, since);
        } catch (XeroException e) {
            if (e.kind() != XeroException.Kind.UNAUTHORIZED) throw e;
            payments = xero.payments(connection.session(true), since);
        }
        LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC);
        int[] c = new int[5];
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        // Scheduled runs have no login: they act as "Xero" (named in the audit log)
        if (before == null) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("Xero", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            for (Payment p : payments) {
                String r = tx.execute(st -> one(t, p));
                switch (r == null ? "" : r) {
                    case "RECORDED" -> c[0]++;
                    case "VOIDED" -> c[1]++;
                    case "UNMATCHED" -> c[2]++;
                    case "ERROR" -> c[3]++;
                    default -> c[4]++;
                }
            }
        } finally {
            if (before == null) SecurityContextHolder.clearContext();
        }
        jdbc.update("UPDATE xero_connections SET payments_pulled_at = ?, pull_requested_at = NULL WHERE tenant_id = ?", Timestamp.valueOf(started), t);
        if (c[0] + c[1] + c[2] + c[3] > 0) {
            auditService.log("XERO", null, "PAYMENTS_PULLED", Map.of("recorded", c[0], "voided", c[1], "unmatched", c[2], "errors", c[3]));
        }
        return new PullResult(c[0], c[1], c[2], c[3], c[4]);
    }

    private String one(Long t, Payment p) {
        // Sent from here: Xero has it from us
        Boolean ours = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM xero_sync_items WHERE tenant_id = ? AND kind = 'PAYMENT' AND xero_id = ?)",
                Boolean.class, t, p.paymentID());
        List<Map<String, Object>> seen = jdbc.queryForList("SELECT * FROM xero_pulled_payments WHERE tenant_id = ? AND xero_payment_id = ?", t, p.paymentID());
        boolean deleted = "DELETED".equals(p.status());
        if (Boolean.TRUE.equals(ours) && seen.isEmpty()) return "SKIPPED";
        if (!seen.isEmpty()) {
            Map<String, Object> row = seen.get(0);
            if (deleted && "RECORDED".equals(row.get("status")) && row.get("erp_payment_id") != null) {
                Long docId = Long.valueOf(((String) row.get("doc_key")).substring(4));
                try {
                    salesLedger.voidPayment(docId, ((Number) row.get("erp_payment_id")).longValue(), "Deleted in Xero");
                    jdbc.update("UPDATE xero_pulled_payments SET status = 'VOIDED', xero_status = 'DELETED', message = NULL, updated_at = now() WHERE id = ?", row.get("id"));
                    return "VOIDED";
                } catch (RuntimeException e) {
                    jdbc.update("UPDATE xero_pulled_payments SET xero_status = 'DELETED', message = ?, updated_at = now() WHERE id = ?",
                            "Deleted in Xero, not voided here: " + e.getMessage(), row.get("id"));
                    return "ERROR";
                }
            }
            return "SKIPPED";
        }
        if (deleted) return "SKIPPED";

        // Which ERP document: through the queue's mapping, else by number
        boolean refund = p.creditNote() != null;
        String xeroDoc = refund ? p.creditNote().creditNoteID() : p.invoice() == null ? null : p.invoice().invoiceID();
        String number = refund ? p.creditNote().creditNoteNumber() : p.invoice() == null ? null : p.invoice().invoiceNumber();
        Long docId = null;
        if (xeroDoc != null) {
            List<String> k = jdbc.queryForList("SELECT item_key FROM xero_sync_items WHERE tenant_id = ? AND item_key LIKE 'DOC:%' AND xero_id = ?", String.class, t, xeroDoc);
            if (!k.isEmpty()) docId = Long.valueOf(k.get(0).substring(4));
        }
        if (docId == null && number != null) {
            List<Long> d = jdbc.queryForList("SELECT id FROM invoices WHERE tenant_id = ? AND invoice_number = ? AND status IN ('ISSUED', 'PAID') ORDER BY id DESC LIMIT 1",
                    Long.class, t, number);
            if (!d.isEmpty()) docId = d.get(0);
        }
        LocalDate day = date(p.date());
        if (docId == null) {
            save(t, p, null, null, day, "UNMATCHED", "No invoice, bill or credit note here for this Xero payment" + (number == null ? "" : " (" + number + ")"));
            return "UNMATCHED";
        }
        try {
            PaymentRequest req = new PaymentRequest();
            req.setAmount(p.amount());
            req.setPaidOn(day);
            req.setReference(cut("Xero" + (p.reference() == null || p.reference().isBlank() ? "" : ": " + p.reference()), 100));
            if (p.account() != null && p.account().code() != null) {
                List<Long> acc = jdbc.queryForList("SELECT id FROM payment_accounts WHERE tenant_id = ? AND lower(code) = lower(?)", Long.class, t, p.account().code());
                if (acc.isEmpty()) throw new IllegalArgumentException("Xero's bank account " + p.account().code() + " is not a payment account here");
                req.setAccountId(acc.get(0));
            }
            invoiceService.addPayment(docId, req);
            Long erpPayment = jdbc.queryForObject("SELECT MAX(id) FROM payments WHERE invoice_id = ?", Long.class, docId);
            // Xero has it already: never sent back (queue item SENT, one record with the CSV export)
            jdbc.update("""
                    INSERT INTO xero_sync_items (tenant_id, item_key, kind, label, xero_id, status, attempts, sent_at, doc_date, amount, last_error)
                    VALUES (?, ?, 'PAYMENT', ?, ?, 'SENT', 0, now(), ?, ?, 'Entered in Xero') ON CONFLICT (tenant_id, item_key) DO NOTHING""",
                    t, "PAY:" + erpPayment, "Payment from Xero", p.paymentID(), Date.valueOf(day), p.amount());
            export.recordSentByApi("PAY:" + erpPayment, day, p.amount());
            save(t, p, "DOC:" + docId, erpPayment, day, "RECORDED", null);
            return "RECORDED";
        } catch (RuntimeException e) {
            save(t, p, "DOC:" + docId, null, day, "ERROR", e.getMessage());
            return "ERROR";
        }
    }

    private void save(Long t, Payment p, String docKey, Long erpPayment, LocalDate day, String status, String message) {
        jdbc.update("""
                INSERT INTO xero_pulled_payments (tenant_id, xero_payment_id, xero_status, doc_key, erp_payment_id, amount, paid_on, status, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, xero_payment_id) DO UPDATE SET status = EXCLUDED.status,
                    message = EXCLUDED.message, doc_key = EXCLUDED.doc_key, erp_payment_id = EXCLUDED.erp_payment_id, updated_at = now()""",
                t, p.paymentID(), p.status(), docKey, erpPayment, p.amount(), day == null ? null : Date.valueOf(day), status, cut(message, 2000));
    }

    /** What came back from Xero (newest first). */
    public List<Pulled> pulled(int limit) {
        return jdbc.query("""
                SELECT x.*, i.invoice_number FROM xero_pulled_payments x
                  LEFT JOIN invoices i ON x.doc_key = 'DOC:' || i.id
                 WHERE x.tenant_id = ? ORDER BY x.pulled_at DESC, x.id DESC LIMIT ?""", (rs, n) -> new Pulled(rs.getLong("id"),
                rs.getString("xero_payment_id"), rs.getString("xero_status"), rs.getString("doc_key"), rs.getString("invoice_number"),
                rs.getObject("erp_payment_id") == null ? null : rs.getLong("erp_payment_id"), rs.getBigDecimal("amount"),
                rs.getDate("paid_on") == null ? null : rs.getDate("paid_on").toLocalDate(), rs.getString("status"), rs.getString("message"),
                rs.getTimestamp("pulled_at").toLocalDateTime()), TenantContext.get(), Math.max(1, Math.min(limit, 500)));
    }

    /** A Xero webhook (live) for this Xero organisation: pull at the next run. Returns whether the organisation is known. */
    public boolean requestPull(String xeroTenantId) {
        return jdbc.update("UPDATE xero_connections SET pull_requested_at = now() WHERE xero_tenant_id = ? AND status = 'CONNECTED'", xeroTenantId) > 0;
    }

    private static LocalDate date(String s) {
        if (s == null) return LocalDate.now();
        // Xero answers "/Date(1730764800000+0000)/" in its JSON; ISO dates from the fake
        if (s.startsWith("/Date(")) {
            long ms = Long.parseLong(s.substring(6).replaceAll("[^0-9].*$", ""));
            return java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
