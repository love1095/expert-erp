package com.experterp.backend.integration.shopify;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.service.CustomerService;
import com.experterp.backend.service.SaleOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Posts signed webhook payloads (Shopify's REST JSON shape) to the public endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopifyWebhookTest {

    static final String SECRET = "shpss_test_secret";

    @Autowired MockMvc mvc;
    @Autowired SecretCipher cipher;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ShopifyConnectionRepository connectionRepository;
    @Autowired ShopifyWebhookEventRepository eventRepository;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired CustomerService customerService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired TransactionTemplate tx;

    Long tenantId;
    String shop;
    String helmetSku;
    String glovesSku;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Tenant t = new Tenant();
        t.setName("Webhook " + id);
        t.setSlug("webhook-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);

        Warehouse wh = new Warehouse();
        wh.setName("WH-" + id);
        warehouseRepository.save(wh);
        helmetSku = "HELMET-" + id;
        glovesSku = "GLOVES-" + id;
        product(helmetSku, "Helmet");
        product(glovesSku, "Gloves");

        shop = "shop-" + id + ".myshopify.com";
        ShopifyConnection c = new ShopifyConnection();
        c.setShopDomain(shop);
        c.setClientId("client");
        c.setClientSecretEncrypted(cipher.encrypt(SECRET));
        c.setStatus(ShopifyConnection.Status.CONNECTED);
        connectionRepository.save(c);
        // Webhooks arrive without a tenant, like in production
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aPaidOrderBecomesAConfirmedSaleOrderWithItsCustomer() throws Exception {
        send("orders/create", "evt-1", order(1001, "paid", "GBP", customer(77, "Pat", "Jones", "pat@buyer.test"),
                line(helmetSku, "Helmet", 2, "25.00", "5.00"), line(glovesSku, "Gloves", 1, "8.50", null)))
                .andExpect(status().isOk());

        asTenant(() -> {
            SaleOrder so = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1001").orElseThrow();
            assertThat(so.getStatus()).isEqualTo(Status.CONFIRMED);
            assertThat(so.getExternalReference()).isEqualTo("#1001");
            assertThat(so.isNeedsReview()).isFalse();
            assertThat(so.getCustomerName()).isEqualTo("Pat Jones");
            assertThat(so.getLines()).hasSize(2);
            // £5 discount spread over 2 helmets
            assertThat(so.getLines().get(0).getUnitPrice()).isEqualByComparingTo("22.50");

            Customer c = so.getCustomer();
            assertThat(c.getSource()).isEqualTo(Customer.Source.SHOPIFY);
            assertThat(c.getExternalId()).isEqualTo("77");
            assertThat(c.getContacts()).singleElement().satisfies(cc -> assertThat(cc.getEmail()).isEqualTo("pat@buyer.test"));

            AuditLog audit = auditLogRepository.findAll().stream()
                    .filter(a -> "SALE_ORDER".equals(a.getEntityType()) && so.getId().equals(a.getEntityId())).findFirst().orElseThrow();
            assertThat(audit.getAction()).isEqualTo("IMPORT");
            assertThat(audit.getActorType()).isEqualTo(AuditLog.ActorType.INTEGRATION);
            assertThat(audit.getActorId()).isEqualTo("shopify");
            return null;
        });
    }

    @Test
    void repeatedDeliveriesAndUpdatesNeverDuplicateTheOrder() throws Exception {
        String body = order(1002, "paid", "GBP", customer(78, "Sam", "Lee", null), line(helmetSku, "Helmet", 1, "25.00", null));
        send("orders/create", "evt-dup", body).andExpect(status().isOk());
        send("orders/create", "evt-dup", body).andExpect(status().isOk());
        send("orders/updated", "evt-upd", body).andExpect(status().isOk());

        asTenant(() -> {
            assertThat(saleOrderRepository.findAll()).hasSize(1);
            assertThat(eventRepository.findAll()).extracting(ShopifyWebhookEvent::getEventId)
                    .containsExactlyInAnyOrder("evt-dup", "evt-upd");
            return null;
        });
    }

    @Test
    void unknownSkusAndOtherCurrenciesAreImportedAsDraftsThatNeedReview() throws Exception {
        send("orders/create", "evt-r1", order(1003, "paid", "GBP", null,
                line(helmetSku, "Helmet", 1, "25.00", null), line("NOT-IN-ERP", "Mystery box", 3, "1.00", null),
                line(null, "Gift wrap", 1, "2.00", null))).andExpect(status().isOk());
        send("orders/create", "evt-r2", order(1004, "paid", "USD", null, line(helmetSku, "Helmet", 1, "30.00", null)))
                .andExpect(status().isOk());

        asTenant(() -> {
            SaleOrder unknown = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1003").orElseThrow();
            assertThat(unknown.getStatus()).isEqualTo(Status.DRAFT);
            assertThat(unknown.isNeedsReview()).isTrue();
            assertThat(unknown.getReviewReason()).contains("SKU NOT-IN-ERP is not in the ERP").contains("'Gift wrap' × 1 has no SKU");
            assertThat(unknown.getLines()).hasSize(1);

            SaleOrder usd = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1004").orElseThrow();
            assertThat(usd.isNeedsReview()).isTrue();
            assertThat(usd.getReviewReason()).contains("USD");

            // A person confirming it accepts it as it is
            saleOrderService.confirm(unknown.getId());
            assertThat(saleOrderRepository.findById(unknown.getId()).orElseThrow().isNeedsReview()).isFalse();
            return null;
        });
    }

    @Test
    void anUnpaidOrderWaitsAsDraftAndIsConfirmedOnceShopifySaysItIsPaid() throws Exception {
        send("orders/create", "evt-u1", order(1005, "pending", "GBP", null, line(helmetSku, "Helmet", 1, "25.00", null)))
                .andExpect(status().isOk());
        assertThat(statusOf("1005")).isEqualTo(Status.DRAFT);

        send("orders/updated", "evt-u2", order(1005, "paid", "GBP", null, line(helmetSku, "Helmet", 1, "25.00", null)))
                .andExpect(status().isOk());
        assertThat(statusOf("1005")).isEqualTo(Status.CONFIRMED);
    }

    @Test
    void customersAreReusedByShopifyIdOrByEmail() throws Exception {
        asTenant(() -> {
            CustomerRequest req = new CustomerRequest();
            req.setName("Existing Builders Ltd");
            CustomerRequest.Contact contact = new CustomerRequest.Contact();
            contact.setEmail("Orders@Builders.test");
            req.getContacts().add(contact);
            return customerService.create(req);
        });

        send("orders/create", "evt-c1", order(1006, "paid", "GBP", customer(90, "Kim", "Park", null), line(helmetSku, "Helmet", 1, "1", null)));
        send("orders/create", "evt-c2", order(1007, "paid", "GBP", customer(90, "Kim", "Park", null), line(helmetSku, "Helmet", 1, "1", null)));
        // Guest checkout with an email an ERP customer already uses
        send("orders/create", "evt-c3", withEmail(order(1008, "paid", "GBP", null, line(helmetSku, "Helmet", 1, "1", null)), "orders@builders.test"));

        asTenant(() -> {
            SaleOrder a = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1006").orElseThrow();
            SaleOrder b = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1007").orElseThrow();
            SaleOrder guest = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1008").orElseThrow();
            assertThat(b.getCustomer().getId()).isEqualTo(a.getCustomer().getId());
            assertThat(guest.getCustomerName()).isEqualTo("Existing Builders Ltd");
            assertThat(customerRepository.findAll()).hasSize(2);
            return null;
        });
    }

    @Test
    void phoneAndAddressComeFromTheShippingAddressWhenTheCustomerHasNone() throws Exception {
        // Like a real checkout: the phone is only on the shipping address
        String body = order(1010, "paid", "GBP", customer(91, "Tina", "Tinh", "tina@buyer.test"), line(helmetSku, "Helmet", 1, "1", null))
                .replace("\"line_items\"", "\"shipping_address\":{\"name\":\"Tina Tinh\",\"address1\":\"10 Downing Street\",\"address2\":null,"
                        + "\"city\":\"London\",\"zip\":\"SW1A 2AA\",\"country\":\"United Kingdom\",\"phone\":\"+447700900123\"},\"line_items\"");
        send("orders/create", "evt-ship", body).andExpect(status().isOk());

        asTenant(() -> {
            Customer c = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, "1010").orElseThrow().getCustomer();
            assertThat(c.getContacts()).singleElement().satisfies(cc -> assertThat(cc.getPhone()).isEqualTo("+447700900123"));
            assertThat(c.getAddress()).isEqualTo("10 Downing Street\nLondon\nSW1A 2AA\nUnited Kingdom");
            return null;
        });
    }

    @Test
    void forgedOrMisaddressedWebhooksAreRejectedAndNothingIsStored() throws Exception {
        String body = order(1009, "paid", "GBP", null, line(helmetSku, "Helmet", 1, "1", null));
        mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Shopify-Shop-Domain", shop).header("X-Shopify-Topic", "orders/create")
                        .header("X-Shopify-Event-Id", "evt-forged").header("X-Shopify-Hmac-Sha256", sign(body, "wrong-secret")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Shopify-Shop-Domain", "someone-else.myshopify.com").header("X-Shopify-Topic", "orders/create")
                        .header("X-Shopify-Event-Id", "evt-other").header("X-Shopify-Hmac-Sha256", sign(body, SECRET)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        asTenant(() -> {
            assertThat(eventRepository.findAll()).isEmpty();
            assertThat(saleOrderRepository.findAll()).isEmpty();
            return null;
        });
    }

    @Test
    void anEventThatFailsAnswers500SoShopifyRetries() throws Exception {
        send("orders/create", "evt-bad", "{not json").andExpect(status().isInternalServerError());
        asTenant(() -> {
            assertThat(eventRepository.findByEventId("evt-bad").orElseThrow().getStatus()).isEqualTo(ShopifyWebhookEvent.Status.FAILED);
            return null;
        });
    }

    @Test
    void uninstallingTheAppMarksTheConnectionAsNotWorking() throws Exception {
        send("app/uninstalled", "evt-un", "{\"id\":1}").andExpect(status().isOk());
        asTenant(() -> {
            ShopifyConnection c = connectionRepository.findFirstByOrderByIdAsc().orElseThrow();
            assertThat(c.getStatus()).isEqualTo(ShopifyConnection.Status.ERROR);
            assertThat(c.getLastError()).contains("uninstalled");
            return null;
        });
    }

    // ---- helpers ----

    private ResultActions send(String topic, String eventId, String body) throws Exception {
        return mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Shopify-Shop-Domain", shop)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Event-Id", eventId)
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Hmac-Sha256", sign(body, SECRET)));
    }

    private Status statusOf(String shopifyId) {
        return asTenant(() -> saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, shopifyId).orElseThrow().getStatus());
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return tx.execute(s -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void product(String sku, String name) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setUnit("pcs");
        productRepository.save(p);
    }

    static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String customer(long id, String first, String last, String email) {
        return "{\"id\":" + id + ",\"first_name\":\"" + first + "\",\"last_name\":\"" + last + "\",\"email\":"
                + (email == null ? "null" : "\"" + email + "\"") + ",\"phone\":null}";
    }

    static String line(String sku, String title, int qty, String price, String discount) {
        return "{\"sku\":" + (sku == null ? "null" : "\"" + sku + "\"") + ",\"title\":\"" + title + "\",\"quantity\":" + qty
                + ",\"price\":\"" + price + "\",\"discount_allocations\":["
                + (discount == null ? "" : "{\"amount\":\"" + discount + "\"}") + "]}";
    }

    static String order(long id, String financialStatus, String currency, String customerJson, String... lines) {
        return "{\"id\":" + id + ",\"name\":\"#" + id + "\",\"financial_status\":\"" + financialStatus + "\",\"currency\":\""
                + currency + "\",\"created_at\":\"2026-10-29T09:30:00+00:00\",\"cancelled_at\":null,\"email\":null,\"phone\":null,"
                + "\"customer\":" + (customerJson == null ? "null" : customerJson) + ",\"line_items\":[" + String.join(",", List.of(lines)) + "]}";
    }

    static String withEmail(String orderJson, String email) {
        return orderJson.replace("\"email\":null,\"phone\":null,\"customer\"", "\"email\":\"" + email + "\",\"phone\":null,\"customer\"");
    }
}
