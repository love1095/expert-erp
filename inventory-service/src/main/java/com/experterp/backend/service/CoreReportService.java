package com.experterp.backend.service;

import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.security.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Cin7 Core's everyday reports (phase I3, spec §8), each as columns and rows the page shows, filters and exports:
 * Sales Overview, Sale Invoices &amp; Credit Notes, Sales Backorder Details, Pending Purchase Orders, Stock Received
 * Not Invoiced and Stock Value Inventory Aging. Each report needs its Cin7 report group (Sale / Purchase / Inventory
 * reports); columns with cost or profit are left out for roles without that tick (I1).
 */
@Service
@RequiredArgsConstructor
public class CoreReportService {

    private final JdbcTemplate jdbc;
    private final AllocationService allocationService;

    public enum Type { TEXT, DATE, NUMBER, MONEY, PERCENT, LINK }

    /** A column; {@code link}: the page a LINK cell opens, with {id} from the row's {@code <key>Id}. */
    public record Column(String key, String label, Type type, String link) {
        static Column of(String key, String label, Type type) { return new Column(key, label, type, null); }
        static Column link(String key, String label, String link) { return new Column(key, label, Type.LINK, link); }
    }

    public record Info(String key, String group, String title, String description, boolean period) {}

    public record Report(String key, String title, String description, LocalDate from, LocalDate to, List<Column> columns,
                         List<Map<String, Object>> rows, Map<String, BigDecimal> totals) {}

    static final List<Info> CATALOGUE = List.of(
            new Info("sales-overview", "Sale", "Sales Overview",
                    "Every sale order of the period with its customer, status, amount invoiced, paid, due, COGS and profit.", true),
            new Info("sale-invoices", "Sale", "Sale Invoices & Credit Notes",
                    "All issued invoices and credit notes of the period: amount, tax, total, paid and due.", true),
            new Info("sales-backorders", "Sale", "Sales Backorder Details",
                    "Confirmed orders waiting on backordered products: what is short, what is in stock and on order.", false),
            new Info("pending-purchase-orders", "Purchase", "Pending Purchase Orders",
                    "Purchase orders not yet fully received or invoiced: ordered, still to receive, still to invoice.", false),
            new Info("stock-received-not-invoiced", "Purchase", "Stock Received Not Invoiced",
                    "Stock received from suppliers who have not billed it yet, with its cost.", false),
            new Info("stock-value-aging", "Inventory", "Stock Value Inventory Aging",
                    "Stock on hand and its FIFO value by how long it has been in stock (0–30 days up to over 180).", false));

    public List<Info> catalogue() {
        return CATALOGUE.stream().filter(i -> Permission.current(groupPermission(i.group()))).toList();
    }

    private static Permission groupPermission(String group) {
        return switch (group) {
            case "Sale" -> Permission.REPORTS_SALE;
            case "Purchase" -> Permission.REPORTS_PURCHASE;
            default -> Permission.REPORTS_INVENTORY;
        };
    }

