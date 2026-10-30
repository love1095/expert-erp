package com.experterp.backend.integration.shopify;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.integration.shopify.ShopifyProductSyncService.State;
import com.experterp.backend.integration.shopify.ShopifyProductSyncService.SyncView;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.StockMovementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sending ERP products to a local stand-in for Shopify that keeps products and their variants (like productSet
 * does: variants left out of an update are removed) and records every mutation it receives.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopifyProductSyncTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String LOCATION = "gid://shopify/Location/77";
    static final AtomicLong ids = new AtomicLong(1000);
    // What the fake shop has: product id -> product
    static final Map<String, FakeProduct> shop = new ConcurrentHashMap<>();
    // Mutations received: name -> variables
    static final List<Map.Entry<String, JsonNode>> calls = new CopyOnWriteArrayList<>();
    static volatile String scopes;
    static final HttpServer fakeShopify = startFakeShopify();

    static final class FakeProduct {
        final String id;
        volatile String status;
        final List<FakeVariant> variants = new CopyOnWriteArrayList<>();

        FakeProduct(String id, String status) {
            this.id = id;
            this.status = status;
        }
    }

    record FakeVariant(String id, String itemId, String sku, boolean stocked) {}

    @DynamicPropertySource
    static void shopifyUrl(DynamicPropertyRegistry registry) {
        registry.add("app.shopify.base-url", () -> "http://localhost:" + fakeShopify.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fakeShopify.stop(0);
    }

    @Autowired ShopifyConnectionService connectionService;
    @Autowired ShopifyProductSyncService sync;
    @Autowired StockMovementService movementService;
    @Autowired ShopifyProductLinkRepository linkRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;

    Warehouse warehouse;
    String id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 8);
        Tenant t = new Tenant();
        t.setName("Products " + id);
        t.setSlug("products-" + id);
        TenantContext.set(tenantRepository.save(t).getId());

        warehouse = new Warehouse();
        warehouse.setName("WH-" + id);
        warehouse = warehouseRepository.save(warehouse);
        shop.clear();
        calls.clear();
        scopes = "read_orders,write_products,write_inventory,read_locations";
        connectionService.save(new ShopifyConnectionService.SaveRequest("shop-" + id + ".myshopify.com", "client", "secret"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void singleProductsAreCreatedAsDraftsWithPriceCostStockAndDetails_evenWithoutAPrice() {
        Category cat = new Category();
        cat.setName("Hi-vis " + id);
        cat = categoryRepository.save(cat);
        Product vest = product("VEST-" + id, "Hi-vis vest", "12.50", "4.10");
        vest.setDescription("Class 2 <EN ISO 20471>\nYellow");
        vest.setCategory(cat);
        vest.setBrand("Portwest");
        vest.getDetails().setTags("Vests, Hi-vis");
        vest.getDetails().setWeight(new BigDecimal("150"));
        vest.getDetails().setWeightUnit("g");
        productRepository.save(vest);
        stock(vest, "7.6");
        product("NOPRICE-" + id, "No price yet", null, "1");

        SyncView result = runSync();

        assertThat(result.state()).isEqualTo(State.DONE);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.withoutPrice()).isEqualTo(1);
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("£0.00").contains("NOPRICE-" + id));
        assertThat(result.locationName()).isEqualTo("Main store");

        JsonNode input = productSetFor("VEST-" + id).path("input");
        assertThat(input.path("title").asText()).isEqualTo("Hi-vis vest");
        assertThat(input.path("status").asText()).isEqualTo("DRAFT");
        assertThat(input.path("productType").asText()).isEqualTo("Hi-vis " + id);
        assertThat(input.path("vendor").asText()).isEqualTo("Portwest");
        assertThat(input.path("tags").toString()).isEqualTo("[\"Vests\",\"Hi-vis\"]");
        assertThat(input.path("descriptionHtml").asText()).isEqualTo("<p>Class 2 &lt;EN ISO 20471&gt;<br>Yellow</p>");
        assertThat(input.path("productOptions").get(0).path("name").asText()).isEqualTo("Title");
        JsonNode v = input.path("variants").get(0);
        assertThat(v.path("sku").asText()).isEqualTo("VEST-" + id);
        assertThat(v.path("price").asText()).isEqualTo("12.5000");
        assertThat(v.path("inventoryItem").path("cost").asText()).isEqualTo("4.1000");
        assertThat(v.path("inventoryItem").path("measurement").path("weight").path("unit").asText()).isEqualTo("GRAMS");
        // Part units round down
        assertThat(v.path("inventoryQuantities").get(0).path("quantity").asInt()).isEqualTo(7);
        assertThat(productSetFor("NOPRICE-" + id).path("input").path("variants").get(0).path("price").asText()).isEqualTo("0.00");

        ShopifyProductLink link = link(vest);
        assertThat(link.getCreatedByErp()).isTrue();
        assertThat(connectionService.get().orElseThrow().lastProductSyncAt()).isNotNull();
    }

    @Test
    void aFamilyBecomesOneProductWithOptions_andLaterRunsOnlyResendWhatChanged() {
        Product navyM = familyMember("POLO-N-M-" + id, "Navy", "M", "10");
        Product navyL = familyMember("POLO-N-L-" + id, "Navy", "L", "10");
        Product redM = familyMember("POLO-R-M-" + id, "Red", "M", "11");
        stock(navyL, "5");

        SyncView first = runSync();

        assertThat(first.total()).isEqualTo(1);
        assertThat(first.created()).isEqualTo(1);
        assertThat(first.variants()).isEqualTo(3);
        assertThat(shop).hasSize(1);
        JsonNode input = only("productSet").path("input");
        assertThat(input.path("title").asText()).isEqualTo("Orn Polo " + id);
        assertThat(input.path("productOptions").toString())
                .isEqualTo("[{\"name\":\"Colour\",\"values\":[{\"name\":\"Navy\"},{\"name\":\"Red\"}]},"
                        + "{\"name\":\"Size\",\"values\":[{\"name\":\"L\"},{\"name\":\"M\"}]}]");
        assertThat(input.path("variants")).hasSize(3);
        assertThat(link(navyM).getShopifyProductId()).isEqualTo(link(redM).getShopifyProductId())
                .isEqualTo(link(navyL).getShopifyProductId());
        assertThat(link(navyM).isSingleVariant()).isFalse();

        // Nothing changed: no product sent, stock still set (on hand, no change-from check)
        calls.clear();
        SyncView second = runSync();
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(names()).doesNotContain("productSet");
        JsonNode set = only("inventorySetQuantities").path("input");
        assertThat(set.path("name").asText()).isEqualTo("on_hand");
        Map<String, Integer> byItem = new HashMap<>();
        set.path("quantities").forEach(q -> {
            assertThat(q.path("changeFromQuantity").isNull()).isTrue();
            byItem.put(q.path("inventoryItemId").asText(), q.path("quantity").asInt());
        });
        assertThat(byItem).containsEntry(link(navyL).getShopifyInventoryItemId(), 5).hasSize(3);

        // A price change and a deprecated size: the same product is updated, the old variant goes
        redM.setSellPrice(new BigDecimal("12"));
        productRepository.save(redM);
        navyM.setStatus(Product.Status.DEPRECATED);
        productRepository.save(navyM);
        calls.clear();
        SyncView third = runSync();

        assertThat(third.updated()).isEqualTo(1);
        assertThat(third.notSent()).isEqualTo(1);
        JsonNode update = only("productSet");
        assertThat(update.path("identifier").path("id").asText()).isEqualTo(link(redM).getShopifyProductId());
        assertThat(update.path("input").has("status")).isFalse();
        assertThat(update.path("input").path("variants")).hasSize(2)
                .allSatisfy(v -> assertThat(v.has("id")).isTrue())
                .allSatisfy(v -> assertThat(v.has("inventoryQuantities")).isFalse());
        assertThat(shop.values()).singleElement().satisfies(p -> assertThat(p.variants).hasSize(2));
        assertThat(linkRepository.findByProductId(navyM.getId())).isEmpty();
    }

    @Test
    void oldOnePerSkuDraftsOfAFamilyAreReplacedByOneFamilyProduct() {
        Product m = familyMember("TEE-M-" + id, "Black", "M", "5");
        Product l = familyMember("TEE-L-" + id, "Black", "L", "5");
        // Linked by an earlier version of the sync (ownership not recorded), still drafts in Shopify
        legacyDraftLink(m);
        legacyDraftLink(l);
        assertThat(shop).hasSize(2);

        SyncView result = runSync();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(2);
        assertThat(all("productDelete")).hasSize(2);
        assertThat(shop).hasSize(1);
        assertThat(shop.values().iterator().next().variants).extracting(FakeVariant::sku)
                .containsExactlyInAnyOrder("TEE-M-" + id, "TEE-L-" + id);
    }

    @Test
    void handMadeShopifyProductsAreUpdatedNotRebuilt_andArchivedWhenDeprecated() {
        // Made by hand in Shopify (active): one plain product, one variant of a product with sizes, one not stocked here
        handMade("ROLL-" + id, true, true);
        handMade("BOOT-M-" + id, false, true);
        handMade("TAPE-" + id, true, false);
        product("ROLL-" + id, "Blue roll", "12.50", "6");
        product("BOOT-M-" + id, "Boot size M", "40", "15");
        Product tape = product("TAPE-" + id, "Tape", "3", "1");
        stock(tape, "4");

        SyncView result = runSync();

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(3);
        assertThat(names()).doesNotContain("productSet", "productDelete");
        // Title/description only on single-variant products
        assertThat(all("productUpdate")).hasSize(2);
        assertThat(all("productVariantsBulkUpdate")).hasSize(3);
        JsonNode activate = only("inventoryActivate");
        assertThat(activate.path("onHand").asInt()).isEqualTo(4);
        assertThat(link(tape).getCreatedByErp()).isFalse();

        // Deprecated in the ERP: archived in Shopify, never deleted
        tape.setStatus(Product.Status.DEPRECATED);
        productRepository.save(tape);
        calls.clear();
        SyncView after = runSync();
        assertThat(after.removed()).isEqualTo(1);
        assertThat(names()).doesNotContain("productDelete");
        assertThat(all("productUpdate")).anySatisfy(u -> assertThat(u.path("product").path("status").asText()).isEqualTo("ARCHIVED"));
        assertThat(linkRepository.findByProductId(tape.getId())).isEmpty();
    }

    @Test
    void aFamilyWithClashingOptionsIsSentAsSeparateProducts() {
        familyMember("CAP-1-" + id, "Navy", "One size", "8");
        familyMember("CAP-2-" + id, "Navy", "One size", "8");

        SyncView result = runSync();

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("FAM-" + id).contains("sent as separate products"));
    }

    @Test
    void aProductShopifyRefusesIsReportedAndTheRestStillSync() {
        product("GOOD-" + id, "Good", "1", null);
        product("REFUSE-" + id, "Refuse me", "1", null);

        SyncView result = runSync();

        assertThat(result.state()).isEqualTo(State.DONE);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.problems()).anySatisfy(p -> assertThat(p)
                .startsWith("REFUSE-" + id).contains("Shopify refused the product").contains("Title is not allowed"));
    }

    @Test
    void refusesToStartWithoutPermissionToChangeProducts() {
        scopes = "read_orders,read_products";
        connectionService.test();

        assertThatThrownBy(() -> sync.start()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("write_products");
    }

    @Test
    void quantitiesAndDescriptions() {
        assertThat(ShopifyProductSyncService.shopifyQuantity(null)).isZero();
        assertThat(ShopifyProductSyncService.shopifyQuantity(new BigDecimal("-3"))).isZero();
        assertThat(ShopifyProductSyncService.shopifyQuantity(new BigDecimal("2.99"))).isEqualTo(2);
        assertThat(ShopifyProductSyncService.descriptionHtml(null)).isEmpty();
        assertThat(ShopifyProductSyncService.descriptionHtml("A & B\n\nSecond")).isEqualTo("<p>A &amp; B</p><p>Second</p>");
        // Cin7 descriptions can be whole web pages: only the body content is kept
        assertThat(ShopifyProductSyncService.descriptionHtml("""
                <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>X</title>
                <style>p{color:red}</style></head><body><div class="d"><p>Spill kit</p><script>alert(1)</script></div></body></html>"""))
                .isEqualTo("<div class=\"d\"><p>Spill kit</p></div>");
        assertThat(ShopifyProductSyncService.shopifyWeightUnit("gram")).isEqualTo("GRAMS");
        assertThat(ShopifyProductSyncService.shopifyWeightUnit("stone")).isNull();
    }

    // ---- helpers ----

    private SyncView runSync() {
        sync.start();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            SyncView v = sync.status().orElseThrow();
            if (v.state() != State.RUNNING) return v;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Product sync did not finish");
    }

    private Product product(String sku, String name, String price, String cost) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setUnit("pcs");
        p.setSellPrice(price == null ? null : new BigDecimal(price));
        p.setCostPrice(cost == null ? null : new BigDecimal(cost));
        return productRepository.save(p);
    }

    private Product familyMember(String sku, String colour, String size, String price) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("Each");
        p.setSellPrice(new BigDecimal(price));
        String family = sku.startsWith("CAP") ? "FAM-" + id : sku.substring(0, sku.indexOf('-')) + "-" + id;
        p.setFamilySku(family);
        p.setFamilyName(sku.startsWith("POLO") ? "Orn Polo " + id : "Family " + family);
        p.setVariantOptions(List.of(new VariantOption("Colour", colour), new VariantOption("Size", size)));
        return productRepository.save(p);
    }

    private void legacyDraftLink(Product p) {
        String n = String.valueOf(ids.incrementAndGet());
        FakeProduct fp = new FakeProduct("gid://shopify/Product/" + n, "DRAFT");
        fp.variants.add(new FakeVariant("gid://shopify/ProductVariant/" + n, "gid://shopify/InventoryItem/" + n, p.getSku(), true));
        shop.put(fp.id, fp);
        ShopifyProductLink l = new ShopifyProductLink();
        l.setProductId(p.getId());
        l.setShopifyProductId(fp.id);
        l.setShopifyVariantId("gid://shopify/ProductVariant/" + n);
        l.setShopifyInventoryItemId("gid://shopify/InventoryItem/" + n);
        l.setContentHash("old");
        linkRepository.save(l);
    }

    private void handMade(String sku, boolean single, boolean stocked) {
        String n = String.valueOf(ids.incrementAndGet());
        FakeProduct fp = new FakeProduct("gid://shopify/Product/" + n, "ACTIVE");
        fp.variants.add(new FakeVariant("gid://shopify/ProductVariant/" + n, "gid://shopify/InventoryItem/" + n, sku, stocked));
        if (!single) fp.variants.add(new FakeVariant("gid://shopify/ProductVariant/" + n + "9", "gid://shopify/InventoryItem/" + n + "9", sku + "-OTHER", true));
        shop.put(fp.id, fp);
    }

    private void stock(Product p, String qty) {
        movementService.createMovement(p, warehouse, new BigDecimal(qty), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "T", null);
    }

    private ShopifyProductLink link(Product p) {
        return linkRepository.findByProductId(p.getId()).orElseThrow();
    }

    private static List<String> names() {
        return calls.stream().map(Map.Entry::getKey).toList();
    }

    private static List<JsonNode> all(String name) {
        return calls.stream().filter(c -> c.getKey().equals(name)).map(Map.Entry::getValue).toList();
    }

    private static JsonNode only(String name) {
        List<JsonNode> found = all(name);
        assertThat(found).as(name + " calls").hasSize(1);
        return found.get(0);
    }

    private static JsonNode productSetFor(String sku) {
        return all("productSet").stream()
                .filter(c -> c.path("input").path("variants").get(0).path("sku").asText().equals(sku))
                .findFirst().orElseThrow();
    }

    // ---- fake Shopify ----

    private static HttpServer startFakeShopify() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/admin/oauth/access_token", ex ->
                    reply(ex, "{\"access_token\":\"tok\",\"scope\":\"" + scopes + "\",\"expires_in\":86399}"));
            server.createContext("/admin/api/", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                String query = req.path("query").asText();
                JsonNode vars = req.path("variables");
                if (query.contains("location {")) {
                    reply(ex, "{\"data\":{\"location\":{\"id\":\"" + LOCATION + "\",\"name\":\"Main store\"}}}");
                } else if (query.contains("productVariants(first")) {
                    ArrayNode nodes = JSON.createArrayNode();
                    for (FakeProduct p : shop.values()) {
                        for (FakeVariant v : p.variants) {
                            ObjectNode n = nodes.addObject().put("id", v.id()).put("sku", v.sku());
                            n.putObject("product").put("id", p.id).put("hasOnlyDefaultVariant", p.variants.size() == 1).put("status", p.status);
                            ObjectNode item = n.putObject("inventoryItem").put("id", v.itemId());
                            if (v.stocked()) item.putObject("inventoryLevel").put("id", "lvl");
                        }
                    }
                    reply(ex, "{\"data\":{\"productVariants\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"nodes\":" + nodes + "}}}");
                } else if (query.contains("productSet(")) {
                    calls.add(Map.entry("productSet", vars));
                    JsonNode input = vars.path("input");
                    if (input.path("title").asText().startsWith("Refuse")) {
                        reply(ex, "{\"data\":{\"productSet\":{\"product\":null,\"userErrors\":[{\"field\":[\"title\"],\"message\":\"Title is not allowed\"}]}}}");
                        return;
                    }
                    String productId = vars.path("identifier").path("id").asText(null);
                    FakeProduct p = productId == null ? null : shop.get(productId);
                    if (p == null) {
                        p = new FakeProduct("gid://shopify/Product/" + ids.incrementAndGet(), input.path("status").asText("ACTIVE"));
                        shop.put(p.id, p);
                    }
                    // Like productSet: the given variants replace the product's variants
                    List<FakeVariant> next = new ArrayList<>();
                    for (JsonNode v : input.path("variants")) {
                        String vid = v.path("id").asText(null);
                        FakeVariant existing = vid == null ? null
                                : p.variants.stream().filter(x -> x.id().equals(vid)).findFirst().orElse(null);
                        if (existing != null) {
                            next.add(new FakeVariant(existing.id(), existing.itemId(), v.path("sku").asText(), true));
                        } else {
                            long n = ids.incrementAndGet();
                            next.add(new FakeVariant("gid://shopify/ProductVariant/" + n, "gid://shopify/InventoryItem/" + n, v.path("sku").asText(), true));
                        }
                    }
                    p.variants.clear();
                    p.variants.addAll(next);
                    ObjectNode product = JSON.createObjectNode().put("id", p.id);
                    ArrayNode nodes = product.putObject("variants").putArray("nodes");
                    for (FakeVariant v : next) {
                        nodes.addObject().put("id", v.id()).put("sku", v.sku()).putObject("inventoryItem").put("id", v.itemId());
                    }
                    reply(ex, "{\"data\":{\"productSet\":{\"product\":" + product + ",\"userErrors\":[]}}}");
                } else if (query.contains("productDelete(")) {
                    calls.add(Map.entry("productDelete", vars));
                    shop.remove(vars.path("input").path("id").asText());
                    reply(ex, "{\"data\":{\"productDelete\":{\"deletedProductId\":\"x\",\"userErrors\":[]}}}");
                } else if (query.contains("productUpdate(")) {
                    calls.add(Map.entry("productUpdate", vars));
                    FakeProduct p = shop.get(vars.path("product").path("id").asText());
                    if (p != null && vars.path("product").has("status")) p.status = vars.path("product").path("status").asText();
                    reply(ex, "{\"data\":{\"productUpdate\":{\"product\":{\"id\":\"x\"},\"userErrors\":[]}}}");
                } else if (query.contains("productVariantsBulkUpdate(")) {
                    calls.add(Map.entry("productVariantsBulkUpdate", vars));
                    reply(ex, "{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}");
                } else if (query.contains("inventorySetQuantities(")) {
                    calls.add(Map.entry("inventorySetQuantities", vars));
                    reply(ex, "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"id\":\"g\"},\"userErrors\":[]}}}");
                } else if (query.contains("inventoryActivate(")) {
                    calls.add(Map.entry("inventoryActivate", vars));
                    reply(ex, "{\"data\":{\"inventoryActivate\":{\"inventoryLevel\":{\"id\":\"l\"},\"userErrors\":[]}}}");
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
