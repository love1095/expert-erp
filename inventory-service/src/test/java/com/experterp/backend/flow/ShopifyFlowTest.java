package com.experterp.backend.flow;

import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.Payment;
import com.experterp.backend.entity.PaymentAccount;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.entity.ShopifyProductLink;
import com.experterp.backend.entity.ShopifyRefund;
import com.experterp.backend.integration.shopify.ShopifyConnectionService;
import com.experterp.backend.integration.shopify.ShopifyFulfilmentSync;
import com.experterp.backend.integration.shopify.ShopifyRefundService;
import com.experterp.backend.integration.shopify.ShopifyStockSync;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.ShopifyProductLinkRepository;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.SalesLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review flow test F5, Shopify (a stand-in for the store): an order comes in by webhook with VAT in its prices, is
 * shipped with tracking, invoiced and paid, part refunded; the stock Shopify shows follows, and nothing is sent twice.
 *
 * 10 helmets at £6.00. Shopify order: 2 helmets at £25.00 + £4.95 shipping = £54.95 paid at checkout.
 * One helmet comes back and is refunded with the shipping: £29.95. Left: £25.00 of sales, 9 helmets, cost of goods £6.00.
 */
@AutoConfigureMockMvc
class ShopifyFlowTest extends FlowSupport {

    static final String SECRET = "shpss_flow_secret";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Long> remaining = new ConcurrentHashMap<>();          // Shopify line item id -> not fulfilled yet
    static final List<JsonNode> fulfillments = new CopyOnWriteArrayList<>();
    static final List<Integer> stockSet = new CopyOnWriteArrayList<>();            // every quantity Shopify was given
    static final HttpServer fakeShopify = startFakeShopify();

