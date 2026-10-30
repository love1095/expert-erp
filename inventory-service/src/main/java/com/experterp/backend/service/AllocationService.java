package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Stock reserved for sale orders (docs/business-rules.md §1), computed from the order lines — nothing stored, so
 * shipping or cancelling ends a reservation by itself.
 * <p>
 * Reserving orders: CONFIRMED ones, and Shopify orders from the moment they are imported (drafts kept for review
 * included). Their quantity still to ship is <b>reserved</b>; available = on hand − reserved, below 0 when more is
 * promised than there is.
 * <p>
 * Which order gets the stock when there isn't enough: first confirmed, first served (per product and warehouse,
 * by confirmation time, then line). A line's <b>allocated</b> quantity is what the stock on hand covers after the
 * orders before it; the rest of it is <b>backordered</b>.
 */
@Service
@RequiredArgsConstructor
public class AllocationService {

    // Needs :t; aliases so = sale_orders, l = sale_order_lines
    static final String RESERVING =
            "so.tenant_id = :t AND (so.status = 'CONFIRMED' OR (so.status = 'DRAFT' AND so.source = 'SHOPIFY')) " +
            "AND l.quantity - l.fulfilled_quantity - l.cancelled_quantity > 0";
    static final String REMAINING = "(l.quantity - l.fulfilled_quantity - l.cancelled_quantity)";

    /** Reserved quantity per product, all warehouses together: SQL giving (product_id, qty); needs :t. */
    public static final String RESERVED_PER_PRODUCT_SQL =
            "SELECT l.product_id, SUM(" + REMAINING + ") AS qty " +
            "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " +
            " WHERE " + RESERVING + " GROUP BY l.product_id";

    private final NamedParameterJdbcTemplate jdbc;

    /** Allocation of one line of a reserving order. */
    public record LineAllocation(BigDecimal remaining, BigDecimal allocated) {
        public BigDecimal backordered() {
            return remaining.subtract(allocated);
        }
    }

    /** Reserved quantity per product (all warehouses) for the given products; products with none are absent. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> reservedByProduct(Collection<Long> productIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        jdbc.query("SELECT l.product_id, SUM(" + REMAINING + ") AS qty " +
                   "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " +
                   " WHERE " + RESERVING + " AND l.product_id IN (:ids) GROUP BY l.product_id",
                params().addValue("ids", productIds),
                rs -> { out.put(rs.getLong("product_id"), rs.getBigDecimal("qty")); });
        return out;
    }

    /** Reserved quantity per product and warehouse for the given products; key = productId + ":" + warehouseId. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> reservedByProductAndWarehouse(Collection<Long> productIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        jdbc.query("SELECT l.product_id, so.warehouse_id, SUM(" + REMAINING + ") AS qty " +
                   "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " +
                   " WHERE " + RESERVING + " AND l.product_id IN (:ids) GROUP BY l.product_id, so.warehouse_id",
                params().addValue("ids", productIds),
                rs -> { out.put(key(rs.getLong("product_id"), rs.getLong("warehouse_id")), rs.getBigDecimal("qty")); });
        return out;
    }

    public static String key(Long productId, Long warehouseId) {
        return productId + ":" + warehouseId;
    }

    /** Allocation of the open lines of the given orders (lines of orders that reserve nothing are absent). */
    @Transactional(readOnly = true)
    public Map<Long, LineAllocation> forOrders(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) return new HashMap<>();
        return allocate("AND so.id IN (:orders)", params().addValue("orders", orderIds));
    }

    /** Allocation of every open line of every reserving order. */
    @Transactional(readOnly = true)
    public Map<Long, LineAllocation> forAllOrders() {
        return allocate("", params());
    }

    /** Ids of the reserving orders that cannot be covered in full by the stock on hand. */
    @Transactional(readOnly = true)
    public Set<Long> backorderedOrderIds() {
        Set<Long> ids = new HashSet<>();
        jdbc.query(ALLOCATION_SQL + " AND x.remaining > x.allocated", params().addValue("t", TenantContext.get()),
                rs -> { ids.add(rs.getLong("order_id")); });
        return ids;
    }

    // Every reserving line competing for the same product + warehouse, first confirmed first; :t needed.
    // Gives line_id, order_id, remaining, allocated (aliased x so callers can filter).
    private static final String ALLOCATION_SQL =
            "SELECT x.line_id, x.order_id, x.remaining, x.allocated FROM (" +
            " SELECT d.line_id, d.order_id, d.remaining, " +
            "        GREATEST(LEAST(d.remaining, COALESCE(oh.qty, 0) - d.before), 0) AS allocated " +
            "   FROM (SELECT l.id AS line_id, so.id AS order_id, l.product_id, so.warehouse_id, " + REMAINING + " AS remaining, " +
            "                COALESCE(SUM(" + REMAINING + ") OVER (PARTITION BY l.product_id, so.warehouse_id " +
            "                         ORDER BY COALESCE(so.confirmed_at, so.created_at), l.id " +
            "                         ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before " +
            "           FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " +
            "          WHERE " + RESERVING + ") d " +
            "   LEFT JOIN (SELECT product_id, warehouse_id, " +
            "                     SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") THEN quantity ELSE -quantity END) AS qty " +
            "                FROM stock_movements WHERE tenant_id = :t GROUP BY product_id, warehouse_id) oh " +
            "          ON oh.product_id = d.product_id AND oh.warehouse_id = d.warehouse_id" +
            ") x JOIN sale_orders so ON so.id = x.order_id WHERE so.tenant_id = :t ";

    private Map<Long, LineAllocation> allocate(String orderFilter, MapSqlParameterSource p) {
        Map<Long, LineAllocation> out = new HashMap<>();
        jdbc.query(ALLOCATION_SQL + orderFilter, p,
                rs -> { out.put(rs.getLong("line_id"), new LineAllocation(rs.getBigDecimal("remaining"), rs.getBigDecimal("allocated"))); });
        return out;
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource().addValue("t", TenantContext.get());
    }
}
