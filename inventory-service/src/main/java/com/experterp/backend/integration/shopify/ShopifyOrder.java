package com.experterp.backend.integration.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of a Shopify order the ERP uses. Webhooks send REST-style JSON (snake_case, numeric ids);
 * the catch-up import uses GraphQL (camelCase, gid:// ids). Both are read into this one shape.
 */
public record ShopifyOrder(
        String id,                 // numeric order id, e.g. "5512345678"
        String name,               // order number shown in Shopify, e.g. "#1001"
        String financialStatus,    // lower case: paid, pending, authorized, partially_refunded, ...
        String currency,
        OffsetDateTime createdAt,
        boolean cancelled,
        String email,
        String phone,
        Customer customer,         // null for guest checkouts without a customer record
        List<Line> lines) {

    public record Customer(String id, String firstName, String lastName, String email, String phone) {
        public String displayName() {
            String n = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
            return n.isEmpty() ? null : n;
        }
    }

    public record Line(String sku, String title, BigDecimal quantity, BigDecimal unitPrice) {}

    /** Paid orders are ready to ship; anything else waits as DRAFT. */
    public boolean isPaid() {
        return "paid".equals(financialStatus) || "partially_refunded".equals(financialStatus);
    }

    // ---- webhook (REST) payload ----

    public static ShopifyOrder fromWebhook(JsonNode o) {
        JsonNode c = o.path("customer");
        Customer customer = c.isMissingNode() || c.isNull() || !c.hasNonNull("id") ? null : new Customer(
                c.get("id").asText(), text(c, "first_name"), text(c, "last_name"), text(c, "email"), text(c, "phone"));

        List<Line> lines = new ArrayList<>();
        for (JsonNode l : o.path("line_items")) {
            BigDecimal qty = new BigDecimal(l.path("quantity").asText("0"));
            BigDecimal price = decimal(l.path("price"));
            // Discounts arrive as allocations on the line; spread them over the units
            BigDecimal discount = BigDecimal.ZERO;
            for (JsonNode a : l.path("discount_allocations")) discount = discount.add(decimal(a.path("amount")));
            BigDecimal unit = qty.signum() > 0 && discount.signum() > 0
                    ? price.subtract(discount.divide(qty, 2, RoundingMode.HALF_UP)) : price;
            lines.add(new Line(text(l, "sku"), text(l, "title"), qty, unit));
        }
        return new ShopifyOrder(o.path("id").asText(), text(o, "name"),
                lower(text(o, "financial_status")), text(o, "currency"), dateTime(text(o, "created_at")),
                o.hasNonNull("cancelled_at"), text(o, "email"), text(o, "phone"), customer, lines);
    }

    // ---- GraphQL node (see ShopifyOrderImportService.ORDERS_QUERY) ----

    public static ShopifyOrder fromGraphql(JsonNode o) {
        JsonNode c = o.path("customer");
        Customer customer = c.isMissingNode() || c.isNull() ? null : new Customer(
                numericId(text(c, "id")), text(c, "firstName"), text(c, "lastName"), null, null);

        List<Line> lines = new ArrayList<>();
        for (JsonNode l : o.path("lineItems").path("nodes")) {
            lines.add(new Line(text(l, "sku"), text(l, "title"), new BigDecimal(l.path("quantity").asText("0")),
                    decimal(l.path("discountedUnitPriceAfterAllDiscountsSet").path("shopMoney").path("amount"))));
        }
        return new ShopifyOrder(numericId(text(o, "id")), text(o, "name"), lower(text(o, "displayFinancialStatus")),
                text(o, "currencyCode"), dateTime(text(o, "createdAt")), o.hasNonNull("cancelledAt"),
                text(o, "email"), text(o, "phone"), customer, lines);
    }

    /** gid://shopify/Order/123 → 123 (webhooks use the bare number) */
    static String numericId(String gid) {
        if (gid == null) return null;
        int slash = gid.lastIndexOf('/');
        return slash >= 0 ? gid.substring(slash + 1) : gid;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(JsonNode n) {
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? BigDecimal.ZERO : new BigDecimal(n.asText());
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime dateTime(String s) {
        return s == null ? null : OffsetDateTime.parse(s);
    }
}
