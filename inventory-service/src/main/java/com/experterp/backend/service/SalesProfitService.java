package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * Profit on sales for a period (phase H4), the way Cin7 Core's Sales Profit Summary, Profit by Sales Representatives
 * and Sales by Product Summary give it:
 * <ul>
 *   <li><b>Sale</b> = invoices less credit notes dated in the period, without VAT; <b>Invoiced</b> the same with VAT.
 *       Issued and paid documents only; stock products only (services and charges are left out, as in Cin7);</li>
 *   <li><b>COGS</b>, FIFO, two ways (Cin7's "Show COGS"): {@link CogsMode#WITHIN_DATES} counts the goods that left
 *       (and came back) in the period, whatever their invoice's date, with the bill corrections made in the period;
 *       {@link CogsMode#ALL} gives each invoice in the period the full cost of what it invoices (its order line's
 *       shipments per unit; the cost price when not shipped yet) and each credit note the cost of the goods it took
 *       back into stock;</li>
 *   <li><b>Profit</b> = Sale − COGS; <b>Gross margin</b> = profit × 100 / sale; <b>Markup</b> = profit × 100 / COGS
 *       (Cin7: GM / (1 − GM)); both empty when sale or COGS is zero, as in Cin7.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SalesProfitService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbc;

    public enum CogsMode { WITHIN_DATES, ALL }

    /** Cin7's layouts: Sales Profit Summary per customer or per order, by sales rep, by product. */
    public enum GroupBy { CUSTOMER, ORDER, REP, REP_CUSTOMER, REP_ORDER, PRODUCT }

    public record Row(Long customerId, String customer, String salesRep, Long saleOrderId, String orderNumber,
                      String invoices, String creditNotes, Long productId, String sku, String productName, String category,
                      BigDecimal quantity, BigDecimal invoiced, BigDecimal sale, BigDecimal cogs, BigDecimal profit,
                      BigDecimal grossMargin, BigDecimal markup) {}

    public record Totals(BigDecimal quantity, BigDecimal invoiced, BigDecimal sale, BigDecimal cogs, BigDecimal profit,
                         BigDecimal grossMargin, BigDecimal markup) {}

    public record Report(LocalDate from, LocalDate to, CogsMode cogs, GroupBy groupBy, List<Row> rows, Totals totals) {}

    // One order (or an invoice without an order) and one product: what everything adds up from
    private static final class Detail {
        Long customerId;
        String customer = "", salesRep = "", orderNumber;
        Long saleOrderId, productId;
        String sku, productName, category;
        final Set<String> invoices = new TreeSet<>(), creditNotes = new TreeSet<>();
        BigDecimal quantity = BigDecimal.ZERO, invoiced = BigDecimal.ZERO, sale = BigDecimal.ZERO, cogs = BigDecimal.ZERO;
    }

    private record OrderInfo(Long customerId, String customer, String salesRep, String orderNumber) {}
    private record ProductInfo(String sku, String name, String category) {}

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, CogsMode mode, GroupBy groupBy) {
        if (from == null || to == null) throw new IllegalArgumentException("Give the period (from and to)");
        if (from.isAfter(to)) throw new IllegalArgumentException("The period starts after it ends");
        if (mode == null) mode = CogsMode.WITHIN_DATES;
        if (groupBy == null) groupBy = GroupBy.CUSTOMER;
        Long t = TenantContext.get();
        Map<String, Detail> details = new LinkedHashMap<>();

        // ---- sale: invoice and credit note lines of stock products dated in the period ----
        record DocLine(long invoiceId, String number, boolean credit, Long saleOrderId, Long customerId, String party,
                       Long saleReturnId, Long productId, Long lineId, BigDecimal qty, BigDecimal net, BigDecimal vat) {}
        List<DocLine> lines = jdbc.query("""
                SELECT i.id, i.invoice_number, i.kind, i.sale_order_id, i.customer_id, i.party_name, i.sale_return_id,
                       it.product_id, it.sale_order_line_id, it.quantity, it.total_price, it.tax_amount
                FROM invoice_items it
                JOIN invoices i ON i.id = it.invoice_id
                JOIN products p ON p.id = it.product_id
                WHERE i.tenant_id = ? AND i.type = 'SALES' AND i.status IN ('ISSUED', 'PAID')
                  AND i.issue_date BETWEEN ? AND ? AND p.product_type = 'STOCK'
                ORDER BY i.issue_date, i.id, it.id""", (rs, n) -> new DocLine(
                rs.getLong(1), rs.getString(2), "CREDIT_NOTE".equals(rs.getString(3)), (Long) rs.getObject(4),
                (Long) rs.getObject(5), rs.getString(6), (Long) rs.getObject(7), rs.getLong(8), (Long) rs.getObject(9),
                nz(rs.getBigDecimal(10)), nz(rs.getBigDecimal(11)), nz(rs.getBigDecimal(12))),
                t, Date.valueOf(from), Date.valueOf(to));

        Map<Long, OrderInfo> orders = new HashMap<>();
        Set<Long> productIds = new HashSet<>();
        lines.forEach(l -> productIds.add(l.productId()));

        // ---- COGS ----
        record Cost(Long saleOrderId, Long productId, BigDecimal amount) {}
        List<Cost> costs = new ArrayList<>();
        List<BigDecimal> docCost = new ArrayList<>();
        if (mode == CogsMode.WITHIN_DATES) {
            Timestamp start = Timestamp.valueOf(from.atStartOfDay()), end = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
            costs.addAll(jdbc.query("""
                    SELECT sol.sale_order_id, sol.product_id, SUM(x.cost) FROM (
                      SELECT m.source_line_id AS line_id, c.quantity * c.unit_cost AS cost FROM cost_consumptions c
                        JOIN stock_movements m ON m.id = c.movement_id
                        WHERE m.tenant_id = ? AND m.type = 'SALE_OUT' AND m.created_at >= ? AND m.created_at < ?
                      UNION ALL
                      SELECT m.source_line_id, a.amount FROM cost_adjustments a
                        JOIN stock_movements m ON m.id = a.movement_id
                        WHERE m.tenant_id = ? AND m.type = 'SALE_OUT' AND a.created_at >= ? AND a.created_at < ?
                      UNION ALL
                      SELECT m.source_line_id, -(l.quantity * l.original_unit_cost) FROM cost_layers l
                        JOIN stock_movements m ON m.id = l.movement_id
                        WHERE m.tenant_id = ? AND m.type = 'SALE_RETURN_IN' AND m.created_at >= ? AND m.created_at < ?
                    ) x JOIN sale_order_lines sol ON sol.id = x.line_id
                    GROUP BY sol.sale_order_id, sol.product_id""", (rs, n) -> new Cost(rs.getLong(1), rs.getLong(2), nz(rs.getBigDecimal(3))),
                    t, start, end, t, start, end, t, start, end));
        } else {
            Map<Long, BigDecimal> unitCost = shippedUnitCost(lines.stream().map(DocLine::lineId).filter(Objects::nonNull).toList());
            Map<Long, BigDecimal> costPrice = costPrices(productIds);
            Map<String, BigDecimal> returned = returnedCost(lines.stream().map(DocLine::saleReturnId).filter(Objects::nonNull).toList());
            for (DocLine l : lines) {
                BigDecimal c;
                if (l.credit()) {
                    // Only goods that came back to stock take their cost off; goods credited but kept stay sold
                    c = l.saleReturnId() == null || l.lineId() == null ? BigDecimal.ZERO
                            : returned.getOrDefault(l.saleReturnId() + ":" + l.lineId(), BigDecimal.ZERO).negate();
                } else {
                    BigDecimal unit = l.lineId() != null ? unitCost.get(l.lineId()) : null;
                    if (unit == null) unit = costPrice.getOrDefault(l.productId(), BigDecimal.ZERO);
                    c = unit.multiply(l.qty());
                }
                docCost.add(c);
            }
        }
        costs.forEach(c -> { if (c.saleOrderId() != null) orders.put(c.saleOrderId(), null); productIds.add(c.productId()); });
        lines.forEach(l -> { if (l.saleOrderId() != null) orders.put(l.saleOrderId(), null); });
        loadOrders(orders);
        Map<Long, ProductInfo> products = loadProducts(productIds);

        for (int i = 0; i < lines.size(); i++) {
            DocLine l = lines.get(i);
            String key = (l.saleOrderId() != null ? "o" + l.saleOrderId() : "i" + l.invoiceId()) + ":" + l.productId();
            Detail d = details.computeIfAbsent(key, k -> newDetail(l.saleOrderId(), l.productId(), orders, products, l.customerId(), l.party()));
            BigDecimal sign = l.credit() ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            d.quantity = d.quantity.add(l.qty().multiply(sign));
            d.sale = d.sale.add(l.net().multiply(sign));
            d.invoiced = d.invoiced.add(l.net().add(l.vat()).multiply(sign));
            (l.credit() ? d.creditNotes : d.invoices).add(l.number());
            if (mode == CogsMode.ALL) d.cogs = d.cogs.add(docCost.get(i));
        }
        for (Cost c : costs) {
            if (c.amount().signum() == 0) continue;
            Detail d = details.computeIfAbsent("o" + c.saleOrderId() + ":" + c.productId(),
                    k -> newDetail(c.saleOrderId(), c.productId(), orders, products, null, null));
            d.cogs = d.cogs.add(c.amount());
        }

        // ---- the layout asked for ----
        GroupBy g = groupBy;
        Map<String, Detail> grouped = new LinkedHashMap<>();
        for (Detail d : details.values()) {
            String key = switch (g) {
                case CUSTOMER -> d.customer + "|" + d.salesRep;
                case REP -> d.salesRep;
                case REP_CUSTOMER -> d.salesRep + "|" + d.customer;
                case ORDER, REP_ORDER -> d.saleOrderId != null ? "o" + d.saleOrderId : "i" + String.join(",", d.invoices) + String.join(",", d.creditNotes);
                case PRODUCT -> "p" + d.productId;
            };
            Detail sum = grouped.computeIfAbsent(key, k -> copyKeys(d, g));
            sum.quantity = sum.quantity.add(d.quantity);
            sum.invoiced = sum.invoiced.add(d.invoiced);
            sum.sale = sum.sale.add(d.sale);
            sum.cogs = sum.cogs.add(d.cogs);
            sum.invoices.addAll(d.invoices);
            sum.creditNotes.addAll(d.creditNotes);
        }
        List<Row> rows = new ArrayList<>(grouped.values().stream().map(SalesProfitService::row).toList());
        Comparator<Row> order = switch (g) {
            case CUSTOMER -> Comparator.comparing(Row::customer, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::salesRep);
            case REP, REP_CUSTOMER -> Comparator.comparing(Row::salesRep, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(r -> r.customer() == null ? "" : r.customer(), String.CASE_INSENSITIVE_ORDER);
            case ORDER -> Comparator.comparing(Row::customer, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(r -> r.orderNumber() == null ? "" : r.orderNumber());
            case REP_ORDER -> Comparator.comparing(Row::salesRep, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(r -> r.orderNumber() == null ? "" : r.orderNumber());
            case PRODUCT -> Comparator.comparing(r -> r.sku() == null ? "" : r.sku(), String.CASE_INSENSITIVE_ORDER);
        };
        rows.sort(order);

        BigDecimal q = BigDecimal.ZERO, inv = BigDecimal.ZERO, s = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (Row r : rows) { q = q.add(r.quantity()); inv = inv.add(r.invoiced()); s = s.add(r.sale()); c = c.add(r.cogs()); }
        Totals totals = new Totals(q.stripTrailingZeros(), money(inv), money(s), money(c), money(s.subtract(c)),
                margin(s, c), markup(s, c));
        return new Report(from, to, mode, groupBy, rows, totals);
    }

    // Per sale order line: what its shipments cost per unit (with later bill corrections), over all time
    private Map<Long, BigDecimal> shippedUnitCost(List<Long> lineIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (lineIds.isEmpty()) return result;
        String ids = array(lineIds);
        jdbc.query("SELECT x.line_id, SUM(x.cost), SUM(x.qty) FROM (" +
                "  SELECT m.source_line_id AS line_id, c.quantity * c.unit_cost AS cost, 0 AS qty FROM cost_consumptions c" +
                "    JOIN stock_movements m ON m.id = c.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id = ANY(?::bigint[])" +
                "  UNION ALL" +
                "  SELECT m.source_line_id, a.amount, 0 FROM cost_adjustments a" +
                "    JOIN stock_movements m ON m.id = a.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id = ANY(?::bigint[])" +
                "  UNION ALL" +
                "  SELECT m.source_line_id, 0, m.quantity FROM stock_movements m WHERE m.type = 'SALE_OUT' AND m.source_line_id = ANY(?::bigint[])" +
                ") x GROUP BY x.line_id", rs -> {
            BigDecimal qty = nz(rs.getBigDecimal(3));
            if (qty.signum() > 0) result.put(rs.getLong(1), nz(rs.getBigDecimal(2)).divide(qty, 6, RoundingMode.HALF_UP));
        }, ids, ids, ids);
        return result;
    }

    // Per credit note's return and order line: the cost the goods came back into stock at
    private Map<String, BigDecimal> returnedCost(List<Long> returnIds) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (returnIds.isEmpty()) return result;
        List<String> refs = returnIds.stream().distinct().map(id -> "RET-" + id).toList();
        jdbc.query("SELECT m.reference_id, m.source_line_id, SUM(l.quantity * l.original_unit_cost) FROM cost_layers l" +
                " JOIN stock_movements m ON m.id = l.movement_id" +
                " WHERE m.tenant_id = ? AND m.type = 'SALE_RETURN_IN' AND m.reference_type = 'SALE_RETURN' AND m.reference_id = ANY(?::text[])" +
                " GROUP BY m.reference_id, m.source_line_id", rs -> {
            result.put(rs.getString(1).substring(4) + ":" + rs.getLong(2), nz(rs.getBigDecimal(3)));
        }, TenantContext.get(), "{" + String.join(",", refs) + "}");
        return result;
    }

    private Map<Long, BigDecimal> costPrices(Set<Long> productIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (productIds.isEmpty()) return result;
        List<Long> ids = List.copyOf(productIds);
        jdbc.query("SELECT id, cost_price FROM products WHERE id = ANY(?::bigint[])",
                rs -> { result.put(rs.getLong(1), nz(rs.getBigDecimal(2))); }, array(ids));
        return result;
    }

    private void loadOrders(Map<Long, OrderInfo> orders) {
        if (orders.isEmpty()) return;
        List<Long> ids = List.copyOf(orders.keySet());
        jdbc.query("SELECT so.id, so.customer_id, COALESCE(c.name, so.customer_name), so.sales_rep, so.order_number" +
                " FROM sale_orders so LEFT JOIN customers c ON c.id = so.customer_id WHERE so.id = ANY(?::bigint[])",
                rs -> { orders.put(rs.getLong(1), new OrderInfo((Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5))); },
                array(ids));
    }

    private Map<Long, ProductInfo> loadProducts(Set<Long> productIds) {
        Map<Long, ProductInfo> result = new HashMap<>();
        if (productIds.isEmpty()) return result;
        List<Long> ids = List.copyOf(productIds);
        jdbc.query("SELECT p.id, p.sku, p.name, c.name FROM products p LEFT JOIN categories c ON c.id = p.category_id" +
                " WHERE p.id = ANY(?::bigint[])",
                rs -> { result.put(rs.getLong(1), new ProductInfo(rs.getString(2), rs.getString(3), rs.getString(4))); }, array(ids));
        return result;
    }

    private static Detail newDetail(Long saleOrderId, Long productId, Map<Long, OrderInfo> orders, Map<Long, ProductInfo> products,
                                    Long customerId, String party) {
        Detail d = new Detail();
        d.saleOrderId = saleOrderId;
        d.productId = productId;
        OrderInfo o = saleOrderId != null ? orders.get(saleOrderId) : null;
        if (o != null) {
            d.customerId = o.customerId();
            d.customer = o.customer() != null ? o.customer() : "";
            d.salesRep = o.salesRep() != null ? o.salesRep() : "";
            d.orderNumber = o.orderNumber();
        } else {
            d.customerId = customerId;
            d.customer = party != null ? party : "";
        }
        ProductInfo p = products.get(productId);
        if (p != null) { d.sku = p.sku(); d.productName = p.name(); d.category = p.category(); }
        return d;
    }

    // The fields the layout shows; the others stay empty
    private static Detail copyKeys(Detail d, GroupBy g) {
        Detail s = new Detail();
        boolean customer = g != GroupBy.REP && g != GroupBy.PRODUCT;
        boolean rep = g != GroupBy.PRODUCT;
        boolean order = g == GroupBy.ORDER || g == GroupBy.REP_ORDER;
        if (customer) { s.customerId = d.customerId; s.customer = d.customer; }
        else s.customer = null;
        s.salesRep = rep ? d.salesRep : null;
        if (order) { s.saleOrderId = d.saleOrderId; s.orderNumber = d.orderNumber; }
        if (g == GroupBy.PRODUCT) { s.productId = d.productId; s.sku = d.sku; s.productName = d.productName; s.category = d.category; }
        return s;
    }

    private static Row row(Detail d) {
        return new Row(d.customerId, d.customer, d.salesRep, d.saleOrderId, d.orderNumber,
                String.join(", ", d.invoices), String.join(", ", d.creditNotes), d.productId, d.sku, d.productName, d.category,
                d.quantity.stripTrailingZeros(), money(d.invoiced), money(d.sale), money(d.cogs), money(d.sale.subtract(d.cogs)),
                margin(d.sale, d.cogs), markup(d.sale, d.cogs));
    }

    // Cin7: empty when the sale or the COGS is zero
    static BigDecimal margin(BigDecimal sale, BigDecimal cogs) {
        if (sale.signum() == 0 || cogs.signum() == 0) return null;
        return sale.subtract(cogs).multiply(HUNDRED).divide(sale, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal markup(BigDecimal sale, BigDecimal cogs) {
        if (sale.signum() == 0 || cogs.signum() == 0) return null;
        return sale.subtract(cogs).multiply(HUNDRED).divide(cogs, 2, RoundingMode.HALF_UP);
    }

    // A Postgres array literal: one parameter however many ids
    private static String array(Collection<Long> ids) {
        StringBuilder b = new StringBuilder("{");
        for (Long id : ids) { if (b.length() > 1) b.append(','); b.append(id); }
        return b.append('}').toString();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
