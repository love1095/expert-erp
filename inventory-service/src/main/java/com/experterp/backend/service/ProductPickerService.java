package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.security.Permission;
import com.experterp.backend.security.Restricted;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Review R9: the product selection list on order lines (like Cin7's). Active products A-Z by SKU, the chosen supplier's
 * products first, narrowed by part of the SKU, name or barcode; one page at a time (no count, "more" tells the list to
 * load the next page on scroll), with on hand, available, on order and prices for that page only.
 */
@Service
@RequiredArgsConstructor
public class ProductPickerService {

    private final NamedParameterJdbcTemplate jdbc;
    private final StockMovementRepository stockMovementRepository;
    private final AllocationService allocationService;

    public record Row(Long id, String sku, String name, String unit, String barcode, BigDecimal sellPrice,
                      @Restricted(Permission.PRODUCT_COST) BigDecimal costPrice,
                      BigDecimal onHand, BigDecimal available, BigDecimal onOrder, boolean supplierProduct) {}

    public record Result(List<Row> rows, boolean more) {}

    @Transactional(readOnly = true)
    public Result page(String q, Long supplierId, int page, int size) {
        int n = Math.min(Math.max(size, 1), 50);
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("t", TenantContext.get()).addValue("q", pattern).addValue("sid", supplierId)
                .addValue("limit", n + 1).addValue("offset", Math.max(page, 0) * n);
        // No coalesce() around the searched columns, so their trigram indexes can be used (see review R1)
        List<Object[]> raw = jdbc.query(
                "SELECT id, sku, name, unit, barcode, sell_price, cost_price, (default_supplier_id = :sid) AS mine FROM products"
                        + " WHERE tenant_id = :t AND status = 'ACTIVE'"
                        + " AND (CAST(:q AS varchar) IS NULL OR lower(sku) LIKE :q OR lower(name) LIKE :q OR lower(barcode) LIKE :q)"
                        + " ORDER BY (default_supplier_id = :sid) DESC NULLS LAST, sku, id LIMIT :limit OFFSET :offset",
                p, (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
                        rs.getString("barcode"), rs.getBigDecimal("sell_price"), rs.getBigDecimal("cost_price"), rs.getBoolean("mine")});
        boolean more = raw.size() > n;
        if (more) raw = raw.subList(0, n);
        List<Long> ids = raw.stream().map(r -> (Long) r[0]).toList();

        Map<Long, BigDecimal> onHand = new HashMap<>();
        Map<Long, BigDecimal> reserved = Map.of();
        Map<Long, BigDecimal> onOrder = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : stockMovementRepository.calculateTotalStocks(ids)) onHand.put((Long) row[0], (BigDecimal) row[1]);
            reserved = allocationService.reservedByProduct(ids);
            jdbc.query("SELECT l.product_id, SUM(GREATEST(l.quantity - l.received_quantity - l.closed_short_quantity, 0) * l.unit_factor) AS qty"
                            + " FROM purchase_order_lines l JOIN purchase_orders po ON po.id = l.purchase_order_id"
                            + " WHERE po.tenant_id = :t AND po.status IN ('DRAFT', 'ORDERED') AND l.product_id IN (:ids) GROUP BY l.product_id",
                    new MapSqlParameterSource("t", TenantContext.get()).addValue("ids", ids),
                    rs -> { onOrder.put(rs.getLong("product_id"), rs.getBigDecimal("qty")); });
        }
        List<Row> rows = new ArrayList<>();
        for (Object[] r : raw) {
            Long id = (Long) r[0];
            BigDecimal hand = onHand.getOrDefault(id, BigDecimal.ZERO);
            rows.add(new Row(id, (String) r[1], (String) r[2], (String) r[3], (String) r[4], (BigDecimal) r[5], (BigDecimal) r[6],
                    hand, hand.subtract(reserved.getOrDefault(id, BigDecimal.ZERO)), onOrder.getOrDefault(id, BigDecimal.ZERO),
                    supplierId != null && (Boolean) r[7]));
        }
        return new Result(rows, more);
    }
}
