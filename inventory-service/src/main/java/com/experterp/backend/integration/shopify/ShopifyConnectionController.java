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
    private final ShopifyWebhookEventRepository eventRepository;

    public record EventView(Long id, String topic, String eventId, ShopifyWebhookEvent.Status status, String error,
                            LocalDateTime receivedAt, LocalDateTime processedAt) {}

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

    // Latest webhook deliveries, newest first
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(defaultValue = "20") int size) {
        return eventRepository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, Math.min(Math.max(size, 1), 100)))
                .map(e -> new EventView(e.getId(), e.getTopic(), e.getEventId(), e.getStatus(), e.getError(),
                        e.getReceivedAt(), e.getProcessedAt()))
                .getContent();
    }

    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        service.disconnect();
        return ResponseEntity.noContent().build();
    }
}
