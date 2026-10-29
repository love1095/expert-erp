package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.AuditActorContext;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Phase 7.2: order-import settings, webhook registration and the catch-up import. */
@Service
@RequiredArgsConstructor
public class ShopifyOrderSyncService {

    public static final String WEBHOOK_PATH = "/api/webhooks/shopify";
    static final List<String> WEBHOOK_TOPICS = List.of("ORDERS_CREATE", "ORDERS_UPDATED", "APP_UNINSTALLED");
    // 10 pages of 50; without read_all_orders Shopify only returns the last 60 days anyway
    private static final int MAX_PAGES = 10;

    static final String ORDERS_QUERY = """
            query($cursor: String, $q: String) {
              orders(first: 50, after: $cursor, query: $q, sortKey: CREATED_AT) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id name createdAt cancelledAt displayFinancialStatus currencyCode email phone
                  customer { id firstName lastName }
                  shippingAddress { name address1 address2 city zip country phone }
                  lineItems(first: 100) {
                    nodes { id sku title quantity discountedUnitPriceAfterAllDiscountsSet { shopMoney { amount } } }
                  }
                }
              }
            }""";

    private static final String LIST_WEBHOOKS = "{ webhookSubscriptions(first: 100) { nodes { id topic uri } } }";
    private static final String DELETE_WEBHOOK = """
            mutation($id: ID!) { webhookSubscriptionDelete(id: $id) { deletedWebhookSubscriptionId userErrors { field message } } }""";
    private static final String CREATE_WEBHOOK = """
            mutation($topic: WebhookSubscriptionTopic!, $sub: WebhookSubscriptionInput!) {
              webhookSubscriptionCreate(topic: $topic, webhookSubscription: $sub) {
                webhookSubscription { id topic uri }
                userErrors { field message }
              }
            }""";

    private final ShopifyConnectionRepository connectionRepository;
    private final WarehouseRepository warehouseRepository;
    private final ShopifyConnectionService connectionService;
    private final ShopifyOrderImporter importer;
    private final ShopifyClient client;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public record Settings(Long defaultWarehouseId, String publicUrl) {}

    public record ImportResult(int fetched, int created, int updated, int unchanged, int skipped, int needsReview,
                               List<String> problems) {}