    @DynamicPropertySource
    static void shopifyUrl(DynamicPropertyRegistry registry) {
        registry.add("app.shopify.base-url", () -> "http://localhost:" + fakeShopify.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fakeShopify.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ShopifyConnectionService connectionService;
    @Autowired ShopifyStockSync stockSync;
    @Autowired ShopifyFulfilmentSync fulfilmentSync;
    @Autowired ShopifyRefundService refundService;
    @Autowired ShopifyProductLinkRepository links;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired FulfilmentRepository fulfilmentRepository;

    String shop;
    long shopifyOrderId;

    @Test
    void aShopifyOrderIsShippedInvoicedAndPartRefundedAndTheStoreIsToldEachThingOnce() throws Exception {
        remaining.clear();
        fulfillments.clear();
        stockSet.clear();
        Product helmet = product("HELMET");
        in(helmet, main, 10, "6.00");
        salesLedger.saveAccounts(List.of(new SalesLedgerService.AccountRequest(bank, "650", "Current Account - RBS", PaymentAccount.Kind.BANK, true, true),
                new SalesLedgerService.AccountRequest(null, "645", "Shopify Payments", PaymentAccount.Kind.SHOPIFY, true, false)));
        shop = "shop-" + id + ".myshopify.com";
        connectionService.save(new ShopifyConnectionService.SaveRequest(shop, "client", SECRET));
        ShopifyProductLink link = new ShopifyProductLink();
        link.setProductId(helmet.getId());
        link.setShopifyProductId("gid://shopify/Product/1");
        link.setShopifyVariantId("gid://shopify/ProductVariant/1");
        link.setShopifyInventoryItemId("gid://shopify/InventoryItem/900");
        links.save(link);
        shopifyOrderId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        remaining.put("1", 2L);

        // ---- the store is told the stock: 10 ----
        assertThat(stockSync.sync(false).sent()).isEqualTo(1);
        assertThat(stockSet).containsExactly(10);

        // ---- the order arrives (and arrives again): one confirmed order, £54.95 as the customer paid, 20 % VAT inside ----
        String order = order("");
        send("orders/create", order);
        send("orders/updated", order);
        assertThat(inTenant(() -> saleOrderRepository.findAll().size())).isEqualTo(1);
        SaleOrderResponse so = order();
        Long soId = so.getId();
        assertThat(so.getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);
        assertThat(so.getTotal()).isEqualByComparingTo("54.95");
        assertThat(so.getLines().get(0).getTaxRate()).isEqualByComparingTo("20");
        // 2 reserved: the store shows 8
        inTenant(() -> stockSync.sync(false));
        assertThat(stockSet).containsExactly(10, 8);

        // ---- shipped with tracking: the store gets one fulfillment, and only one ----
        FulfilmentRequest shipment = new FulfilmentRequest();
        shipment.setTrackingCompany("Royal Mail");
        shipment.setTrackingNumber("RM123GB");
        FulfilmentRequest.Line fl = new FulfilmentRequest.Line();
        fl.setSaleOrderLineId(so.getLines().get(0).getId());
        fl.setQuantity(new BigDecimal("2"));
        shipment.setLines(List.of(fl));
        inTenant(() -> saleOrderService.fulfill(soId, shipment));
        assertThat(fulfillments).singleElement().satisfies(f -> assertThat(f.path("trackingInfo").path("number").asText()).isEqualTo("RM123GB"));
        Long fulfilmentId = inTenant(() -> fulfilmentRepository.findBySaleOrderIdOrderByCreatedAtAsc(soId).get(0).getId());
        inTenant(() -> fulfilmentSync.push(fulfilmentId));
        assertThat(fulfillments).hasSize(1);
        assertThat(remaining).containsEntry("1", 0L);
        // 8 on hand, none reserved: still 8, so nothing is sent
        assertThat(inTenant(() -> stockSync.sync(false)).sent()).isZero();

        // ---- invoiced: paid at checkout, on the Shopify account ----
        InvoiceResponse invoice = inTenant(() -> invoiceAll(soId));
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("54.95");
        assertThat(invoice.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(invoice.getPayments()).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(Payment.PaymentMethod.SHOPIFY);
            assertThat(p.getAccount()).isEqualTo("645: Shopify Payments");
        });

        // ---- one helmet refunded with the shipping (seen twice): waits for review, then a credit note with the goods back ----
        String refunded = order("{\"id\":7001,\"created_at\":\"2026-11-03T11:00:00+00:00\",\"note\":\"Changed mind\",\"refund_line_items\":["
                + "{\"line_item_id\":1,\"quantity\":1,\"subtotal\":25.00,\"total_tax\":0,\"restock_type\":\"return\"}],"
                + "\"transactions\":[{\"kind\":\"refund\",\"status\":\"success\",\"amount\":\"29.95\"}]}");
        send("orders/updated", refunded);
        send("orders/updated", refunded);
        List<ShopifyRefundService.RefundView> refunds = inTenant(() -> refundService.forOrder(soId));
        assertThat(refunds).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ShopifyRefund.Status.TO_REVIEW);
            assertThat(r.amount()).isEqualByComparingTo("29.95");
        });
        Long lineId = so.getLines().get(0).getId();
        ShopifyRefundService.RefundView done = inTenant(() -> refundService.apply(soId, refunds.get(0).id(), new ShopifyRefundService.ApplyRequest(List.of(lineId))));
        assertThat(done.outcome()).startsWith("Credit note").contains("(£29.95)").contains("1 line(s) back in stock");
        // 9 on hand: the store is told once
        inTenant(() -> stockSync.sync(false));
        inTenant(() -> stockSync.sync(false));
        assertThat(stockSet).containsExactly(10, 8, 9);

        // ==== what must agree at the end ====
        TenantContext.set(tenantId);
        assertThat(onHand(helmet)).isEqualByComparingTo("9");
        assertThat(saleOrderService.findById(soId).getCostOfGoods()).isEqualByComparingTo("6.00");
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(tb.debits()).isEqualByComparingTo(tb.credits());
        assertThat(tb.warnings()).isEmpty();
        assertThat(balance(tb, "631")).isEqualByComparingTo("54.00");
        assertThat(layersValue()).isEqualByComparingTo("54.00");
        assertThat(balance(tb, "310")).isEqualByComparingTo("6.00");
        // Sales and VAT together are what the customer paid and kept paying: £54.95 − £29.95
        assertThat(balance(tb, "200").add(balance(tb, "820"))).isEqualByComparingTo("-25.00");
        assertThat(balance(tb, "200")).isEqualByComparingTo("-20.84");
        assertThat(balance(tb, "820")).isEqualByComparingTo("-4.16");
        // The refund went back out of Shopify Payments, where the money came in; the bank was never touched
        assertThat(balance(tb, "645")).isEqualByComparingTo("25.00");
        assertThat(balance(tb, "650")).isEqualByComparingTo("0");
        assertThat(balance(tb, "610")).isEqualByComparingTo("0");
        assertThat(auditActions()).contains("SHOPIFY:CONNECT", "SALE_ORDER:IMPORT", "SALE_ORDER:FULFILL", "SALE_ORDER:SHOPIFY_FULFILLMENT", "INVOICE:CREATE",
                "INVOICE:PAYMENT", "SALE_ORDER:SHOPIFY_REFUND", "SALE_ORDER:SHOPIFY_REFUND_APPLIED", "INVOICE:CREDIT_NOTE", "SALE_ORDER:RETURN");
    }

    // ---- helpers ----

    private void send(String topic, String body) throws Exception {
        TenantContext.clear();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Shopify-Shop-Domain", shop)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Event-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isOk());
        TenantContext.set(tenantId);
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(tenantId);
        return work.get();
    }

    private SaleOrderResponse order() {
        return inTenant(() -> saleOrderService.findById(saleOrderRepository
                .findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, String.valueOf(shopifyOrderId)).orElseThrow().getId()));
    }

    private String order(String refunds) {
        return "{\"id\":" + shopifyOrderId + ",\"name\":\"#" + shopifyOrderId + "\",\"financial_status\":\"paid\",\"currency\":\"GBP\","
                + "\"taxes_included\":true,\"total_price\":\"54.95\",\"created_at\":\"2026-11-03T09:30:00+00:00\","
                + "\"cancelled_at\":null,\"email\":null,\"phone\":null,\"customer\":null,"
                + "\"line_items\":[{\"id\":1,\"sku\":\"HELMET-" + id + "\",\"title\":\"Helmet\",\"quantity\":2,\"price\":\"25.00\",\"discount_allocations\":[]}],"
                + "\"shipping_lines\":[{\"title\":\"Courier\",\"price\":\"4.95\"}],\"refunds\":[" + refunds + "]}";
    }

    private static HttpServer startFakeShopify() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/admin/oauth/access_token", ex -> reply(ex,
                    "{\"access_token\":\"tok\",\"scope\":\"read_orders,write_merchant_managed_fulfillment_orders,write_products,write_inventory,read_locations\",\"expires_in\":86399}"));
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
                            + "{\"id\":\"gid://shopify/FulfillmentOrder/500\",\"status\":\"OPEN\",\"lineItems\":{\"nodes\":[" + items + "]}}]}}}}");
                } else if (query.contains("fulfillmentCreate")) {
                    JsonNode f = req.path("variables").path("fulfillment");
                    for (JsonNode byFo : f.path("lineItemsByFulfillmentOrder")) {
                        for (JsonNode li : byFo.path("fulfillmentOrderLineItems")) {
                            String foLine = li.path("id").asText();
                            String lineItem = foLine.substring(foLine.lastIndexOf('/') + 1, foLine.length() - 1);
                            remaining.computeIfPresent(lineItem, (k, v) -> v - li.path("quantity").asLong());
                        }
                    }
                    fulfillments.add(f);
                    reply(ex, "{\"data\":{\"fulfillmentCreate\":{\"fulfillment\":{\"id\":\"gid://shopify/Fulfillment/" + fulfillments.size()
                            + "\",\"status\":\"SUCCESS\"},\"userErrors\":[]}}}");
                } else if (query.contains("inventorySetQuantities(")) {
                    req.path("variables").path("input").path("quantities").forEach(q -> stockSet.add(q.path("quantity").asInt()));
                    reply(ex, "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"id\":\"g\"},\"userErrors\":[]}}}");
                } else if (query.contains("location {")) {
                    reply(ex, "{\"data\":{\"location\":{\"id\":\"gid://shopify/Location/1\",\"name\":\"Main store\"}}}");
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
