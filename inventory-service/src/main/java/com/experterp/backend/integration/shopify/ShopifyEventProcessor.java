package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyWebhookEvent;
import com.experterp.backend.entity.ShopifyWebhookEvent.Status;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.ShopifyWebhookEventRepository;
import com.experterp.backend.service.AuditActorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs stored Shopify webhook events (7.5). The webhook is answered as soon as the event is stored; this runs it
 * in the background. A failed event is tried again later, each wait longer than the last
 * ({@link #RETRY_DELAYS}), and given up after {@link #MAX_ATTEMPTS}; people can retry it from the Audit Log.
 * Each run first takes the event (locked_until), so two workers or a worker and a Retry never run it together.
 */
@Slf4j
@Service
public class ShopifyEventProcessor {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4), Duration.ofHours(12), Duration.ofHours(24));
    static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;
    // A run that takes longer is assumed dead (backend stopped) and may be taken again
    private static final Duration LOCK = Duration.ofMinutes(5);
    // A RECEIVED event older than this was never run (backend stopped, queue full): the worker runs it
    static final Duration STRANDED = Duration.ofMinutes(2);

    static final java.util.Set<String> ORDER_TOPICS = ShopifyWebhookService.ORDER_TOPICS;

    private final TaskExecutor executor;
    private final JdbcTemplate jdbc;
    private final ShopifyWebhookEventRepository eventRepository;
    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyOrderImporter importer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ShopifyEventProcessor(@Qualifier("shopifyEventExecutor") TaskExecutor executor, JdbcTemplate jdbc,
                                 ShopifyWebhookEventRepository eventRepository, ShopifyConnectionRepository connectionRepository,
                                 ShopifyOrderImporter importer, ObjectMapper objectMapper, TransactionTemplate tx) {
        this.executor = executor;
        this.jdbc = jdbc;
        this.eventRepository = eventRepository;
        this.connectionRepository = connectionRepository;
        this.importer = importer;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    /** Runs a just-stored event in the background; if the queue is full, the worker picks it up shortly. */
    public void submit(Long tenantId, Long eventPk) {
        try {
            executor.execute(() -> run(tenantId, eventPk));
        } catch (TaskRejectedException full) {
            log.warn("Shopify event {} queued for the worker: {}", eventPk, full.getMessage());
        }
    }

    /** A person pressed Retry: runs the event now, whatever its wait, and returns it as it ended. */
    public ShopifyWebhookEvent retryNow(Long eventPk) {
        ShopifyWebhookEvent e = eventRepository.findById(eventPk)
                .orElseThrow(() -> new com.experterp.backend.exception.ResourceNotFoundException("Shopify event not found: " + eventPk));
        if (e.getStatus() == Status.PROCESSED || e.getStatus() == Status.IGNORED) {
            throw new IllegalStateException("This event was already handled");
        }
        if (!run(e.getTenantId(), eventPk)) throw new IllegalStateException("The event is being run right now; look again in a moment");
        // run() writes with plain SQL: read the event again, not the copy this request already holds
        return tx.execute(s -> {
            ShopifyWebhookEvent fresh = eventRepository.findById(eventPk).orElseThrow();
            entityManager.refresh(fresh);
            return fresh;
        });
    }

    /** Failed events whose wait is over, and received ones never run: runs each (the scheduler calls this). */
    public int runDue() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, tenant_id FROM shopify_webhook_events
                WHERE ((status = 'FAILED' AND next_attempt_at <= now()) OR (status = 'RECEIVED' AND received_at < ?))
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY received_at LIMIT 100""", Timestamp.valueOf(LocalDateTime.now().minus(STRANDED)));
        int ran = 0;
        for (Map<String, Object> row : due) {
            if (run(((Number) row.get("tenant_id")).longValue(), ((Number) row.get("id")).longValue())) ran++;
        }
        return ran;
    }

    /** Takes the event, runs it as its organisation, records the outcome. False when someone else has it. */
    boolean run(Long tenantId, Long eventPk) {
        LocalDateTime now = LocalDateTime.now();
        List<Integer> taken = jdbc.queryForList("""
                UPDATE shopify_webhook_events SET locked_until = ?, attempts = attempts + 1
                WHERE id = ? AND status IN ('RECEIVED', 'FAILED') AND (locked_until IS NULL OR locked_until < ?)
                RETURNING attempts""", Integer.class, Timestamp.valueOf(now.plus(LOCK)), eventPk, Timestamp.valueOf(now));
        if (taken.isEmpty()) return false;
        int attempt = taken.get(0);

        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        Status outcome;
        String error = null;
        try {
            ShopifyWebhookEvent event = tx.execute(s -> eventRepository.findById(eventPk).orElseThrow());
            outcome = AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> handle(event));
        } catch (RuntimeException e) {
            outcome = Status.FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }

        LocalDateTime next = null;
        if (outcome == Status.FAILED) {
            if (attempt < MAX_ATTEMPTS) {
                next = LocalDateTime.now().plus(RETRY_DELAYS.get(attempt - 1));
                log.warn("Shopify event {} failed (attempt {}), next try {}: {}", eventPk, attempt, next, error);
            } else {
                error = error + " (gave up after " + attempt + " attempts)";
                log.error("Shopify event {} failed {} times, given up: {}", eventPk, attempt, error);
            }
        }
        jdbc.update("""
                UPDATE shopify_webhook_events SET status = ?, error = ?, processed_at = ?, next_attempt_at = ?, locked_until = NULL
                WHERE id = ?""", outcome.name(), error, Timestamp.valueOf(LocalDateTime.now()),
                next == null ? null : Timestamp.valueOf(next), eventPk);
        return true;
    }

    private Status handle(ShopifyWebhookEvent event) {
        if (ORDER_TOPICS.contains(event.getTopic())) {
            try {
                importer.importOrder(ShopifyOrder.fromWebhook(objectMapper.readTree(event.getPayload())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Webhook body is not valid JSON");
            }
            return Status.PROCESSED;
        }
        if ("app/uninstalled".equals(event.getTopic())) {
            tx.executeWithoutResult(s -> connectionRepository.findFirstByOrderByIdAsc().ifPresent(c -> {
                c.setStatus(ShopifyConnection.Status.ERROR);
                c.setLastError("The Expert ERP app was uninstalled from the Shopify store. Install it again, then test the connection.");
                c.setAccessTokenEncrypted(null);
                c.setTokenExpiresAt(null);
                c.setWebhooksRegisteredAt(null);
                c.setWebhookTopics(null);
            }));
            return Status.PROCESSED;
        }
        return Status.IGNORED;
    }
}
