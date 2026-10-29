package com.experterp.backend.integration.shopify;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyConnection.Status;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.exception.DuplicateResourceException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.TenantRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a local stand-in for Shopify (no network). It accepts client id "good-client" with secret
 * "good-secret", answers "shop_not_permitted" for client id "other-org", and rejects everything else.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopifyConnectionServiceTest {

    static final String TOKEN = "shpat_test_token";
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static final java.util.List<String> graphqlCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile String knownSku = "";

    // GraphQL shape of two orders: one fully known, one with an unknown SKU
    static final String ORDERS_PAGE = """
            {"data":{"orders":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[
              {"id":"gid://shopify/Order/9001","name":"#9001","createdAt":"2026-10-28T10:00:00Z","cancelledAt":null,
               "displayFinancialStatus":"PAID","currencyCode":"GBP","email":"buyer@example.test","phone":null,
               "customer":{"id":"gid://shopify/Customer/55","firstName":"Ada","lastName":"Lovelace"},
               "lineItems":{"nodes":[{"sku":"KNOWN_SKU","title":"Helmet","quantity":2,
                 "discountedUnitPriceAfterAllDiscountsSet":{"shopMoney":{"amount":"19.99"}}}]}},
              {"id":"gid://shopify/Order/9002","name":"#9002","createdAt":"2026-10-28T11:00:00Z","cancelledAt":null,
               "displayFinancialStatus":"PAID","currencyCode":"GBP","email":null,"phone":null,"customer":null,
               "lineItems":{"nodes":[{"sku":"UNKNOWN-1","title":"Mystery","quantity":1,
                 "discountedUnitPriceAfterAllDiscountsSet":{"shopMoney":{"amount":"5.00"}}}]}}
            ]}}}""";
    static final HttpServer fakeShopify = startFakeShopify();

    @DynamicPropertySource
    static void shopifyUrl(DynamicPropertyRegistry registry) {
        registry.add("app.shopify.base-url", () -> "http://localhost:" + fakeShopify.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeShopify() {
        fakeShopify.stop(0);
    }

    @Autowired ShopifyConnectionService service;
    @Autowired ShopifyOrderSyncService syncService;
    @Autowired com.experterp.backend.repository.SaleOrderRepository saleOrderRepository;
    @Autowired com.experterp.backend.repository.ProductRepository productRepository;
    @Autowired com.experterp.backend.repository.WarehouseRepository warehouseRepository;
    @Autowired ShopifyConnectionRepository repository;
    @Autowired TenantRepository tenantRepository;

    String shop;

    @BeforeEach
    void setUp() {
        TenantContext.set(newTenant());
        shop = "store-" + UUID.randomUUID().toString().substring(0, 8) + ".myshopify.com";
        tokenRequests.set(0);
        graphqlCalls.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void savingWorkingCredentialsConnectsAndStoresSecretsEncrypted() {
        var view = service.save(new ShopifyConnectionService.SaveRequest("https://" + shop + "/", "good-client", "good-secret"));

        assertThat(view.status()).isEqualTo(Status.CONNECTED);
        assertThat(view.shopName()).isEqualTo("Fake Shop");
        assertThat(view.shopCurrency()).isEqualTo("GBP");
        assertThat(view.shopDomain()).isEqualTo(shop);
        assertThat(view.secretSaved()).isTrue();
        assertThat(view.lastError()).isNull();
        assertThat(view.missingScopes()).containsExactly("read_returns");
        assertThat(view.tokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));

        ShopifyConnection row = repository.findFirstByOrderByIdAsc().orElseThrow();
        assertThat(row.getClientSecretEncrypted()).startsWith("v1:").doesNotContain("good-secret");
        assertThat(row.getAccessTokenEncrypted()).startsWith("v1:").doesNotContain(TOKEN);
    }

    @Test
    void theCachedTokenIsReusedUntilItIsAboutToExpire() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));
        assertThat(tokenRequests.get()).isEqualTo(1);

        assertThat(service.accessToken()).isEqualTo(TOKEN);
        assertThat(tokenRequests.get()).isEqualTo(1);

        ShopifyConnection row = repository.findFirstByOrderByIdAsc().orElseThrow();
        row.setTokenExpiresAt(LocalDateTime.now().plusMinutes(5));
        repository.save(row);
        assertThat(service.accessToken()).isEqualTo(TOKEN);
        assertThat(tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void wrongCredentialsAreSavedWithAReadableError() {
        var view = service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "wrong-secret"));
        assertThat(view.status()).isEqualTo(Status.ERROR);
        assertThat(view.lastError()).contains("client ID or client secret");

        var unknownApp = service.save(new ShopifyConnectionService.SaveRequest(shop, "unknown-app", "x"));
        assertThat(unknownApp.lastError()).contains("no app with this client ID");

        var otherOrg = service.save(new ShopifyConnectionService.SaveRequest(shop, "other-org", "x"));
        assertThat(otherOrg.status()).isEqualTo(Status.ERROR);
        assertThat(otherOrg.lastError()).contains("not in the same organization");
    }

    @Test
    void aBlankSecretOnUpdateKeepsTheSavedOne() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));

        var view = service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", ""));

        assertThat(view.status()).isEqualTo(Status.CONNECTED);
        // The saved secret was used for a second token request
        assertThat(tokenRequests.get()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aStoreConnectedToAnotherTenantCannotBeTaken() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));

        TenantContext.set(newTenant());
        assertThatThrownBy(() -> service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret")))
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(service.get()).isEmpty();
    }

    @Test
    void disconnectRemovesTheConnection() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));
        service.disconnect();

        assertThat(service.get()).isEmpty();
        assertThatThrownBy(() -> service.test()).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void storeAddressesAreNormalisedAndOnlyMyshopifyDomainsAreAccepted() {
        assertThat(ShopifyConnectionService.normalizeShopDomain("expertsafety-dev")).isEqualTo("expertsafety-dev.myshopify.com");
        assertThat(ShopifyConnectionService.normalizeShopDomain(" HTTPS://ExpertSafety-Dev.myshopify.com/admin "))
                .isEqualTo("expertsafety-dev.myshopify.com");
        assertThat(ShopifyConnectionService.normalizeShopDomain("https://admin.shopify.com/store/expertsafety-dev/apps/expert-erp"))
                .isEqualTo("expertsafety-dev.myshopify.com");

        for (String bad : new String[]{"evil.com", "localhost:8080", "x.myshopify.com.evil.com", "a b.myshopify.com", ""}) {
            assertThatThrownBy(() -> ShopifyConnectionService.normalizeShopDomain(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theCatchUpImportCreatesSaleOrdersAndIsSafeToRepeat() {
        com.experterp.backend.entity.Warehouse wh = new com.experterp.backend.entity.Warehouse();
        wh.setName("WH-" + UUID.randomUUID());
        wh = warehouseRepository.save(wh);
        com.experterp.backend.entity.Product p = new com.experterp.backend.entity.Product();
        knownSku = "SKU-" + UUID.randomUUID();
        p.setSku(knownSku);
        p.setName("Helmet");
        p.setUnit("pcs");
        productRepository.save(p);
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));
        syncService.updateSettings(new ShopifyOrderSyncService.Settings(wh.getId(), null));

        var first = syncService.importRecent(30);
        assertThat(first.fetched()).isEqualTo(2);
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.needsReview()).isEqualTo(1);
        assertThat(first.problems()).singleElement().asString().contains("#9002").contains("UNKNOWN-1");

        var order = saleOrderRepository.findBySourceAndExternalId(com.experterp.backend.entity.SaleOrder.Source.SHOPIFY, "9001").orElseThrow();
        assertThat(order.getStatus()).isEqualTo(com.experterp.backend.entity.SaleOrder.Status.CONFIRMED);
        assertThat(order.getWarehouse().getId()).isEqualTo(wh.getId());
        assertThat(order.getCustomerName()).isEqualTo("Ada Lovelace");

        var again = syncService.importRecent(30);
        // #9001 is CONFIRMED now, so Shopify updates no longer change it; #9002 is still a DRAFT and is refreshed
        assertThat(again.created()).isZero();
        assertThat(again.unchanged()).isEqualTo(1);
        assertThat(again.updated()).isEqualTo(1);
        assertThat(service.get().orElseThrow().lastImportAt()).isNotNull();
    }

    @Test
    void registeringWebhooksReplacesOurOldAddressAndKeepsOtherSubscriptions() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));
        assertThatThrownBy(() -> syncService.registerWebhooks()).hasMessageContaining("public address");

        syncService.updateSettings(new ShopifyOrderSyncService.Settings(null, "https://New-Tunnel.trycloudflare.com/"));
        var view = syncService.registerWebhooks();

        assertThat(graphqlCalls).containsExactly(
                "delete 1",
                "create ORDERS_CREATE https://new-tunnel.trycloudflare.com/api/webhooks/shopify",
                "create ORDERS_UPDATED https://new-tunnel.trycloudflare.com/api/webhooks/shopify",
                "create APP_UNINSTALLED https://new-tunnel.trycloudflare.com/api/webhooks/shopify");
        assertThat(view.webhooksRegisteredAt()).isNotNull();
        assertThat(view.webhookUrl()).isEqualTo("https://new-tunnel.trycloudflare.com/api/webhooks/shopify");

        // A new address means webhooks must be registered again
        var moved = syncService.updateSettings(new ShopifyOrderSyncService.Settings(null, "https://another.trycloudflare.com"));
        assertThat(moved.webhooksRegisteredAt()).isNull();
    }

    @Test
    void shopifyRefusingAWebhookTopicIsReportedWithItsReason() {
        service.save(new ShopifyConnectionService.SaveRequest(shop, "good-client", "good-secret"));
        syncService.updateSettings(new ShopifyOrderSyncService.Settings(null, "https://refused-topics.trycloudflare.com"));

        assertThatThrownBy(() -> syncService.registerWebhooks())
                .isInstanceOf(ShopifyApiException.class)
                .hasMessageContaining("protected customer data");
        assertThat(service.get().orElseThrow().webhooksRegisteredAt()).isNull();
    }

    @Test
    void thePublicAddressMustBeAPlainHttpsAddress() {
        assertThat(ShopifyOrderSyncService.normalizePublicUrl("https://abc.trycloudflare.com/api/webhooks/shopify"))
                .isEqualTo("https://abc.trycloudflare.com");
        assertThat(ShopifyOrderSyncService.normalizePublicUrl("  ")).isNull();
        for (String bad : new String[]{"http://abc.example.com", "https://abc.example.com/somewhere", "not a url", "ftp://x"}) {
            assertThatThrownBy(() -> ShopifyOrderSyncService.normalizePublicUrl(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Long newTenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Shopify " + id);
        t.setSlug("shopify-" + id);
        return tenantRepository.save(t).getId();
    }

    private static HttpServer startFakeShopify() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/admin/oauth/access_token", ex -> {
                tokenRequests.incrementAndGet();
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (form.contains("client_id=unknown-app")) {
                    // What the real Shopify answers for a client id it doesn't know
                    reply(ex, 400, "{\"error\":\"application_cannot_be_found\",\"error_description\":\"Could not find Shopify API application with api_key unknown-app\"}");
                } else if (form.contains("client_id=other-org")) {
                    reply(ex, 400, "{\"error\":\"shop_not_permitted\",\"error_description\":\"Client credentials cannot be performed on this shop.\"}");
                } else if (form.contains("grant_type=client_credentials") && form.contains("client_id=good-client")
                        && form.contains("client_secret=good-secret")) {
                    // Deliberately without read_returns, to check the missing-scope report
                    reply(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"scope\":\"read_orders,read_customers,read_products,"
                            + "write_inventory,read_locations,write_merchant_managed_fulfillment_orders\",\"expires_in\":86399}");
                } else {
                    reply(ex, 401, "{\"error\":\"invalid_client\"}");
                }
            });
            server.createContext("/admin/api/", ex -> {
                String gql = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Shopify-Access-Token"))) {
                    reply(ex, 401, "{\"errors\":\"[API] Invalid API key or access token\"}");
                } else if (gql.contains("webhookSubscriptions(")) {
                    // One of ours to an old tunnel address, and someone else's that must be left alone
                    reply(ex, 200, "{\"data\":{\"webhookSubscriptions\":{\"nodes\":["
                            + "{\"id\":\"gid://shopify/WebhookSubscription/1\",\"topic\":\"ORDERS_CREATE\",\"uri\":\"https://old.trycloudflare.com/api/webhooks/shopify\"},"
                            + "{\"id\":\"gid://shopify/WebhookSubscription/2\",\"topic\":\"ORDERS_CREATE\",\"uri\":\"https://other-app.example/hook\"}]}}}");
                } else if (gql.contains("webhookSubscriptionDelete")) {
                    graphqlCalls.add("delete " + (gql.contains("WebhookSubscription/1") ? "1" : "?"));
                    reply(ex, 200, "{\"data\":{\"webhookSubscriptionDelete\":{\"deletedWebhookSubscriptionId\":\"x\",\"userErrors\":[]}}}");
                } else if (gql.contains("webhookSubscriptionCreate") && gql.contains("refused-topics.trycloudflare.com")) {
                    // What the real Shopify answers before protected customer data access is approved
                    reply(ex, 200, "{\"data\":{\"webhookSubscriptionCreate\":{\"webhookSubscription\":null,\"userErrors\":[{\"field\":[\"webhookSubscription\"],"
                            + "\"message\":\"This app is not approved to subscribe to webhook topics containing protected customer data.\"}]}}}");
                } else if (gql.contains("webhookSubscriptionCreate")) {
                    String topic = gql.replaceAll("(?s).*\"topic\":\"([A-Z_]+)\".*", "$1");
                    String uri = gql.replaceAll("(?s).*\"uri\":\"([^\"]+)\".*", "$1");
                    graphqlCalls.add("create " + topic + " " + uri);
                    reply(ex, 200, "{\"data\":{\"webhookSubscriptionCreate\":{\"webhookSubscription\":{\"id\":\"x\"},\"userErrors\":[]}}}");
                } else if (gql.contains("orders(")) {
                    reply(ex, 200, ORDERS_PAGE.replace("KNOWN_SKU", knownSku));
                } else {
                    reply(ex, 200, "{\"data\":{\"shop\":{\"name\":\"Fake Shop\",\"myshopifyDomain\":\"fake.myshopify.com\",\"currencyCode\":\"GBP\"}}}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
