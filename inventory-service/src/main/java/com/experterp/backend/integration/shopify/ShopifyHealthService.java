package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyWebhookEvent;
import com.experterp.backend.entity.ShopifyWebhookEvent.Status;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.ShopifyWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** How the Shopify link is doing (7.5), for the Shopify page: deliveries, failures, the daily check, warnings. */
@Service
@RequiredArgsConstructor
public class ShopifyHealthService {

    static final int CHECK_LATE_HOURS = 36;

    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyWebhookEventRepository eventRepository;
    private final com.experterp.backend.repository.ShopifyRefundRepository refundRepository;

    public record Health(boolean ok, LocalDateTime lastEventAt, long processedLast24h, long failedLast24h,
                         long waitingRetry, long gaveUp, LocalDateTime lastReconciledAt, String lastReconcileSummary,
                         String lastReconcileError, long refundsToReview, List<String> warnings) {}

    @Transactional(readOnly = true)
    public Optional<Health> health() {
        return connectionRepository.findFirstByOrderByIdAsc().map(this::health);
    }

    private Health health(ShopifyConnection c) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastEvent = eventRepository.findFirstByOrderByReceivedAtDesc().map(ShopifyWebhookEvent::getReceivedAt).orElse(null);
        long processed = eventRepository.countByStatusAndReceivedAtAfter(Status.PROCESSED, now.minusHours(24));
        long failed = eventRepository.countByStatusAndReceivedAtAfter(Status.FAILED, now.minusHours(24));
        long waiting = eventRepository.countByStatusAndNextAttemptAtIsNotNull(Status.FAILED);
        long gaveUp = eventRepository.countByStatusAndNextAttemptAtIsNull(Status.FAILED);

        List<String> warnings = new ArrayList<>();
        if (c.getStatus() != ShopifyConnection.Status.CONNECTED) {
            warnings.add("The connection is not working" + (c.getLastError() == null ? "" : ": " + c.getLastError()));
        }
        if (c.getWebhooksRegisteredAt() == null) {
            warnings.add("Webhooks are not registered: new orders only arrive with Import or the daily check");
        }
        if (gaveUp > 0) warnings.add(gaveUp + " webhook event(s) failed for good: see Audit Log → Shopify webhooks and retry them");
        long refunds = refundRepository.countByStatus(com.experterp.backend.entity.ShopifyRefund.Status.TO_REVIEW);
        if (refunds > 0) warnings.add(refunds + " Shopify refund(s) to review: credit or cancel them on their orders");
        if (c.getLastReconcileError() != null) warnings.add("The last daily check failed: " + c.getLastReconcileError());
        if (c.getStatus() == ShopifyConnection.Status.CONNECTED
                && (c.getLastReconciledAt() == null ? c.getCreatedAt().isBefore(now.minusHours(CHECK_LATE_HOURS))
                : c.getLastReconciledAt().isBefore(now.minusHours(CHECK_LATE_HOURS)))) {
            warnings.add("The daily check has not run for more than " + CHECK_LATE_HOURS + " hours");
        }
        return new Health(warnings.isEmpty(), lastEvent, processed, failed, waiting, gaveUp, c.getLastReconciledAt(),
                c.getLastReconcileSummary(), c.getLastReconcileError(), refunds, warnings);
    }
}
