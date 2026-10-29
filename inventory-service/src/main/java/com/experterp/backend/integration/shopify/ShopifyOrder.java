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
        Address shippingAddress,   // null when the order has nothing to ship
        List<Line> lines) {

    public record Address(String name, String address1, String address2, String city, String zip, String country, String phone) {
        /** One field per line, blanks left out, e.g. for a customer's address. */
        public String formatted() {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[]{address1, address2, city, zip, country}) {
                if (part != null && !part.isBlank()) sb.append(sb.isEmpty() ? "" : "\n").append(part.trim());
            }
            return sb.isEmpty() ? null : sb.toString();
        }
    }

    public record Customer(String id, String firstName, String lastName, String email, String phone) {
        public String displayName() {
            String n = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
            return n.isEmpty() ? null : n;
        }
    }

    // id: Shopify's numeric line item id
    public record Line(String id, String sku, String title, BigDecimal quantity, BigDecimal unitPrice) {}

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
            lines.add(new Line(text(l, "id"), text(l, "sku"), text(l, "title"), qty, unit));
        }
        JsonNode a = o.path("shipping_address");
        Address address = a.isMissingNode() || a.isNull() ? null : new Address(text(a, "name"), text(a, "address1"),
                text(a, "address2"), text(a, "city"), text(a, "zip"), text(a, "country"), text(a, "phone"));
        return new ShopifyOrder(o.path("id").asText(), text(o, "name"),
                lower(text(o, "financial_status")), text(o, "currency"), dateTime(text(o, "created_at")),
                o.hasNonNull("cancelled_at"), text(o, "email"), text(o, "phone"), customer, address, lines);
    }

    // ---- GraphQL node (see ShopifyOrderImportService.ORDERS_QUERY) ----

    public static ShopifyOrder fromGraphql(JsonNode o) {
        JsonNode c = o.path("customer");
        Customer customer = c.isMissingNode() || c.isNull() ? null : new Customer(
                numericId(text(c, "id")), text(c, "firstName"), text(c, "lastName"), null, null);

        List<Line> lines = new ArrayList<>();
        for (JsonNode l : o.path("lineItems").path("nodes")) {
            lines.add(new Line(numericId(text(l, "id")), text(l, "sku"), text(l, "title"), new BigDecimal(l.path("quantity").asText("0")),
                    decimal(l.path("discountedUnitPriceAfterAllDiscountsSet").path("shopMoney").path("amount"))));
        }
        JsonNode a = o.path("shippingAddress");
        Address address = a.isMissingNode() || a.isNull() ? null : new Address(text(a, "name"), text(a, "address1"),
                text(a, "address2"), text(a, "city"), text(a, "zip"), text(a, "country"), text(a, "phone"));
        return new ShopifyOrder(numericId(text(o, "id")), text(o, "name"), lower(text(o, "displayFinancialStatus")),
                text(o, "currencyCode"), dateTime(text(o, "createdAt")), o.hasNonNull("cancelledAt"),
                text(o, "email"), text(o, "phone"), customer, address, lines);
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
