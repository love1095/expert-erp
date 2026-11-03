package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The daily check against Shopify (7.5), for anything webhooks missed: webhook subscriptions Shopify removed are
 * made again, and orders created or changed in Shopify since the last check are imported (the import is safe to
 * repeat, so an order already here is left as it is). Runs for every connected organisation, or one on request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopifyReconciler {

    // The first check, and one after a long gap, looks back this far (Shopify keeps 60 days without read_all_orders)
    static final int FIRST_DAYS = 2;
    static final int MAX_DAYS = 60;
    // Overlap with the previous check, so an order changed while it ran is not missed
    private static final int OVERLAP_MINUTES = 60;

    public record Result(LocalDateTime checkedAt, String summary, String error, List<String> problems) {}

    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyOrderSyncService syncService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** Every organisation with a working Shopify connection (the scheduler calls this). */
    public int reconcileAll() {
        List<Long> tenants = jdbc.queryForList("SELECT tenant_id FROM shopify_connections WHERE status = 'CONNECTED'", Long.class);
        for (Long tenantId : tenants) {
            Long previous = TenantContext.get();
            TenantContext.set(tenantId);
            try {
                reconcile();
            } catch (RuntimeException e) {
                log.warn("Shopify check for organisation {} failed: {}", tenantId, e.getMessage());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
            }
        }
        return tenants.size();
    }

    /** The current organisation's check; the outcome is kept on the connection and shown on the Shopify page. */
    public Result reconcile() {
        ShopifyConnection c = tx.execute(s -> connectionRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected")));
        LocalDateTime started = LocalDateTime.now();
        List<String> parts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String error = null;
        try {
            if (c.getPublicUrl() != null && c.getWebhooksRegisteredAt() != null) {
                List<String> missing = syncService.missingWebhookTopics();
                if (!missing.isEmpty()) {
                    syncService.registerWebhooks();
                    parts.add("webhooks made again (" + String.join(", ", missing) + " were missing)");
                } else {
                    parts.add("webhooks OK");
                }
            }
            LocalDateTime since = since(c.getLastReconciledAt(), started);
            ShopifyOrderSyncService.ImportResult r = syncService.importChangedSince(since);
            parts.add(r.fetched() + " orders changed since the last check: " + r.created() + " new, " + r.updated() + " updated");
            problems.addAll(r.problems());
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String summary = String.join("; ", parts);
        String err = error;
        tx.executeWithoutResult(s -> connectionRepository.findById(c.getId()).ifPresent(fresh -> {
            // A failed check does not move the start of the next one forward
            if (err == null) fresh.setLastReconciledAt(started);
            fresh.setLastReconcileSummary(summary.isEmpty() ? null : summary);
            fresh.setLastReconcileError(err);
            auditService.log("SHOPIFY", fresh.getId(), "RECONCILE", Map.of("summary", summary, "error", String.valueOf(err)));
        }));
        return new Result(started, summary, error, problems);
    }

    static LocalDateTime since(LocalDateTime last, LocalDateTime now) {
        if (last == null) return now.minusDays(FIRST_DAYS);
        LocalDateTime from = last.minusMinutes(OVERLAP_MINUTES);
        return from.isBefore(now.minusDays(MAX_DAYS)) ? now.minusDays(MAX_DAYS) : from;
    }
}
