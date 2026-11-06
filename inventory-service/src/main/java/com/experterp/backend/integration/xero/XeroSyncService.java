package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase L2: the Xero sync queue. Each ERP item to send has one row (its key, kind, what it waits for, its Xero ID
 * once sent, status, attempts, last error). The queue sends what is due, contacts first, each item in its own
 * transaction with its row locked (two runs never take the same item):
 * <ul>
 *   <li>sent → SENT with the Xero ID; a SENT item is never sent again;</li>
 *   <li>Xero's rate limit → waits Retry-After; Xero down → backs off 1, 2, 4 … 60 minutes, FAILED after 8 tries;</li>
 *   <li>expired token → refreshed and the item tried again at once;</li>
 *   <li>Xero refused it (validation) → FAILED at once: a person fixes the cause and presses Retry.</li>
 * </ul>
 * Every call carries the item's key as Xero's Idempotency-Key.
 */
@Slf4j
@Service
public class XeroSyncService {

    static final int MAX_ATTEMPTS = 8;
    private static final List<String> ORDER = List.of("CONTACT", "INVOICE", "BILL", "CREDIT_NOTE", "SUPPLIER_CREDIT", "PAYMENT", "ALLOCATION", "JOURNAL");

    private final JdbcTemplate jdbc;
    private final XeroConnectionService connection;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final Map<String, XeroItemSender> senders = new HashMap<>();
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public XeroSyncService(JdbcTemplate jdbc, XeroConnectionService connection, AuditService auditService,
                           PlatformTransactionManager txManager, List<XeroItemSender> senders) {
        this.jdbc = jdbc;
        this.connection = connection;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(txManager);
        senders.forEach(s -> s.kinds().forEach(k -> this.senders.put(k, s)));
    }

    public record Item(Long id, String key, String kind, String label, String dependsOn, String xeroId, String status, int attempts,
                       String lastError, LocalDateTime nextAttemptAt, LocalDateTime createdAt, LocalDateTime sentAt) {}

    public record RunResult(int sent, int failed, int waiting) {}

    /** Puts an item on the queue once (an item already queued, sent or not, is left as it is). */
    @Transactional
    public void enqueue(String key, String kind, String label, String dependsOn) {
        jdbc.update("""
                INSERT INTO xero_sync_items (tenant_id, item_key, kind, label, depends_on) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, item_key) DO NOTHING""", TenantContext.get(), key, kind, cut(label, 255), dependsOn);
    }

    /** The Xero ID of an item already sent (e.g. the contact of an invoice), or null. */
    @Transactional(readOnly = true)
    public String xeroId(String key) {
        List<String> r = jdbc.queryForList("SELECT xero_id FROM xero_sync_items WHERE tenant_id = ? AND item_key = ? AND status = 'SENT'",
                String.class, TenantContext.get(), key);
        return r.isEmpty() ? null : r.get(0);
    }

    @Transactional(readOnly = true)
    public List<Item> items(String status, int limit) {
        return jdbc.query("""
                SELECT * FROM xero_sync_items WHERE tenant_id = ? AND (?::varchar IS NULL OR status = ?::varchar)
                 ORDER BY CASE status WHEN 'FAILED' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, updated_at DESC, id DESC LIMIT ?""",
                (rs, n) -> new Item(rs.getLong("id"), rs.getString("item_key"), rs.getString("kind"), rs.getString("label"), rs.getString("depends_on"),
                        rs.getString("xero_id"), rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"),
                        rs.getTimestamp("next_attempt_at").toLocalDateTime(), rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toLocalDateTime()),
                TenantContext.get(), status, status, Math.max(1, Math.min(limit, 1000)));
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>(Map.of("PENDING", 0, "SENT", 0, "FAILED", 0));
        jdbc.query("SELECT status, COUNT(*) FROM xero_sync_items WHERE tenant_id = ? GROUP BY status", rs -> { m.put(rs.getString(1), rs.getInt(2)); }, TenantContext.get());
        return m;
    }

    /** Retry: a failed item goes back on the queue, due now, with its attempts counted afresh. */
    @Transactional
    public void retry(Long id) {
        int n = jdbc.update("""
                UPDATE xero_sync_items SET status = 'PENDING', attempts = 0, next_attempt_at = now(), updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'FAILED'""", TenantContext.get(), id);
        if (n == 0) throw new IllegalStateException("Only a failed item can be retried");
        auditService.log("XERO", id, "RETRY", Map.of());
    }

    /** Every organisation with something due (the background timer). */
    public int processAll() {
        List<Long> tenants = jdbc.queryForList("""
                SELECT DISTINCT i.tenant_id FROM xero_sync_items i JOIN xero_connections c ON c.tenant_id = i.tenant_id
                 WHERE i.status = 'PENDING' AND i.next_attempt_at <= now() AND c.status = 'CONNECTED'""", Long.class);
        int sent = 0;
        for (Long t : tenants) {
            Long previous = TenantContext.get();
            TenantContext.set(t);
            try {
                sent += process().sent();
            } catch (RuntimeException e) {
                log.warn("Xero sync for organisation {} stopped: {}", t, e.getMessage());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
            }
        }
        return sent;
    }