    public ShopifyConnectionService.ConnectionView updateSettings(Settings req) {
        String publicUrl = normalizePublicUrl(req.publicUrl());
        return tx.execute(s -> {
            ShopifyConnection c = current();
            c.setDefaultWarehouse(req.defaultWarehouseId() == null ? null : warehouseRepository.findById(req.defaultWarehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.defaultWarehouseId())));
            if (!Objects.equals(publicUrl, c.getPublicUrl())) {
                // Shopify still sends to the old address until webhooks are registered again
                c.setWebhooksRegisteredAt(null);
            }
            c.setPublicUrl(publicUrl);
            c.setUpdatedAt(LocalDateTime.now());
            auditService.log("SHOPIFY", c.getId(), "SETTINGS", Map.of(
                    "warehouseId", String.valueOf(req.defaultWarehouseId()), "publicUrl", String.valueOf(publicUrl)));
            return connectionService.get().orElseThrow();
        });
    }

    /** Points Shopify's order webhooks at {publicUrl}/api/webhooks/shopify, replacing ones to an older address. */
    public ShopifyConnectionService.ConnectionView registerWebhooks() {
        ShopifyConnection c = tx.execute(s -> current());
        if (c.getPublicUrl() == null) {
            throw new IllegalArgumentException("Enter the public address first (the https address Shopify can reach this backend at)");
        }
        String uri = c.getPublicUrl() + WEBHOOK_PATH;
        String token = connectionService.accessToken();
        String shop = c.getShopDomain();

        // Remove our earlier subscriptions (e.g. an old tunnel address) so events are not sent twice
        for (JsonNode sub : client.graphql(shop, token, LIST_WEBHOOKS, Map.of()).path("webhookSubscriptions").path("nodes")) {
            String existing = sub.path("uri").asText("");
            if (WEBHOOK_TOPICS.contains(sub.path("topic").asText()) && existing.endsWith(WEBHOOK_PATH)) {
                userErrors(client.graphql(shop, token, DELETE_WEBHOOK, Map.of("id", sub.path("id").asText()))
                        .path("webhookSubscriptionDelete"));
            }
        }
        for (String topic : WEBHOOK_TOPICS) {
            userErrors(client.graphql(shop, token, CREATE_WEBHOOK, Map.of("topic", topic, "sub", Map.of("uri", uri)))
                    .path("webhookSubscriptionCreate"));
        }

        return tx.execute(s -> {
            ShopifyConnection fresh = current();
            fresh.setWebhooksRegisteredAt(LocalDateTime.now());
            fresh.setWebhookTopics(String.join(",", WEBHOOK_TOPICS));
            auditService.log("SHOPIFY", fresh.getId(), "REGISTER_WEBHOOKS", Map.of("uri", uri, "topics", WEBHOOK_TOPICS));
            return connectionService.get().orElseThrow();
        });
    }

    /** Fetches orders created in the last {@code days} days and imports each one (one transaction per order). */
    public ImportResult importRecent(int days) {
        if (days < 1 || days > 60) throw new IllegalArgumentException("days must be between 1 and 60");
        ShopifyConnection c = tx.execute(s -> current());
        String token = connectionService.accessToken();
        String q = "created_at:>=" + LocalDate.now().minusDays(days);

        int fetched = 0, created = 0, updated = 0, unchanged = 0, skipped = 0, review = 0;
        List<String> problems = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("cursor", cursor);
            vars.put("q", q);
            JsonNode orders = client.graphql(c.getShopDomain(), token, ORDERS_QUERY, vars).path("orders");
            for (JsonNode node : orders.path("nodes")) {
                fetched++;
                ShopifyOrder order = ShopifyOrder.fromGraphql(node);
                try {
                    ShopifyOrderImporter.Result r = AuditActorContext.runAs(ActorType.INTEGRATION, "shopify",
                            () -> importer.importOrder(order));
                    switch (r.outcome()) {
                        case CREATED -> created++;
                        case UPDATED -> updated++;
                        case UNCHANGED -> unchanged++;
                        case SKIPPED -> skipped++;
                    }
                    if (r.needsReview() && r.outcome() != ShopifyOrderImporter.Outcome.UNCHANGED) {
                        review++;
                        problems.add(order.name() + ": " + r.reviewReason());
                    }
                } catch (RuntimeException e) {
                    problems.add(order.name() + ": not imported — " + e.getMessage());
                }
            }
            if (!orders.path("pageInfo").path("hasNextPage").asBoolean(false)) break;
            cursor = orders.path("pageInfo").path("endCursor").asText();
        }

        int f = fetched, cr = created, up = updated, rv = review;
        tx.executeWithoutResult(s -> {
            ShopifyConnection fresh = current();
            fresh.setLastImportAt(LocalDateTime.now());
            auditService.log("SHOPIFY", fresh.getId(), "IMPORT_ORDERS",
                    Map.of("days", days, "fetched", f, "created", cr, "updated", up, "needsReview", rv));
        });
        return new ImportResult(fetched, created, updated, unchanged, skipped, review, problems);
    }

    private ShopifyConnection current() {
        return connectionRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected"));
    }

    private static void userErrors(JsonNode result) {
        JsonNode errors = result.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText()));
            throw new ShopifyApiException("Shopify refused: " + String.join("; ", messages));
        }
    }

    /** https only, no path; blank = none. */
    static String normalizePublicUrl(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(WEBHOOK_PATH)) s = s.substring(0, s.length() - WEBHOOK_PATH.length());
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Public address is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Public address must start with https:// (Shopify only sends webhooks over HTTPS)");
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty()) {
            throw new IllegalArgumentException("Public address must be just the address, e.g. https://example.trycloudflare.com");
        }
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }
}
