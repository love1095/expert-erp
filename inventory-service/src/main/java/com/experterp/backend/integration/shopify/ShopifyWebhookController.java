package com.experterp.backend.integration.shopify;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public endpoint Shopify calls; authenticated by the HMAC signature, not a login. */
@RestController
@RequestMapping("/api/webhooks/shopify")
@RequiredArgsConstructor
public class ShopifyWebhookController {

    private final ShopifyWebhookService service;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Shopify-Shop-Domain", required = false) String shop,
            @RequestHeader(value = "X-Shopify-Topic", required = false) String topic,
            @RequestHeader(value = "X-Shopify-Hmac-Sha256", required = false) String hmac,
            @RequestHeader(value = "X-Shopify-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Shopify-Webhook-Id", required = false) String webhookId) {
        return switch (service.receive(body, shop, topic, hmac, eventId, webhookId)) {
            case OK -> ResponseEntity.ok().build();
            case UNAUTHORIZED -> ResponseEntity.status(401).build();
            case BAD_REQUEST -> ResponseEntity.badRequest().build();
            // Shopify retries non-2xx answers
            case FAILED -> ResponseEntity.internalServerError().build();
        };
    }
}
