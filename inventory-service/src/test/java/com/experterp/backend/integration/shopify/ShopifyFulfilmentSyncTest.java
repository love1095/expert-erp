package com.experterp.backend.integration.shopify;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.Fulfilment.SyncStatus;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.SaleOrderService;
import com.experterp.backend.service.StockMovementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping a Shopify order in the ERP sends a fulfillment to a local stand-in for Shopify, which keeps the remaining
 * quantity per Shopify line item like real fulfillment orders do.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopifyFulfilmentSyncTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Long> remaining = new ConcurrentHashMap<>();   // Shopify line item id -> unfulfilled
    static final List<JsonNode> fulfillmentsCreated = new CopyOnWriteArrayList<>();
    static volatile boolean refuseNext;
    static final HttpServer fakeShopify = startFakeShopify();

    @DynamicPropertySource
    static void shopifyUrl(DynamicPropertyRegistry registry) {
        registry.add("app.shopify.base-url", () -> "http://localhost:" + fakeShopify.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fakeShopify.stop(0);
    }

    @Autowired ShopifyConnectionService connectionService;
    @Autowired ShopifyOrderImporter importer;
    @Autowired ShopifyFulfilmentSync sync;
    @Autowired SaleOrderService saleOrderService;
    @Autowired StockMovementService movementService;
    @Autowired StockMovementRepository movementRepository;
    @Autowired FulfilmentRepository fulfilmentRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ProductRepository productRepository;

    Warehouse warehouse;
    Product helmet;
    Product gloves;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Tenant t = new Tenant();
        t.setName("Fulfil " + id);
        t.setSlug("fulfil-" + id);
        TenantContext.set(tenantRepository.save(t).getId());

        warehouse = new Warehouse();
        warehouse.setName("WH-" + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = product("HELMET-" + id);
        gloves = product("GLOVES-" + id);
        connectionService.save(new ShopifyConnectionService.SaveRequest("shop-" + id + ".myshopify.com", "client", "secret"));

        remaining.clear();
        fulfillmentsCreated.clear();
        refuseNext = false;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void eachShipmentBecomesAShopifyFulfillmentWithTracking() {
        SaleOrderResponse so = shopifyOrder(3, 2);

        SaleOrderResponse afterFirst = saleOrderService.fulfill(so.getId(), shipment("Royal Mail", "RM123GB", line(so, 0, 2)));

        assertThat(fulfillmentsCreated).hasSize(1);
        JsonNode sent = fulfillmentsCreated.get(0);
        assertThat(sent.path("notifyCustomer").asBoolean()).isTrue();
        assertThat(sent.path("trackingInfo").path("number").asText()).isEqualTo("RM123GB");
        assertThat(sent.path("trackingInfo").path("company").asText()).isEqualTo("Royal Mail");
        JsonNode byFo = sent.path("lineItemsByFulfillmentOrder").get(0);
        assertThat(byFo.path("fulfillmentOrderId").asText()).isEqualTo("gid://shopify/FulfillmentOrder/500");
        assertThat(byFo.path("fulfillmentOrderLineItems").get(0).path("id").asText()).isEqualTo("gid://shopify/FulfillmentOrderLineItem/1110");
        assertThat(byFo.path("fulfillmentOrderLineItems").get(0).path("quantity").asLong()).isEqualTo(2);
        assertThat(remaining).containsEntry("111", 1L).containsEntry("222", 2L);
        assertThat(syncStatusOfLatest(so.getId())).isEqualTo(SyncStatus.SYNCED);

        // The order view shows the result
        assertThat(saleOrderService.findById(so.getId()).getFulfilments()).singleElement()
                .satisfies(f -> assertThat(f.getSyncStatus()).isEqualTo(SyncStatus.SYNCED))
                .satisfies(f -> assertThat(f.getTrackingNumber()).isEqualTo("RM123GB"));
        assertThat(afterFirst.getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);

        // Second shipment: everything left
        saleOrderService.fulfill(so.getId(), null);
        assertThat(fulfillmentsCreated).hasSize(2);
        assertThat(remaining).containsEntry("111", 0L).containsEntry("222", 0L);
    }

    @Test
    void whenShopifyRefusesTheErpShipmentStandsAndCanBeRetried() {
        SaleOrderResponse so = shopifyOrder(1, 1);
        refuseNext = true;

        saleOrderService.fulfill(so.getId(), null);

        Fulfilment f = latest(so.getId());
        assertThat(f.getExternalSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(f.getExternalSyncMessage()).contains("Shopify refused the fulfillment").contains("location is inactive");
        // Stock left the warehouse regardless
        assertThat(movementRepository.calculateStock(helmet.getId(), warehouse.getId())).isEqualByComparingTo("99");

        assertThat(sync.push(f.getId())).isEqualTo(SyncStatus.SYNCED);
        assertThat(remaining).containsEntry("111", 0L);
        // A synced shipment is never sent twice
        assertThat(sync.push(f.getId())).isEqualTo(SyncStatus.SYNCED);
        assertThat(fulfillmentsCreated).hasSize(1);
    }

    @Test
    void itemsAlreadyFulfilledInShopifyAreNotSentAgain() {
        SaleOrderResponse so = shopifyOrder(2, 0);
        remaining.put("111", 0L);   // e.g. fulfilled by hand in Shopify admin

        saleOrderService.fulfill(so.getId(), null);

        Fulfilment f = latest(so.getId());
        assertThat(f.getExternalSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(f.getExternalSyncMessage()).contains("2 already fulfilled in Shopify");
        assertThat(fulfillmentsCreated).isEmpty();
    }

    @Test
    void ordersNotFromShopifyAreNotSent() {
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Walk-in");
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(l));
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(req).getId());

        saleOrderService.fulfill(so.getId(), null);

        assertThat(latest(so.getId()).getExternalSyncStatus()).isNull();
        assertThat(fulfillmentsCreated).isEmpty();
    }

    // ---- helpers ----

    private SaleOrderResponse shopifyOrder(int helmets, int gloveCount) {
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(100), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        movementService.createMovement(gloves, warehouse, BigDecimal.valueOf(100), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
        List<ShopifyOrder.Line> lines = new ArrayList<>();
        lines.add(new ShopifyOrder.Line("111", helmet.getSku(), "Helmet", BigDecimal.valueOf(helmets), new BigDecimal("10")));
        remaining.putIfAbsent("111", (long) helmets);
        if (gloveCount > 0) {
            lines.add(new ShopifyOrder.Line("222", gloves.getSku(), "Gloves", BigDecimal.valueOf(gloveCount), new BigDecimal("5")));
            remaining.putIfAbsent("222", (long) gloveCount);
        }
        String orderId = String.valueOf(Math.abs(new Random().nextLong() % 1_000_000_000L));
        var result = importer.importOrder(new ShopifyOrder(orderId, "#" + orderId, "paid", "GBP", OffsetDateTime.now(), false,
                null, null, null, null, lines));
        return saleOrderService.findById(result.saleOrderId());
    }

    private static FulfilmentRequest shipment(String company, String number, FulfilmentRequest.Line... lines) {
        FulfilmentRequest r = new FulfilmentRequest();
        r.setTrackingCompany(company);
        r.setTrackingNumber(number);
        r.setLines(List.of(lines));
        return r;
    }

    private static FulfilmentRequest.Line line(SaleOrderResponse so, int index, int qty) {
        FulfilmentRequest.Line l = new FulfilmentRequest.Line();
        l.setSaleOrderLineId(so.getLines().get(index).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private Fulfilment latest(Long saleOrderId) {
        return fulfilmentRepository.findAll().stream()
                .filter(f -> f.getSaleOrder().getId().equals(saleOrderId))
                .max(Comparator.comparing(Fulfilment::getId)).orElseThrow();
    }

    private SyncStatus syncStatusOfLatest(Long saleOrderId) {
        return latest(saleOrderId).getExternalSyncStatus();
    }

    private Product product(String sku) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("pcs");
        return productRepository.save(p);
    }

    // ---- fake Shopify ----

    private static HttpServer startFakeShopify() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/admin/oauth/access_token", ex ->
                    reply(ex, "{\"access_token\":\"tok\",\"scope\":\"read_orders,write_merchant_managed_fulfillment_orders\",\"expires_in\":86399}"));
            server.createContext("/admin/api/", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                String query = req.path("query").asText();
                if (query.contains("fulfillmentOrders")) {
                    StringBuilder items = new StringBuilder();
                    remaining.forEach((lineItem, qty) -> items.append(items.isEmpty() ? "" : ",")
                            .append("{\"id\":\"gid://shopify/FulfillmentOrderLineItem/").append(lineItem).append("0\",")
                            .append("\"remainingQuantity\":").append(qty)
                            .append(",\"lineItem\":{\"id\":\"gid://shopify/LineItem/").append(lineItem).append("\"}}"));
                    reply(ex, "{\"data\":{\"order\":{\"fulfillmentOrders\":{\"nodes\":["
                            + "{\"id\":\"gid://shopify/FulfillmentOrder/499\",\"status\":\"CLOSED\",\"lineItems\":{\"nodes\":[]}},"
                            + "{\"id\":\"gid://shopify/FulfillmentOrder/500\",\"status\":\"OPEN\",\"lineItems\":{\"nodes\":[" + items + "]}}]}}}}");
                } else if (query.contains("fulfillmentCreate")) {
                    if (refuseNext) {
                        refuseNext = false;
                        reply(ex, "{\"data\":{\"fulfillmentCreate\":{\"fulfillment\":null,\"userErrors\":[{\"field\":null,\"message\":\"The location is inactive\"}]}}}");
                        return;
                    }
                    JsonNode f = req.path("variables").path("fulfillment");
                    for (JsonNode byFo : f.path("lineItemsByFulfillmentOrder")) {
                        for (JsonNode li : byFo.path("fulfillmentOrderLineItems")) {
                            String foLine = li.path("id").asText();
                            String lineItem = foLine.substring(foLine.lastIndexOf('/') + 1, foLine.length() - 1);
                            remaining.computeIfPresent(lineItem, (k, v) -> v - li.path("quantity").asLong());
                        }
                    }
                    fulfillmentsCreated.add(f);
                    reply(ex, "{\"data\":{\"fulfillmentCreate\":{\"fulfillment\":{\"id\":\"gid://shopify/Fulfillment/" + fulfillmentsCreated.size()
                            + "\",\"status\":\"SUCCESS\"},\"userErrors\":[]}}}");
                } else {
                    reply(ex, "{\"data\":{\"shop\":{\"name\":\"Fake\",\"currencyCode\":\"GBP\"}}}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