    @Transactional(readOnly = true)
    public Report run(String key, LocalDate from, LocalDate to) {
        Info info = CATALOGUE.stream().filter(i -> i.key().equals(key)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No such report: " + key));
        if (!Permission.current(groupPermission(info.group()))) {
            throw new AccessDeniedException("Your role does not allow " + info.group() + " reports");
        }
        if (info.period()) {
            if (from == null || to == null) throw new IllegalArgumentException("Give the period (from and to)");
            if (from.isAfter(to)) throw new IllegalArgumentException("The period starts after it ends");
        }
        return switch (key) {
            case "sales-overview" -> salesOverview(info, from, to);
            case "sale-invoices" -> saleInvoices(info, from, to);
            case "sales-backorders" -> backorders(info);
            case "pending-purchase-orders" -> pendingPurchaseOrders(info);
            case "stock-received-not-invoiced" -> receivedNotInvoiced(info);
            default -> stockAging(info);
        };
    }

    // ---- Sale ----

    private Report salesOverview(Info info, LocalDate from, LocalDate to) {
        boolean profit = Permission.current(Permission.SALE_PROFIT);
        List<Column> cols = new ArrayList<>(List.of(Column.link("order", "Order #", "/sale-orders/{id}"), Column.of("date", "Order date", Type.DATE),
                Column.link("customer", "Customer", "/customers/{id}"), Column.of("salesRep", "Sales rep", Type.TEXT),
                Column.of("status", "Status", Type.TEXT), Column.of("fulfilment", "Shipment", Type.TEXT),
                Column.of("ordered", "Ordered", Type.MONEY), Column.of("invoiced", "Invoiced", Type.MONEY),
                Column.of("credited", "Credited", Type.MONEY), Column.of("paid", "Paid", Type.MONEY), Column.of("due", "Due", Type.MONEY)));
        if (profit) cols.addAll(List.of(Column.of("cogs", "COGS", Type.MONEY), Column.of("profit", "Profit", Type.MONEY),
                Column.of("margin", "Gross margin", Type.PERCENT)));
        Long t = TenantContext.get();
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT so.id, so.order_number, COALESCE(so.ordered_at, so.created_at)::date AS d, so.customer_id,
                       COALESCE(c.name, so.customer_name) AS customer, so.sales_rep, so.status, so.fulfilment_status,
                       (SELECT COALESCE(SUM(l.quantity * l.unit_price * (100 - COALESCE(l.discount_percent, 0)) / 100 * (100 + COALESCE(l.tax_rate, 0)) / 100), 0)
                          FROM sale_order_lines l WHERE l.sale_order_id = so.id) AS ordered,
                       COALESCE(inv.invoiced, 0) AS invoiced, COALESCE(inv.credited, 0) AS credited,
                       COALESCE(inv.net, 0) AS net, COALESCE(pay.paid, 0) AS paid,
                       (SELECT COALESCE(SUM(x.cost), 0) FROM (
                          SELECT c2.quantity * c2.unit_cost AS cost FROM cost_consumptions c2 JOIN stock_movements m ON m.id = c2.movement_id
                            JOIN sale_order_lines l ON l.id = m.source_line_id WHERE m.type = 'SALE_OUT' AND l.sale_order_id = so.id
                          UNION ALL
                          SELECT a.amount FROM cost_adjustments a JOIN stock_movements m ON m.id = a.movement_id
                            JOIN sale_order_lines l ON l.id = m.source_line_id WHERE m.type = 'SALE_OUT' AND l.sale_order_id = so.id
                          UNION ALL
                          SELECT -(cl.quantity * cl.original_unit_cost) FROM cost_layers cl JOIN stock_movements m ON m.id = cl.movement_id
                            JOIN sale_order_lines l ON l.id = m.source_line_id WHERE m.type = 'SALE_RETURN_IN' AND l.sale_order_id = so.id) x) AS cogs
                FROM sale_orders so
                LEFT JOIN customers c ON c.id = so.customer_id
                LEFT JOIN (SELECT sale_order_id,
                                  SUM(CASE WHEN kind = 'INVOICE' THEN total_amount ELSE 0 END) AS invoiced,
                                  SUM(CASE WHEN kind = 'CREDIT_NOTE' THEN total_amount ELSE 0 END) AS credited,
                                  SUM(CASE WHEN kind = 'INVOICE' THEN subtotal ELSE -subtotal END) AS net
                           FROM invoices WHERE tenant_id = ? AND type = 'SALES' AND status IN ('ISSUED', 'PAID') GROUP BY sale_order_id) inv
                       ON inv.sale_order_id = so.id
                LEFT JOIN (SELECT i.sale_order_id, SUM(p.amount) AS paid FROM payments p JOIN invoices i ON i.id = p.invoice_id
                           WHERE i.tenant_id = ? AND i.type = 'SALES' AND i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') GROUP BY i.sale_order_id) pay
                       ON pay.sale_order_id = so.id
                WHERE so.tenant_id = ? AND COALESCE(so.ordered_at, so.created_at) >= ? AND COALESCE(so.ordered_at, so.created_at) < ?
                ORDER BY d, so.id""", (rs, n) -> {
            Map<String, Object> r = row();
            r.put("order", rs.getString("order_number"));
            r.put("orderId", rs.getLong("id"));
            r.put("date", rs.getDate("d").toLocalDate());
            r.put("customer", rs.getString("customer"));
            r.put("customerId", rs.getObject("customer_id"));
            r.put("salesRep", rs.getString("sales_rep"));
            r.put("status", cap(rs.getString("status")));
            r.put("fulfilment", cap(rs.getString("fulfilment_status")));
            r.put("ordered", money(rs, "ordered"));
            BigDecimal invoiced = money(rs, "invoiced");
            BigDecimal paid = money(rs, "paid");
            r.put("invoiced", invoiced);
            r.put("credited", money(rs, "credited"));
            r.put("paid", paid);
            r.put("due", invoiced.subtract(paid).max(BigDecimal.ZERO));
            if (profit) {
                BigDecimal net = money(rs, "net"), cogs = money(rs, "cogs");
                r.put("cogs", cogs);
                r.put("profit", net.subtract(cogs));
                r.put("margin", net.signum() == 0 || cogs.signum() == 0 ? null
                        : net.subtract(cogs).multiply(BigDecimal.valueOf(100)).divide(net, 2, RoundingMode.HALF_UP));
            }
            return r;
        }, t, t, t, Date.valueOf(from), Date.valueOf(to.plusDays(1)));
        return report(info, from, to, cols, rows, "ordered", "invoiced", "credited", "paid", "due", "cogs", "profit");
    }

    private Report saleInvoices(Info info, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(Column.link("number", "Number", "/invoices/{id}"), Column.of("type", "Type", Type.TEXT),
                Column.of("date", "Date", Type.DATE), Column.of("dueDate", "Due date", Type.DATE),
                Column.link("customer", "Customer", "/customers/{id}"), Column.link("order", "Order #", "/sale-orders/{id}"),
                Column.of("status", "Status", Type.TEXT), Column.of("net", "Amount", Type.MONEY), Column.of("tax", "Tax", Type.MONEY),
                Column.of("total", "Total", Type.MONEY), Column.of("paid", "Paid", Type.MONEY), Column.of("due", "Due", Type.MONEY));
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT i.id, i.invoice_number, i.kind, i.issue_date, i.due_date, i.customer_id, COALESCE(c.name, i.party_name) AS customer,
                       i.sale_order_id, so.order_number, i.status, i.subtotal, i.tax_total, i.total_amount,
                       (SELECT COALESCE(SUM(p.amount), 0) FROM payments p WHERE p.invoice_id = i.id) AS paid
                FROM invoices i LEFT JOIN customers c ON c.id = i.customer_id LEFT JOIN sale_orders so ON so.id = i.sale_order_id
                WHERE i.tenant_id = ? AND i.type = 'SALES' AND i.status IN ('ISSUED', 'PAID') AND i.issue_date BETWEEN ? AND ?
                ORDER BY i.issue_date, i.id""", (rs, n) -> {
            boolean credit = "CREDIT_NOTE".equals(rs.getString("kind"));
            BigDecimal sign = credit ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            Map<String, Object> r = row();
            r.put("number", rs.getString("invoice_number"));
            r.put("numberId", rs.getLong("id"));
            r.put("type", credit ? "Credit note" : "Invoice");
            r.put("date", rs.getDate("issue_date").toLocalDate());
            r.put("dueDate", rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate());
            r.put("customer", rs.getString("customer"));
            r.put("customerId", rs.getObject("customer_id"));
            r.put("order", rs.getString("order_number"));
            r.put("orderId", rs.getObject("sale_order_id"));
            r.put("status", cap(rs.getString("status")));
            BigDecimal total = money(rs, "total_amount"), paid = money(rs, "paid");
            r.put("net", money(rs, "subtotal").multiply(sign));
            r.put("tax", money(rs, "tax_total").multiply(sign));
            r.put("total", total.multiply(sign));
            r.put("paid", paid.multiply(sign));
            r.put("due", total.subtract(paid).max(BigDecimal.ZERO).multiply(sign));
            return r;
        }, TenantContext.get(), Date.valueOf(from), Date.valueOf(to));
        return report(info, from, to, cols, rows, "net", "tax", "total", "paid", "due");
    }

    private Report backorders(Info info) {
        Map<Long, AllocationService.LineAllocation> alloc = allocationService.forAllOrders();
        Map<Long, BigDecimal> shortBy = new HashMap<>();
        alloc.forEach((lineId, a) -> { if (a.backordered().signum() > 0) shortBy.put(lineId, a.backordered()); });
        List<Column> cols = List.of(Column.link("order", "Order #", "/sale-orders/{id}"), Column.of("date", "Order date", Type.DATE),
                Column.of("shipBy", "Ship by", Type.DATE), Column.link("customer", "Customer", "/customers/{id}"),
                Column.link("sku", "SKU", "/products/{id}"), Column.of("product", "Product", Type.TEXT),
                Column.of("ordered", "Ordered", Type.NUMBER), Column.of("shipped", "Shipped", Type.NUMBER),
                Column.of("backordered", "Backordered", Type.NUMBER), Column.of("available", "Available", Type.NUMBER),
                Column.of("onOrder", "On order", Type.NUMBER));
        if (shortBy.isEmpty()) return report(info, null, null, cols, List.of(), "backordered");
        String ids = array(shortBy.keySet());
        Long t = TenantContext.get();
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT l.id, so.id AS so_id, so.order_number, COALESCE(so.ordered_at, so.created_at)::date AS d, so.ship_by,
                       so.customer_id, COALESCE(c.name, so.customer_name) AS customer, p.id AS product_id, p.sku, p.name,
                       l.quantity, l.fulfilled_quantity,
                       (SELECT COALESCE(SUM(CASE WHEN m.type LIKE '%\\_IN' THEN m.quantity ELSE -m.quantity END), 0)
                          FROM stock_movements m WHERE m.product_id = p.id AND m.tenant_id = ?) AS on_hand,
                       (SELECT COALESCE(SUM(pl.quantity - pl.received_quantity - COALESCE(pl.closed_short_quantity, 0)), 0)
                          FROM purchase_order_lines pl JOIN purchase_orders po ON po.id = pl.purchase_order_id
                          WHERE pl.product_id = p.id AND po.tenant_id = ? AND po.status = 'ORDERED') AS on_order
                FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id JOIN products p ON p.id = l.product_id
                LEFT JOIN customers c ON c.id = so.customer_id
                WHERE so.tenant_id = ? AND l.id = ANY(?::bigint[])
                ORDER BY so.ship_by NULLS LAST, d, so.id""", (rs, n) -> {
            Map<String, Object> r = row();
            r.put("order", rs.getString("order_number"));
            r.put("orderId", rs.getLong("so_id"));
            r.put("date", rs.getDate("d").toLocalDate());
            r.put("shipBy", rs.getDate("ship_by") == null ? null : rs.getDate("ship_by").toLocalDate());
            r.put("customer", rs.getString("customer"));
            r.put("customerId", rs.getObject("customer_id"));
            r.put("sku", rs.getString("sku"));
            r.put("skuId", rs.getLong("product_id"));
            r.put("product", rs.getString("name"));
            r.put("ordered", qty(rs.getBigDecimal("quantity")));
            r.put("shipped", qty(rs.getBigDecimal("fulfilled_quantity")));
            r.put("backordered", qty(shortBy.get(rs.getLong("id"))));
            r.put("available", qty(rs.getBigDecimal("on_hand")));
            r.put("onOrder", qty(rs.getBigDecimal("on_order")));
            return r;
        }, t, t, t, ids);
        return report(info, null, null, cols, rows, "backordered");
    }

    // ---- Purchase ----

    private Report pendingPurchaseOrders(Info info) {
        boolean prices = Permission.current(Permission.PURCHASE_PRICES);
        List<Column> cols = new ArrayList<>(List.of(Column.link("order", "PO #", "/purchase-orders/{id}"),
                Column.of("date", "Ordered", Type.DATE), Column.of("requiredBy", "Required by", Type.DATE),
                Column.of("supplier", "Supplier", Type.TEXT), Column.of("status", "Status", Type.TEXT),
                Column.of("ordered", "Qty ordered", Type.NUMBER), Column.of("toReceive", "To receive", Type.NUMBER),
                Column.of("toInvoice", "To invoice", Type.NUMBER)));
        if (prices) cols.add(Column.of("total", "Order total (ex VAT)", Type.MONEY));
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT po.id, po.order_number, COALESCE(po.ordered_at, po.created_at)::date AS d, po.required_by, po.supplier_name, po.status,
                       SUM(pl.quantity) AS ordered,
                       SUM(GREATEST(pl.quantity - pl.received_quantity - COALESCE(pl.closed_short_quantity, 0), 0)) AS to_receive,
                       SUM(GREATEST(pl.quantity - COALESCE(pl.closed_short_quantity, 0) - pl.billed_quantity, 0)) AS to_invoice,
                       SUM(pl.quantity * pl.unit_cost * (100 - COALESCE(pl.discount_percent, 0)) / 100) AS total
                FROM purchase_orders po JOIN purchase_order_lines pl ON pl.purchase_order_id = po.id
                WHERE po.tenant_id = ? AND po.status NOT IN ('CANCELLED')
                GROUP BY po.id
                HAVING SUM(GREATEST(pl.quantity - pl.received_quantity - COALESCE(pl.closed_short_quantity, 0), 0)) > 0
                    OR SUM(GREATEST(pl.received_quantity - pl.billed_quantity, 0)) > 0
                ORDER BY po.required_by NULLS LAST, d, po.id""", (rs, n) -> {
            Map<String, Object> r = row();
            r.put("order", rs.getString("order_number"));
            r.put("orderId", rs.getLong("id"));
            r.put("date", rs.getDate("d").toLocalDate());
            r.put("requiredBy", rs.getDate("required_by") == null ? null : rs.getDate("required_by").toLocalDate());
            r.put("supplier", rs.getString("supplier_name"));
            r.put("status", cap(rs.getString("status")));
            r.put("ordered", qty(rs.getBigDecimal("ordered")));
            r.put("toReceive", qty(rs.getBigDecimal("to_receive")));
            r.put("toInvoice", qty(rs.getBigDecimal("to_invoice")));
            if (prices) r.put("total", money(rs, "total"));
            return r;
        }, TenantContext.get());
        return report(info, null, null, cols, rows, "ordered", "toReceive", "toInvoice", "total");
    }

