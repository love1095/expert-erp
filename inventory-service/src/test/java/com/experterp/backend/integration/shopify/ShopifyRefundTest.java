package com.experterp.backend.integration.shopify;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.security.SecretCipher;
import com.experterp.backend.service.InvoiceService;
import com.experterp.backend.service.SaleOrderService;
import com.experterp.backend.service.StockMovementService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 7.4 (F): VAT-inclusive Shopify prices, cancellations and refunds to review (business-rules.md §5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopifyRefundTest {

    static final String SECRET = "shpss_refund_secret";

    @Autowired MockMvc mvc;
    @Autowired SecretCipher cipher;
    @Autowired TenantRepository tenantRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ShopifyConnectionRepository connectionRepository;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired ShopifyRefundRepository refundRepository;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ShopifyRefundService refundService;

    Long tenantId;
    String shop;
    String helmet;
    Warehouse wh;
    long orderId;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Tenant t = new Tenant();
        t.setName("Refund " + id);
        t.setSlug("refund-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        wh = new Warehouse();
        wh.setName("WH-" + id);
        wh = warehouseRepository.save(wh);
        helmet = "HELMET-" + id;
        Product p = new Product();
        p.setSku(helmet);
        p.setName("Helmet");
        p.setUnit("pcs");
        p = productRepository.save(p);
        movementService.createMovement(p, wh, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
        shop = "shop-" + id + ".myshopify.com";
        ShopifyConnection c = new ShopifyConnection();
        c.setShopDomain(shop);
        c.setClientId("client");
        c.setClientSecretEncrypted(cipher.encrypt(SECRET));
        c.setStatus(ShopifyConnection.Status.CONNECTED);
        connectionRepository.save(c);
        TenantContext.clear();
        orderId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pricesIncludeVatSoTheErpTotalIsWhatTheCustomerPaidShippingIncluded() throws Exception {
        // 3 × £9.99 + £4.95 shipping = £34.92 paid
        send("orders/create", order(List.of(item(1, helmet, 3, "9.99")), "34.92", shipping("Royal Mail", "4.95"), ""));
        SaleOrderResponse so = order();
        assertThat(so.getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);
        assertThat(so.isNeedsReview()).isFalse();
        assertThat(so.getTotal()).isEqualByComparingTo("34.92");
        assertThat(so.getLines().get(0).getTaxRate()).isEqualByComparingTo("20");
        // £29.97 cannot be split with VAT rounded per line (£24.97 + £4.99 = £29.96, £24.98 + £5.00 = £29.98):
        // the line is £29.96; £4.95 shipping is the same (£4.12 + £0.82 = £4.94): a 2p rounding charge makes the order
        // what the customer paid
        assertThat(so.getLines().get(0).getNetAmount().add(so.getLines().get(0).getTaxAmount())).isEqualByComparingTo("29.96");
        assertThat(so.getCharges()).anySatisfy(c -> assertThat(c.getDescription()).isEqualTo("Shipping: Royal Mail"));
        assertThat(so.getCharges()).anySatisfy(c -> {
            assertThat(c.getDescription()).isEqualTo(ShopifyOrderImporter.ROUNDING_CHARGE);
            assertThat(c.getNetAmount()).isEqualByComparingTo("0.02");
        });
    }

    @Test
    void everyVatIncludedAmountSplitsExactlyOrAPennyUnder() {
        int under = 0;
        for (int pence = 1; pence <= 3000; pence++) {
            BigDecimal gross = BigDecimal.valueOf(pence, 2);
            VatIncluded.Split s = VatIncluded.split(gross, BigDecimal.ONE, new BigDecimal("20"));
            BigDecimal vat = com.experterp.backend.service.SaleAmounts.tax(s.net(), new BigDecimal("20"));
            assertThat(s.net().add(vat).add(s.shortBy())).as(gross.toPlainString()).isEqualByComparingTo(gross);
            assertThat(s.shortBy()).as(gross.toPlainString()).isIn(new BigDecimal("0.00"), new BigDecimal("0.01"));
            if (s.shortBy().signum() > 0) under++;
        }
        // About one amount in six cannot be reached with VAT rounded per line
        assertThat(under).isBetween(400, 600);
    }

    @Test
    void aPennyOfVatRoundingIsMadeUpAndABiggerDifferenceNeedsReview() throws Exception {
        // £0.09 cannot be split (net 7p + 1p VAT = 8p; 8p + 2p = 10p): 1p rounding charge
        send("orders/create", order(List.of(item(1, helmet, 1, "0.09")), "0.09", "", ""));
        SaleOrderResponse so = order();
        assertThat(so.getTotal()).isEqualByComparingTo("0.09");
        assertThat(so.getCharges()).anySatisfy(c -> assertThat(c.getDescription()).isEqualTo(ShopifyOrderImporter.ROUNDING_CHARGE));

        // Shopify says £50 for a £25 order: something the ERP does not know about
        orderId++;
        send("orders/create", order(List.of(item(1, helmet, 1, "25.00")), "50.00", "", ""));
        SaleOrderResponse odd = order();
        assertThat(odd.getStatus()).isEqualTo(SaleOrder.Status.DRAFT);
        assertThat(odd.isNeedsReview()).isTrue();
        assertThat(odd.getReviewReason()).contains("£25.00 is not Shopify's £50.00");
    }

    @Test
    void cancelledInShopifyBeforeShippingCancelsTheOrderAfterShippingItIsLeftAlone() throws Exception {
        send("orders/create", order(List.of(item(1, helmet, 2, "25.00")), "50.00", "", ""));
        assertThat(order().getStatus()).isEqualTo(SaleOrder.Status.CONFIRMED);
        send("orders/updated", cancelled(order(List.of(item(1, helmet, 2, "25.00")), "50.00", "", "")));
        assertThat(order().getStatus()).isEqualTo(SaleOrder.Status.CANCELLED);

        // Shipped first: left as it is (later, business-rules.md §5)
        orderId++;
        send("orders/create", order(List.of(item(1, helmet, 1, "25.00")), "25.00", "", ""));
        ship(order().getId());
        send("orders/updated", cancelled(order(List.of(item(1, helmet, 1, "25.00")), "25.00", "", "")));
        assertThat(order().getStatus()).isEqualTo(SaleOrder.Status.COMPLETED);
    }

    @Test
    void aRefundWaitsForReviewThenBecomesACreditNoteWithTheGoodsBackInStock() throws Exception {
        String items = "[" + item(1, helmet, 2, "25.00") + "]";
        send("orders/create", order(List.of(item(1, helmet, 2, "25.00")), "54.95", shipping("Courier", "4.95"), ""));
        Long soId = order().getId();
        ship(soId);
        InvoiceResponse inv = asTenant(() -> {
            OrderInvoiceRequest r = new OrderInvoiceRequest();
            r.setIssue(true);
            return invoiceService.invoiceOrder(soId, r);
        });
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("54.95");

        // One helmet sent back and refunded with the shipping: £29.95
        String withRefund = order(List.of(item(1, helmet, 2, "25.00")), "54.95", shipping("Courier", "4.95"),
                refund(7001, "29.95", "Changed mind", refundLine(1, 1, "25.00", "return")));
        send("orders/updated", withRefund);
        send("orders/updated", withRefund);   // seen again: still one refund
        List<ShopifyRefundService.RefundView> refunds = asTenant(() -> refundService.forOrder(soId));
        assertThat(refunds).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ShopifyRefund.Status.TO_REVIEW);
            assertThat(r.amount()).isEqualByComparingTo("29.95");
            assertThat(r.otherAmount()).isEqualByComparingTo("4.95");
            assertThat(r.lines()).singleElement().satisfies(l -> {
                assertThat(l.sku()).isEqualTo(helmet);
                assertThat(l.restockType()).isEqualTo("return");
            });
        });
        // Nothing credited by itself
        assertThat(asTenant(() -> invoiceService.forOrder(soId)).stream().filter(i -> i.getKind() == Invoice.Kind.CREDIT_NOTE)).isEmpty();

        Long lineId = order().getLines().get(0).getId();
        var done = asTenant(() -> refundService.apply(soId, refunds.get(0).id(), new ShopifyRefundService.ApplyRequest(List.of(lineId))));
        assertThat(done.status()).isEqualTo(ShopifyRefund.Status.DONE);
        assertThat(done.outcome()).startsWith("Credit note").contains("(£29.95)").contains("1 line(s) back in stock");
        assertThat(order().getLines().get(0).getReturnedQuantity()).isEqualByComparingTo("1");
        assertThatThrownBy(() -> asTenant(() -> refundService.apply(soId, refunds.get(0).id(), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reviewed already");
    }

    @Test
    void aRefundBeforeShippingCancelsTheQuantityAndOneWithNothingToDoIsDismissed() throws Exception {
        send("orders/create", order(List.of(item(1, helmet, 3, "10.00")), "30.00", "", ""));
        Long soId = order().getId();
        send("orders/updated", order(List.of(item(1, helmet, 3, "10.00")), "30.00", "",
                refund(8001, "10.00", null, refundLine(1, 1, "10.00", "cancel"))));
        Long refundId = asTenant(() -> refundService.forOrder(soId)).get(0).id();
        var done = asTenant(() -> refundService.apply(soId, refundId, null));
        assertThat(done.outcome()).contains("1 × " + helmet + " cancelled on the order");
        assertThat(order().getLines().get(0).getRemainingQuantity()).isEqualByComparingTo("2");

        // £5 goodwill with no invoice: nothing the ERP can do, so it is dismissed with a reason
        send("orders/updated", order(List.of(item(1, helmet, 3, "10.00")), "30.00", "",
                refund(8001, "10.00", null, refundLine(1, 1, "10.00", "cancel")) + "," + refund(8002, "5.00", "Goodwill")));
        Long goodwill = asTenant(() -> refundService.forOrder(soId)).stream().filter(r -> r.status() == ShopifyRefund.Status.TO_REVIEW)
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> asTenant(() -> refundService.apply(soId, goodwill, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dismiss it");
        assertThatThrownBy(() -> asTenant(() -> refundService.dismiss(soId, goodwill, " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(asTenant(() -> refundService.dismiss(soId, goodwill, "Paid back from the till")).outcome())
                .isEqualTo("Dismissed: Paid back from the till");
        assertThat(asTenant(() -> refundService.toReview())).isZero();
    }

    // ---- helpers ----

    private void send(String topic, String body) throws Exception {
        mvc.perform(post("/api/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Shopify-Shop-Domain", shop)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Event-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Hmac-Sha256", ShopifyWebhookTest.sign(body, SECRET)))
                .andExpect(status().isOk());
    }

    private SaleOrderResponse order() {
        return asTenant(() -> saleOrderService.findById(saleOrderRepository
                .findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, String.valueOf(orderId)).orElseThrow().getId()));
    }

    private void ship(Long soId) {
        asTenant(() -> saleOrderService.fulfill(soId, null));
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String order(List<String> items, String total, String shipping, String refunds) {
        return "{\"id\":" + orderId + ",\"name\":\"#" + orderId + "\",\"financial_status\":\"paid\",\"currency\":\"GBP\","
                + "\"taxes_included\":true,\"total_price\":\"" + total + "\",\"created_at\":\"2026-11-03T09:30:00+00:00\","
                + "\"cancelled_at\":null,\"email\":null,\"phone\":null,\"customer\":null,"
                + "\"line_items\":[" + String.join(",", items) + "],\"shipping_lines\":[" + shipping + "],\"refunds\":[" + refunds + "]}";
    }

    private static String cancelled(String orderJson) {
        return orderJson.replace("\"cancelled_at\":null", "\"cancelled_at\":\"2026-11-03T10:00:00+00:00\"");
    }

    private static String item(long id, String sku, int qty, String price) {
        return "{\"id\":" + id + ",\"sku\":\"" + sku + "\",\"title\":\"Helmet\",\"quantity\":" + qty + ",\"price\":\"" + price
                + "\",\"discount_allocations\":[]}";
    }

    private static String shipping(String title, String price) {
        return "{\"title\":\"" + title + "\",\"price\":\"" + price + "\"}";
    }

    private static String refund(long id, String amount, String note, String... lines) {
        return "{\"id\":" + id + ",\"created_at\":\"2026-11-03T11:00:00+00:00\",\"note\":" + (note == null ? "null" : "\"" + note + "\"")
                + ",\"refund_line_items\":[" + String.join(",", lines) + "],"
                + "\"transactions\":[{\"kind\":\"refund\",\"status\":\"success\",\"amount\":\"" + amount + "\"}]}";
    }

    private static String refundLine(long lineItemId, int qty, String subtotal, String restockType) {
        return "{\"line_item_id\":" + lineItemId + ",\"quantity\":" + qty + ",\"subtotal\":" + subtotal
                + ",\"total_tax\":0,\"restock_type\":\"" + restockType + "\"}";
    }
}
