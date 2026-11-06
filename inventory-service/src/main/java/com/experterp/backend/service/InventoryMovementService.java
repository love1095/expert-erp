package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * Stock and its FIFO value over a period, per warehouse and product (phase H4), like Cin7 Core's Inventory Movement
 * Summary: quantity and value at the start, what came in, what went out, quantity and value at the end, unit cost.
 * <p>
 * Quantities come from the stock movements by their date. Values come from the cost layers, each flow on its own date:
 * a layer opened (+ quantity × the cost it came in at), a stock-out taking from it (− quantity × the cost it took),
 * and a supplier bill's correction of the stock still in it (on the day the bill was entered). At the end of the last
 * day the value is the same as the FIFO stock value (remaining × unit cost of every layer). Stock that went out with no
 * layer to take from (history below zero, costed at the cost price) opens and closes at once: left out of both sides.
 */
@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final JdbcTemplate jdbc;

    public record Row(Long warehouseId, String warehouse, Long productId, String sku, String product, String unit,
                      Long categoryId, String category, BigDecimal openingQuantity, BigDecimal openingValue,
                      BigDecimal inQuantity, BigDecimal inValue, BigDecimal outQuantity, BigDecimal outValue,
                      BigDecimal closingQuantity, BigDecimal unitCost, BigDecimal closingValue) {}

    public record Totals(BigDecimal openingValue, BigDecimal inValue, BigDecimal outValue, BigDecimal closingValue) {}

    public record Report(LocalDate from, LocalDate to, List<Row> rows, Totals totals) {}

    private static final class Acc {
        BigDecimal openQ = BigDecimal.ZERO, inQ = BigDecimal.ZERO, outQ = BigDecimal.ZERO;
        BigDecimal openV = BigDecimal.ZERO, inV = BigDecimal.ZERO, outV = BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, Long warehouseId, Long categoryId) {
        if (from == null || to == null) throw new IllegalArgumentException("Give the period (from and to)");
        if (from.isAfter(to)) throw new IllegalArgumentException("The period starts after it ends");
        Long t = TenantContext.get();
        Timestamp start = Timestamp.valueOf(from.atStartOfDay()), end = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Map<String, Acc> acc = new HashMap<>();

        // Quantities: every movement up to the end of the period, before it or in it
        jdbc.query("""
                SELECT warehouse_id, product_id,
                       SUM(CASE WHEN created_at < ? THEN CASE WHEN type LIKE '%\\_IN' THEN quantity ELSE -quantity END ELSE 0 END),
                       SUM(CASE WHEN created_at >= ? AND type LIKE '%\\_IN' THEN quantity ELSE 0 END),
                       SUM(CASE WHEN created_at >= ? AND type NOT LIKE '%\\_IN' THEN quantity ELSE 0 END)
                FROM stock_movements WHERE tenant_id = ? AND created_at < ?
                GROUP BY warehouse_id, product_id""", rs -> {
            Acc a = acc.computeIfAbsent(rs.getLong(1) + ":" + rs.getLong(2), k -> new Acc());
            a.openQ = nz(rs.getBigDecimal(3));
            a.inQ = nz(rs.getBigDecimal(4));
            a.outQ = nz(rs.getBigDecimal(5));
        }, start, start, start, t, end);

        // Values: the FIFO flows, each on its date (opening = before the period)
        jdbc.query("""
                SELECT x.warehouse_id, x.product_id,
                       SUM(CASE WHEN x.at < ? THEN x.amount ELSE 0 END),
                       SUM(CASE WHEN x.at >= ? AND x.amount_in THEN x.amount ELSE 0 END),
                       SUM(CASE WHEN x.at >= ? AND NOT x.amount_in THEN -x.amount ELSE 0 END)
                FROM (
                  SELECT l.warehouse_id, l.product_id, m.created_at AS at, l.quantity * l.original_unit_cost AS amount, true AS amount_in
                    FROM cost_layers l JOIN stock_movements m ON m.id = l.movement_id WHERE l.tenant_id = ? AND m.type LIKE '%\\_IN'
                  UNION ALL
                  SELECT l.warehouse_id, l.product_id, m.created_at, -(c.quantity * c.unit_cost), false
                    FROM cost_consumptions c JOIN cost_layers l ON l.id = c.layer_id JOIN stock_movements m ON m.id = c.movement_id
                    JOIN stock_movements lm ON lm.id = l.movement_id
                    WHERE c.tenant_id = ? AND lm.type LIKE '%\\_IN'
                  UNION ALL
                  SELECT l.warehouse_id, l.product_id, a.created_at, a.amount, true
                    FROM cost_adjustments a JOIN cost_layers l ON l.id = a.layer_id
                    WHERE a.tenant_id = ? AND a.movement_id IS NULL
                ) x WHERE x.at < ?
                GROUP BY x.warehouse_id, x.product_id""", rs -> {
            Acc a = acc.computeIfAbsent(rs.getLong(1) + ":" + rs.getLong(2), k -> new Acc());
            a.openV = nz(rs.getBigDecimal(3));
            a.inV = nz(rs.getBigDecimal(4));
            a.outV = nz(rs.getBigDecimal(5));
        }, start, start, start, t, t, t, end);

        record Info(String warehouse, String sku, String product, String unit, Long categoryId, String category) {}
        Map<String, Info> info = new HashMap<>();
        jdbc.query("""
                SELECT w.id, p.id, w.name, p.sku, p.name, p.unit, p.category_id, c.name
                FROM warehouses w CROSS JOIN products p LEFT JOIN categories c ON c.id = p.category_id
                WHERE w.tenant_id = ? AND p.tenant_id = ?
                  AND (w.id, p.id) IN (SELECT DISTINCT warehouse_id, product_id FROM stock_movements WHERE tenant_id = ?)""", rs -> {
            info.put(rs.getLong(1) + ":" + rs.getLong(2), new Info(rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), (Long) rs.getObject(7), rs.getString(8)));
        }, t, t, t);

        List<Row> rows = new ArrayList<>();
        BigDecimal tOpen = BigDecimal.ZERO, tIn = BigDecimal.ZERO, tOut = BigDecimal.ZERO, tClose = BigDecimal.ZERO;
        for (Map.Entry<String, Acc> e : acc.entrySet()) {
            Acc a = e.getValue();
            Info i = info.get(e.getKey());
            if (i == null) continue;
            String[] ids = e.getKey().split(":");
            long wId = Long.parseLong(ids[0]), pId = Long.parseLong(ids[1]);
            if (warehouseId != null && warehouseId != wId) continue;
            if (categoryId != null && !categoryId.equals(i.categoryId())) continue;
            BigDecimal closeQ = a.openQ.add(a.inQ).subtract(a.outQ);
            BigDecimal closeV = a.openV.add(a.inV).subtract(a.outV);
            // Nothing held and nothing moved in the period: left out, like Cin7
            if (a.openQ.signum() == 0 && a.inQ.signum() == 0 && a.outQ.signum() == 0 && closeV.setScale(2, RoundingMode.HALF_UP).signum() == 0) continue;
            BigDecimal unitCost = closeQ.signum() > 0 ? closeV.divide(closeQ, 4, RoundingMode.HALF_UP) : null;
            rows.add(new Row(wId, i.warehouse(), pId, i.sku(), i.product(), i.unit(), i.categoryId(), i.category(),
                    qty(a.openQ), money(a.openV), qty(a.inQ), money(a.inV), qty(a.outQ), money(a.outV),
                    qty(closeQ), unitCost, money(closeV)));
            tOpen = tOpen.add(a.openV);
            tIn = tIn.add(a.inV);
            tOut = tOut.add(a.outV);
            tClose = tClose.add(closeV);
        }
        rows.sort(Comparator.comparing(Row::warehouse, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::sku, String.CASE_INSENSITIVE_ORDER));
        return new Report(from, to, rows, new Totals(money(tOpen), money(tIn), money(tOut), money(tClose)));
    }

    private static BigDecimal qty(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
