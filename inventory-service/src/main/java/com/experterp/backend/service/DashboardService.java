package com.experterp.backend.service;

import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.DashboardResponse.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read-only figures for the dashboard. Queries are native SQL (Hibernate's tenant filter does not
 * apply to them), so every query filters on tenant_id explicitly.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    // Longest period: a financial year, leap day included
    public static final int MAX_DAYS = 366;

    // Stock really entering / leaving the business; transfers only move it between warehouses
    private static final String UNITS_IN_TYPES = "'PURCHASE_IN','ADJUSTMENT_IN','SALE_RETURN_IN'";
    private static final String UNITS_OUT_TYPES = "'SALE_OUT','ADJUSTMENT_OUT'";

    // Active stock products whose stock + quantity on (not yet received) purchase orders is below their minimum.
    // Needs :t; the caller appends its own filter, ordering and paging.
    private static final String REORDER_FROM =
            "  FROM products p " +
            "  LEFT JOIN (SELECT product_id, SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") " +
            "                                    THEN quantity ELSE -quantity END) AS qty " +
            "               FROM stock_movements WHERE tenant_id = :t GROUP BY product_id) st ON st.product_id = p.id " +
            "  LEFT JOIN (SELECT l.product_id, SUM(l.quantity) AS qty " +
            "               FROM purchase_order_lines l JOIN purchase_orders po ON po.id = l.purchase_order_id " +
            "              WHERE po.tenant_id = :t AND po.status = 'DRAFT' GROUP BY l.product_id) oo ON oo.product_id = p.id " +
            "  LEFT JOIN suppliers s ON s.id = p.default_supplier_id " +
            " WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND p.product_type = 'STOCK' AND p.min_stock > 0 " +
            "   AND COALESCE(st.qty, 0) + COALESCE(oo.qty, 0) < p.min_stock ";

    // Issued invoices with something left to pay; needs :t and :type
    private static final String UNPAID_INVOICES =
            "  FROM invoices i " +
            "  LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid FROM payments WHERE tenant_id = :t GROUP BY invoice_id) pay " +
            "         ON pay.invoice_id = i.id " +
            " WHERE i.tenant_id = :t AND i.type = :type AND i.status = 'ISSUED' " +
            "   AND i.total_amount - COALESCE(pay.paid, 0) > 0 ";

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DashboardResponse get(int days) {
        LocalDate today = LocalDate.now();
        return get(today.minusDays(days - 1L), today);
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(LocalDate from, LocalDate to) {
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        if (length < 1) throw new IllegalArgumentException("The period must end on or after its start");
        if (length > MAX_DAYS) throw new IllegalArgumentException("The period can be at most " + MAX_DAYS + " days");

        Long tenantId = TenantContext.get();
        LocalDate today = LocalDate.now();
        var p = new MapSqlParameterSource()
                .addValue("t", tenantId)
                .addValue("from", Timestamp.valueOf(from.atStartOfDay()))
                .addValue("until", Timestamp.valueOf(to.plusDays(1).atStartOfDay()))
                .addValue("today", Date.valueOf(today));

        // ---- stock value ----
        BigDecimal stockValue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(st.qty * p.cost_price), 0) " +
                "  FROM products p JOIN (SELECT product_id, SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") " +
                "                                                THEN quantity ELSE -quantity END) AS qty " +
                "                           FROM stock_movements WHERE tenant_id = :t GROUP BY product_id) st ON st.product_id = p.id " +
                " WHERE p.tenant_id = :t AND p.cost_price IS NOT NULL AND st.qty > 0", p, BigDecimal.class);

        // ---- orders ----
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("DRAFT", "CONFIRMED", "COMPLETED", "CANCELLED")) byStatus.put(s, 0L);
        jdbc.query("SELECT status, COUNT(*) AS n FROM sale_orders WHERE tenant_id = :t GROUP BY status", p,
                rs -> { byStatus.put(rs.getString("status"), rs.getLong("n")); });

        // ---- per-day series, every day present ----
        Map<LocalDate, Long> orderCounts = new HashMap<>();
        Map<LocalDate, BigDecimal> salesValue = new HashMap<>();
        jdbc.query(
                "SELECT CAST(so.created_at AS date) AS d, COUNT(DISTINCT so.id) AS orders, " +
                "       COALESCE(SUM((l.quantity - l.cancelled_quantity) * COALESCE(l.unit_price, 0)), 0) AS value " +
                "  FROM sale_orders so JOIN sale_order_lines l ON l.sale_order_id = so.id " +
                " WHERE so.tenant_id = :t AND so.status <> 'CANCELLED' AND so.created_at >= :from AND so.created_at < :until " +
                " GROUP BY CAST(so.created_at AS date)", p,
                rs -> {
                    LocalDate d = rs.getDate("d").toLocalDate();
                    orderCounts.put(d, rs.getLong("orders"));
                    salesValue.put(d, rs.getBigDecimal("value"));
                });

        Map<LocalDate, BigDecimal[]> units = new HashMap<>();
        BigDecimal[] shipped = {BigDecimal.ZERO};
        jdbc.query(
                "SELECT CAST(created_at AS date) AS d, " +
                "       COALESCE(SUM(CASE WHEN type IN (" + UNITS_IN_TYPES + ") THEN quantity END), 0) AS units_in, " +
                "       COALESCE(SUM(CASE WHEN type IN (" + UNITS_OUT_TYPES + ") THEN quantity END), 0) AS units_out, " +
                "       COALESCE(SUM(CASE WHEN type = 'SALE_OUT' THEN quantity END), 0) AS shipped " +
                "  FROM stock_movements WHERE tenant_id = :t AND created_at >= :from AND created_at < :until " +
                " GROUP BY CAST(created_at AS date)", p,
                rs -> {
                    units.put(rs.getDate("d").toLocalDate(),
                            new BigDecimal[]{rs.getBigDecimal("units_in"), rs.getBigDecimal("units_out")});
                    shipped[0] = shipped[0].add(rs.getBigDecimal("shipped"));
                });

        List<Day> daily = new ArrayList<>();
        long ordersInPeriod = 0;
        BigDecimal salesInPeriod = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long orders = orderCounts.getOrDefault(d, 0L);
            BigDecimal value = salesValue.getOrDefault(d, BigDecimal.ZERO);
            BigDecimal[] io = units.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            daily.add(new Day(d, orders, value, io[0], io[1]));
            ordersInPeriod += orders;
            salesInPeriod = salesInPeriod.add(value);
        }

        // ---- best and worst sellers by value, among products ordered in the period ----
        String sellers =
                "SELECT p.id, p.sku, p.name, SUM(l.quantity - l.cancelled_quantity) AS qty, " +
                "       SUM((l.quantity - l.cancelled_quantity) * COALESCE(l.unit_price, 0)) AS value " +
                "  FROM sale_orders so JOIN sale_order_lines l ON l.sale_order_id = so.id JOIN products p ON p.id = l.product_id " +
                " WHERE so.tenant_id = :t AND so.status <> 'CANCELLED' AND so.created_at >= :from AND so.created_at < :until " +
                " GROUP BY p.id, p.sku, p.name HAVING SUM(l.quantity - l.cancelled_quantity) > 0 ";
        List<Seller> top = jdbc.query(sellers + "ORDER BY value DESC, qty DESC, p.sku LIMIT 10", p, this::seller);
        List<Seller> worst = jdbc.query(sellers + "ORDER BY value ASC, qty ASC, p.sku LIMIT 10", p, this::seller);

        // ---- work waiting now ----
        long salesDraftInvoices = count("SELECT COUNT(*) FROM invoices WHERE tenant_id = :t AND type = 'SALES' AND status = 'DRAFT'", p);
        long purchaseDraftInvoices = count("SELECT COUNT(*) FROM invoices WHERE tenant_id = :t AND type = 'PURCHASE' AND status = 'DRAFT'", p);
        var sp = new MapSqlParameterSource(p.getValues()).addValue("type", "SALES");
        var pp = new MapSqlParameterSource(p.getValues()).addValue("type", "PURCHASE");
        SalesStatus sales = new SalesStatus(
                byStatus.get("DRAFT"),
                byStatus.get("CONFIRMED"),
                salesDraftInvoices,
                count("SELECT COUNT(*) " + UNPAID_INVOICES, sp),
                count("SELECT COUNT(*) " + UNPAID_INVOICES + "AND i.due_date < :today", sp));
        PurchaseStatus purchases = new PurchaseStatus(
                count("SELECT COUNT(*) FROM purchase_orders WHERE tenant_id = :t AND status = 'DRAFT'", p),
                purchaseDraftInvoices,
                count("SELECT COUNT(*) " + UNPAID_INVOICES, pp),
                count("SELECT COUNT(*) " + UNPAID_INVOICES + "AND i.due_date < :today", pp));

        BigDecimal receivables = jdbc.queryForObject(
                "SELECT COALESCE(SUM(i.total_amount - COALESCE(pay.paid, 0)), 0) " + UNPAID_INVOICES, sp, BigDecimal.class);

        List<RecentOrder> recent = jdbc.query(
                "SELECT so.id, so.customer_name, so.status, so.fulfilment_status, so.created_at, " +
                "       COALESCE(SUM(l.quantity * COALESCE(l.unit_price, 0)), 0) AS total " +
                "  FROM sale_orders so LEFT JOIN sale_order_lines l ON l.sale_order_id = so.id " +
                " WHERE so.tenant_id = :t " +
                " GROUP BY so.id ORDER BY so.created_at DESC, so.id DESC LIMIT 5", p,
                (rs, i) -> new RecentOrder(rs.getLong("id"), rs.getString("customer_name"), rs.getString("status"),
                        rs.getString("fulfilment_status"), rs.getBigDecimal("total"), rs.getTimestamp("created_at").toLocalDateTime()));

        Kpis kpis = new Kpis(
                count("SELECT COUNT(*) FROM products WHERE tenant_id = :t", p),
                count("SELECT COUNT(*) FROM warehouses WHERE tenant_id = :t", p),
                count("SELECT COUNT(*) FROM customers WHERE tenant_id = :t AND status = 'ACTIVE'", p),
                stockValue,
                count("SELECT COUNT(*) " + REORDER_FROM, p),
                byStatus.get("CONFIRMED"),
                byStatus.get("DRAFT"),
                ordersInPeriod,
                salesInPeriod,
                shipped[0],
                receivables);

        return new DashboardResponse(from, to, (int) length, kpis, byStatus, sales, purchases, daily, top, worst, recent);
    }

    /** The reorder list, lowest stock + on order first; {@code search} matches SKU, name or supplier. */
    @Transactional(readOnly = true)
    public Page<ReorderLine> reorder(String search, int page, int size) {
        var p = new MapSqlParameterSource().addValue("t", TenantContext.get());
        String filter = "";
        if (search != null && !search.isBlank()) {
            filter = "AND (p.sku ILIKE :q OR p.name ILIKE :q OR s.name ILIKE :q) ";
            p.addValue("q", "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = count("SELECT COUNT(*) " + REORDER_FROM + filter, p);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<ReorderLine> rows = jdbc.query(
                "SELECT p.id, p.sku, p.name, s.name AS supplier, p.min_stock, p.reorder_quantity, " +
                "       COALESCE(st.qty, 0) AS stock, COALESCE(oo.qty, 0) AS on_order " +
                REORDER_FROM + filter +
                " ORDER BY COALESCE(st.qty, 0) + COALESCE(oo.qty, 0), p.sku LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    BigDecimal stock = rs.getBigDecimal("stock");
                    BigDecimal onOrder = rs.getBigDecimal("on_order");
                    BigDecimal min = rs.getBigDecimal("min_stock");
                    BigDecimal reorderQty = rs.getBigDecimal("reorder_quantity");
                    BigDecimal suggested = reorderQty != null && reorderQty.signum() > 0
                            ? reorderQty : min.subtract(stock).subtract(onOrder);
                    return new ReorderLine(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                            rs.getString("supplier"), stock, onOrder, min, suggested);
                });
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    private Seller seller(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Seller(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getBigDecimal("qty"), rs.getBigDecimal("value"));
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }
}
