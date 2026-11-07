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

    // Phase P2: a kit line reserves its components (kc), any other line its own product
    static final String KIT_JOIN = " LEFT JOIN kit_components kc ON kc.kit_id = l.product_id ";
    static final String STOCK_PRODUCT = "COALESCE(kc.component_id, l.product_id)";
    // ... in the product's own unit: a line sold by the box of 100 reserves 100 per box (P3)
    static final String STOCK_REMAINING = "(" + REMAINING + " * l.unit_factor * COALESCE(kc.quantity, 1))";

    /** Reserved quantity per product, all warehouses together: SQL giving (product_id, qty); needs :t. */
    public static final String RESERVED_PER_PRODUCT_SQL =
            "SELECT " + STOCK_PRODUCT + " AS product_id, SUM(" + STOCK_REMAINING + ") AS qty " +
            "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " + KIT_JOIN +
            " WHERE " + RESERVING + " GROUP BY " + STOCK_PRODUCT;

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
        jdbc.query("SELECT " + STOCK_PRODUCT + " AS product_id, SUM(" + STOCK_REMAINING + ") AS qty " +
                   "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " + KIT_JOIN +
                   " WHERE " + RESERVING + " AND " + STOCK_PRODUCT + " IN (:ids) GROUP BY " + STOCK_PRODUCT,
                params().addValue("ids", productIds),
                rs -> { out.put(rs.getLong("product_id"), rs.getBigDecimal("qty")); });
        return out;
    }

    /** Reserved quantity per product and warehouse for the given products; key = productId + ":" + warehouseId. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> reservedByProductAndWarehouse(Collection<Long> productIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        jdbc.query("SELECT " + STOCK_PRODUCT + " AS product_id, so.warehouse_id, SUM(" + STOCK_REMAINING + ") AS qty " +
                   "  FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " + KIT_JOIN +
                   " WHERE " + RESERVING + " AND " + STOCK_PRODUCT + " IN (:ids) GROUP BY " + STOCK_PRODUCT + ", so.warehouse_id",
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
    // A kit line competes for each of its components (in component units); it is allocated the whole kits its least
    // covered component makes (P2). Other lines: per = 1, one row, allocated as before.
    private static final String ALLOCATION_SQL =
            "SELECT x.line_id, x.order_id, x.remaining, x.allocated FROM (" +
            " SELECT c.line_id, c.order_id, c.remaining, " +
            "        MIN(CASE WHEN c.kit THEN FLOOR(c.allocated / c.per) ELSE c.allocated END) AS allocated FROM (" +
            " SELECT d.line_id, d.order_id, d.remaining, d.per, d.kit, " +
            "        GREATEST(LEAST(d.remaining * d.per, COALESCE(oh.qty, 0) - d.before), 0) AS allocated " +
            "   FROM (SELECT l.id AS line_id, so.id AS order_id, " + STOCK_PRODUCT + " AS product_id, so.warehouse_id, " + REMAINING + " AS remaining, " +
            "                l.unit_factor * COALESCE(kc.quantity, 1) AS per, (kc.id IS NOT NULL OR l.unit_factor <> 1) AS kit, " +
            "                COALESCE(SUM(" + STOCK_REMAINING + ") OVER (PARTITION BY " + STOCK_PRODUCT + ", so.warehouse_id " +
            "                         ORDER BY COALESCE(so.confirmed_at, so.created_at), l.id " +
            "                         ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before " +
            "           FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id " + KIT_JOIN +
            "          WHERE " + RESERVING + ") d " +
            "   LEFT JOIN (SELECT product_id, warehouse_id, " +
            "                     SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") THEN quantity ELSE -quantity END) AS qty " +
            "                FROM stock_movements WHERE tenant_id = :t GROUP BY product_id, warehouse_id) oh " +
            "          ON oh.product_id = d.product_id AND oh.warehouse_id = d.warehouse_id" +
            " ) c GROUP BY c.line_id, c.order_id, c.remaining" +
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
