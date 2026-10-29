package com.experterp.backend.service;

import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.DashboardResponse.*;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * Read-only figures for the dashboard. Queries are native SQL (Hibernate's tenant filter does not
 * apply to them), so every query filters on tenant_id explicitly.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    // Stock really entering / leaving the business; transfers only move it between warehouses
    private static final String UNITS_IN_TYPES = "'PURCHASE_IN','ADJUSTMENT_IN','SALE_RETURN_IN'";
    private static final String UNITS_OUT_TYPES = "'SALE_OUT','ADJUSTMENT_OUT'";

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DashboardResponse get(int days) {
        Long tenantId = TenantContext.get();
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        var p = new MapSqlParameterSource()
                .addValue("t", tenantId)
                .addValue("from", Timestamp.valueOf(from.atStartOfDay()));

        // ---- stock per product (value + low stock) ----
        BigDecimal stockValue = BigDecimal.ZERO;
        List<LowStock> lowStock = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT p.id, p.sku, p.name, p.min_stock, p.cost_price, " +
                "       COALESCE(SUM(CASE WHEN m.type IN (" + StockMovementRepository.IN_TYPES + ") " +
                "                    THEN m.quantity ELSE -m.quantity END), 0) AS stock " +
                "  FROM products p " +
                "  LEFT JOIN stock_movements m ON m.product_id = p.id AND m.tenant_id = :t " +
                " WHERE p.tenant_id = :t " +
                " GROUP BY p.id, p.sku, p.name, p.min_stock, p.cost_price", p)) {
            BigDecimal stock = dec(r.get("stock"));
            BigDecimal cost = (BigDecimal) r.get("cost_price");
            BigDecimal min = (BigDecimal) r.get("min_stock");
            if (cost != null && stock.signum() > 0) stockValue = stockValue.add(stock.multiply(cost));
            if (min != null && min.signum() > 0 && stock.compareTo(min) < 0) {
                lowStock.add(new LowStock(((Number) r.get("id")).longValue(), (String) r.get("sku"),
                        (String) r.get("name"), stock, min));
            }
        }
        // Furthest below minimum first
        lowStock.sort(Comparator.comparing((LowStock l) -> l.stock().divide(l.minStock(), 4, RoundingMode.HALF_UP))
                .thenComparing(LowStock::sku));

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
                " WHERE so.tenant_id = :t AND so.status <> 'CANCELLED' AND so.created_at >= :from " +
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
                "  FROM stock_movements WHERE tenant_id = :t AND created_at >= :from " +
                " GROUP BY CAST(created_at AS date)", p,
                rs -> {
                    units.put(rs.getDate("d").toLocalDate(),
                            new BigDecimal[]{rs.getBigDecimal("units_in"), rs.getBigDecimal("units_out")});
                    shipped[0] = shipped[0].add(rs.getBigDecimal("shipped"));
                });

        List<Day> daily = new ArrayList<>();
        long ordersInPeriod = 0;
        BigDecimal salesInPeriod = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            long orders = orderCounts.getOrDefault(d, 0L);
            BigDecimal value = salesValue.getOrDefault(d, BigDecimal.ZERO);
            BigDecimal[] io = units.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            daily.add(new Day(d, orders, value, io[0], io[1]));
            ordersInPeriod += orders;
            salesInPeriod = salesInPeriod.add(value);
        }

        List<TopProduct> topProducts = jdbc.query(
                "SELECT p.id, p.sku, p.name, SUM(m.quantity) AS shipped " +
                "  FROM stock_movements m JOIN products p ON p.id = m.product_id " +
                " WHERE m.tenant_id = :t AND m.type = 'SALE_OUT' AND m.created_at >= :from " +
                " GROUP BY p.id, p.sku, p.name ORDER BY shipped DESC, p.sku LIMIT 5", p,
                (rs, i) -> new TopProduct(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getBigDecimal("shipped")));

        BigDecimal receivables = jdbc.queryForObject(
                "SELECT COALESCE(SUM(i.total_amount - COALESCE(pay.paid, 0)), 0) " +
                "  FROM invoices i " +
                "  LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid FROM payments WHERE tenant_id = :t GROUP BY invoice_id) pay " +
                "         ON pay.invoice_id = i.id " +
                " WHERE i.tenant_id = :t AND i.type = 'SALES' AND i.status = 'ISSUED'", p, BigDecimal.class);

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
                lowStock.size(),
                byStatus.get("CONFIRMED"),
                byStatus.get("DRAFT"),
                ordersInPeriod,
                salesInPeriod,
                shipped[0],
                receivables);

        return new DashboardResponse(days, kpis, byStatus, daily, topProducts,
                lowStock.subList(0, Math.min(10, lowStock.size())), recent);
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }
}