    /** Sends what is due for the current organisation; one run at a time per organisation. */
    public RunResult process() {
        Long t = TenantContext.get();
        ReentrantLock lock = locks.computeIfAbsent(t, k -> new ReentrantLock());
        if (!lock.tryLock()) return new RunResult(0, 0, 0);
        try {
            int sent = 0, failed = 0, waiting = 0;
            Session[] session = {null};
            for (int pass = 0; pass < ORDER.size(); pass++) {
                List<Long> due = jdbc.queryForList("""
                        SELECT id FROM xero_sync_items WHERE tenant_id = ? AND status = 'PENDING' AND next_attempt_at <= now()
                         ORDER BY array_position(?::varchar[], kind), id LIMIT 500""", Long.class, t, ORDER.toArray(String[]::new));
                if (due.isEmpty()) break;
                int before = sent + failed;
                for (Long id : due) {
                    String r = tx.execute(st -> one(t, id, session));
                    if ("SENT".equals(r)) sent++; else if ("FAILED".equals(r)) failed++; else if ("WAITING".equals(r)) waiting++;
                }
                // Another pass only while it moves things (an invoice whose contact was just sent)
                if (sent + failed == before) break;
            }
            return new RunResult(sent, failed, waiting);
        } finally {
            lock.unlock();
        }
    }

    // One item, in its own transaction, its row locked
    private String one(Long t, Long id, Session[] session) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM xero_sync_items WHERE tenant_id = ? AND id = ? AND status = 'PENDING' AND next_attempt_at <= now() FOR UPDATE SKIP LOCKED""", t, id);
        if (rows.isEmpty()) return "SKIPPED";
        Map<String, Object> r = rows.get(0);
        String dep = (String) r.get("depends_on");
        if (dep != null) {
            List<String> st = jdbc.queryForList("SELECT status FROM xero_sync_items WHERE tenant_id = ? AND item_key = ?", String.class, t, dep);
            if (st.isEmpty() || !"SENT".equals(st.get(0))) {
                jdbc.update("UPDATE xero_sync_items SET last_error = ?, updated_at = now() WHERE id = ?",
                        "Waiting for " + dep + (st.isEmpty() ? " (not queued)" : st.get(0).equals("FAILED") ? " (failed)" : ""), id);
                return "WAITING";
            }
        }
        Item item = new Item(id, (String) r.get("item_key"), (String) r.get("kind"), (String) r.get("label"), dep, null, "PENDING",
                (Integer) r.get("attempts"), null, null, null, null);
        XeroItemSender sender = senders.get(item.kind());
        if (sender == null) return fail(id, item, "Nothing here sends " + item.kind() + " yet", false, 0);
        for (int tries = 0; tries < 2; tries++) {
            try {
                if (session[0] == null || tries > 0) session[0] = connection.session(tries > 0);
                String xeroId = sender.send(session[0].withKey(idempotencyKey(t, item.key())), item);
                jdbc.update("""
                        UPDATE xero_sync_items SET status = 'SENT', xero_id = ?, attempts = attempts + 1, last_error = NULL, sent_at = now(), updated_at = now()
                         WHERE id = ?""", xeroId, id);
                return "SENT";
            } catch (XeroException e) {
                if (e.kind() == XeroException.Kind.UNAUTHORIZED && tries == 0) continue;   // refresh the token, try once more
                return fail(id, item, e.getMessage(), e.retryable(), e.retryAfterSeconds());
            } catch (IllegalStateException | IllegalArgumentException e) {
                // Not connected / expired connection: retried later; the ERP item itself not sendable: a person decides
                boolean connectionProblem = e.getMessage() != null && e.getMessage().contains("connect");
                return fail(id, item, e.getMessage(), connectionProblem, 0);
            } catch (RuntimeException e) {
                log.warn("Xero item {} failed", item.key(), e);
                return fail(id, item, "Could not be sent: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), false, 0);
            }
        }
        return "SKIPPED";
    }

    private String fail(Long id, Item item, String error, boolean retryable, long retryAfter) {
        int attempts = item.attempts() + 1;
        boolean giveUp = !retryable || attempts >= MAX_ATTEMPTS;
        long waitSeconds = retryAfter > 0 ? retryAfter : Math.min(3600, 60L << Math.min(attempts - 1, 6));
        jdbc.update("""
                UPDATE xero_sync_items SET status = ?, attempts = ?, last_error = ?, next_attempt_at = ?, updated_at = now() WHERE id = ?""",
                giveUp ? "FAILED" : "PENDING", attempts, cut(error, 2000), Timestamp.valueOf(LocalDateTime.now().plusSeconds(giveUp ? 0 : waitSeconds)), id);
        return giveUp ? "FAILED" : "RETRY";
    }

    // Unique per organisation and item: the same item always carries the same key
    private static String idempotencyKey(Long tenantId, String itemKey) {
        return UUID.nameUUIDFromBytes(("expert-erp:" + tenantId + ":" + itemKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
