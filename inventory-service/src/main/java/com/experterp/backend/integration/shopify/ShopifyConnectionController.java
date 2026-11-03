package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.ShopifyWebhookEvent;
import com.experterp.backend.repository.ShopifyWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/integrations/shopify")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ShopifyConnectionController {

    private final ShopifyConnectionService service;
    private final ShopifyOrderSyncService syncService;
    private final ShopifyProductSyncService productSyncService;
    private final ShopifyWebhookEventRepository eventRepository;
    private final ShopifyEventProcessor eventProcessor;
    private final ShopifyReconciler reconciler;
    private final ShopifyHealthService healthService;
    private final ShopifyRefundService refundService;
    private final ShopifyStockSync stockSync;

    public record EventView(Long id, String topic, String eventId, ShopifyWebhookEvent.Status status, String error,
                            LocalDateTime receivedAt, LocalDateTime processedAt, int attempts, LocalDateTime nextAttemptAt) {
        static EventView of(ShopifyWebhookEvent e) {
            return new EventView(e.getId(), e.getTopic(), e.getEventId(), e.getStatus(), e.getError(), e.getReceivedAt(),
                    e.getProcessedAt(), e.getAttempts(), e.getNextAttemptAt());
        }
    }

    // 204 when no store is connected yet
    @GetMapping
    public ResponseEntity<ShopifyConnectionService.ConnectionView> get() {
        return service.get().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    // Saves, then tests; the result's status says whether Shopify accepted the credentials
    @PutMapping
    public ShopifyConnectionService.ConnectionView save(@RequestBody ShopifyConnectionService.SaveRequest req) {
        return service.save(req);
    }

    @PostMapping("/test")
    public ShopifyConnectionService.ConnectionView test() {
        return service.test();
    }

    // Order import settings: warehouse that ships Shopify orders, public address for webhooks
    @PutMapping("/settings")
    public ShopifyConnectionService.ConnectionView settings(@RequestBody ShopifyOrderSyncService.Settings req) {
        return syncService.updateSettings(req);
    }

    @PostMapping("/webhooks")
    public ShopifyConnectionService.ConnectionView registerWebhooks() {
        return syncService.registerWebhooks();
    }

    // Catch-up: orders created in the last `days` days
    @PostMapping("/import")
    public ShopifyOrderSyncService.ImportResult importOrders(@RequestParam(defaultValue = "30") int days) {
        return syncService.importRecent(days);
    }

    // Sends every ERP product (details, price, stock) to Shopify in the background; poll GET for progress
    @PostMapping("/products/sync")
    public ShopifyProductSyncService.SyncView syncProducts() {
        return productSyncService.start();
    }

    // 204 when no product sync has run since the backend started
    @GetMapping("/products/sync")
    public ResponseEntity<ShopifyProductSyncService.SyncView> productSyncStatus() {
        return productSyncService.status().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    // Latest webhook deliveries, newest first; status=FAILED for the ones that need attention
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) ShopifyWebhookEvent.Status status) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(size, 1), 100));
        return (status == null ? eventRepository.findAllByOrderByReceivedAtDesc(page)
                : eventRepository.findByStatusOrderByReceivedAtDesc(status, page)).map(EventView::of).getContent();
    }

    // Runs a failed (or waiting) event now
    @PostMapping("/events/{id}/retry")
    public EventView retryEvent(@PathVariable Long id) {
        return EventView.of(eventProcessor.retryNow(id));
    }

    // The daily check, now: missing webhooks made again, orders changed since the last check imported
    @PostMapping("/reconcile")
    public ShopifyReconciler.Result reconcile() {
        return reconciler.reconcile();
    }

    // Every linked product's available stock to Shopify now (changes alone go every 20 s by themselves)
    @PostMapping("/stock/sync")
    public ShopifyStockSync.Result syncStock() {
        return stockSync.sync(true);
    }

    // Shopify refunds waiting for review, oldest first (each is reviewed on its order)
    @GetMapping("/refunds")
    public List<ShopifyRefundService.Waiting> refundsToReview() {
        return refundService.waiting();
    }

    // 204 when no store is connected
    @GetMapping("/health")
    public ResponseEntity<ShopifyHealthService.Health> health() {
        return healthService.health().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        service.disconnect();
        return ResponseEntity.noContent().build();
    }
}