    private Report receivedNotInvoiced(Info info) {
        boolean prices = Permission.current(Permission.PURCHASE_PRICES);
        List<Column> cols = new ArrayList<>(List.of(Column.link("order", "PO #", "/purchase-orders/{id}"),
                Column.of("received", "Last received", Type.DATE), Column.of("supplier", "Supplier", Type.TEXT),
                Column.link("sku", "SKU", "/products/{id}"), Column.of("product", "Product", Type.TEXT),
                Column.of("receivedQty", "Received", Type.NUMBER), Column.of("billedQty", "Invoiced", Type.NUMBER),
                Column.of("notInvoiced", "Not invoiced", Type.NUMBER)));
        if (prices) cols.addAll(List.of(Column.of("unitCost", "Unit cost", Type.MONEY), Column.of("value", "Value", Type.MONEY)));
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT po.id, po.order_number, po.received_at, po.supplier_name, p.id AS product_id, p.sku, p.name,
                       pl.received_quantity, pl.billed_quantity, pl.unit_cost * (100 - COALESCE(pl.discount_percent, 0)) / 100 AS unit_cost
                FROM purchase_order_lines pl JOIN purchase_orders po ON po.id = pl.purchase_order_id JOIN products p ON p.id = pl.product_id
                WHERE po.tenant_id = ? AND po.status <> 'CANCELLED' AND pl.received_quantity > pl.billed_quantity
                ORDER BY po.received_at NULLS LAST, po.id, pl.id""", (rs, n) -> {
            BigDecimal received = rs.getBigDecimal("received_quantity"), billed = rs.getBigDecimal("billed_quantity");
            BigDecimal open = received.subtract(billed);
            Map<String, Object> r = row();
            r.put("order", rs.getString("order_number"));
            r.put("orderId", rs.getLong("id"));
            r.put("received", rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toLocalDateTime().toLocalDate());
            r.put("supplier", rs.getString("supplier_name"));
            r.put("sku", rs.getString("sku"));
            r.put("skuId", rs.getLong("product_id"));
            r.put("product", rs.getString("name"));
            r.put("receivedQty", qty(received));
            r.put("billedQty", qty(billed));
            r.put("notInvoiced", qty(open));
            if (prices) {
                BigDecimal unit = rs.getBigDecimal("unit_cost").setScale(4, RoundingMode.HALF_UP);
                r.put("unitCost", unit);
                r.put("value", open.multiply(unit).setScale(2, RoundingMode.HALF_UP));
            }
            return r;
        }, TenantContext.get());
        return report(info, null, null, cols, rows, "notInvoiced", "value");
    }

    // ---- Inventory ----

    private static final int[] BUCKETS = {30, 60, 90, 180};

    private Report stockAging(Info info) {
        if (!Permission.current(Permission.STOCK_VALUE)) throw new AccessDeniedException("Your role does not allow seeing stock value");
        List<Column> cols = new ArrayList<>(List.of(Column.of("warehouse", "Location", Type.TEXT), Column.link("sku", "SKU", "/products/{id}"),
                Column.of("product", "Product", Type.TEXT), Column.of("onHand", "On hand", Type.NUMBER),
                Column.of("value", "Stock value", Type.MONEY), Column.of("averageAge", "Average age (days)", Type.NUMBER)));
        String[] labels = {"0–30 days", "31–60 days", "61–90 days", "91–180 days", "Over 180 days"};
        for (int b = 0; b < labels.length; b++) cols.add(Column.of("b" + b, labels[b], Type.MONEY));
        LocalDate today = LocalDate.now();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        Map<String, BigDecimal[]> age = new HashMap<>();
        jdbc.query("""
                SELECT w.name, p.id, p.sku, p.name, l.received_at::date, l.remaining, l.unit_cost
                FROM cost_layers l JOIN products p ON p.id = l.product_id JOIN warehouses w ON w.id = l.warehouse_id
                WHERE l.tenant_id = ? AND l.remaining > 0
                ORDER BY w.name, p.sku, l.received_at""", (ResultSet rs) -> {
            String k = rs.getString(1) + "|" + rs.getLong(2);
            Map<String, Object> r = byKey.computeIfAbsent(k, x -> {
                Map<String, Object> m = row();
                try {
                    m.put("warehouse", rs.getString(1));
                    m.put("sku", rs.getString(3));
                    m.put("skuId", rs.getLong(2));
                    m.put("product", rs.getString(4));
                } catch (SQLException e) { throw new IllegalStateException(e); }
                m.put("onHand", BigDecimal.ZERO);
                m.put("value", BigDecimal.ZERO);
                for (int b = 0; b < 5; b++) m.put("b" + b, BigDecimal.ZERO);
                return m;
            });
            long days = ChronoUnit.DAYS.between(rs.getDate(5).toLocalDate(), today);
            BigDecimal remaining = rs.getBigDecimal(6), value = remaining.multiply(rs.getBigDecimal(7));
            int b = 0;
            while (b < BUCKETS.length && days > BUCKETS[b]) b++;
            r.put("onHand", ((BigDecimal) r.get("onHand")).add(remaining));
            r.put("value", ((BigDecimal) r.get("value")).add(value));
            r.put("b" + b, ((BigDecimal) r.get("b" + b)).add(value));
            BigDecimal[] a = age.computeIfAbsent(k, x -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(remaining.multiply(BigDecimal.valueOf(days)));
            a[1] = a[1].add(remaining);
        }, TenantContext.get());
        List<Map<String, Object>> rows = new ArrayList<>();
        byKey.forEach((k, r) -> {
            r.put("onHand", qty((BigDecimal) r.get("onHand")));
            r.put("value", ((BigDecimal) r.get("value")).setScale(2, RoundingMode.HALF_UP));
            for (int b = 0; b < 5; b++) r.put("b" + b, ((BigDecimal) r.get("b" + b)).setScale(2, RoundingMode.HALF_UP));
            BigDecimal[] a = age.get(k);
            r.put("averageAge", a[1].signum() == 0 ? null : a[0].divide(a[1], 0, RoundingMode.HALF_UP));
            rows.add(r);
        });
        return report(info, null, null, cols, rows, "value", "b0", "b1", "b2", "b3", "b4");
    }

    // ---- helpers ----

    private static Report report(Info info, LocalDate from, LocalDate to, List<Column> cols, List<Map<String, Object>> rows, String... sum) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Set<String> present = new HashSet<>();
        cols.forEach(c -> present.add(c.key()));
        for (String k : sum) {
            if (!present.contains(k)) continue;
            BigDecimal s = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) if (r.get(k) instanceof BigDecimal v) s = s.add(v);
            totals.put(k, s);
        }
        return new Report(info.key(), info.title(), info.description(), from, to, cols, rows, totals);
    }

    private static Map<String, Object> row() {
        return new LinkedHashMap<>();
    }

    private static BigDecimal money(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal qty(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static String cap(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String array(Collection<Long> ids) {
        StringBuilder b = new StringBuilder("{");
        for (Long id : ids) { if (b.length() > 1) b.append(','); b.append(id); }
        return b.append('}').toString();
    }
}
