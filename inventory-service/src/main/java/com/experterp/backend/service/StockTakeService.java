package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase N5: stock takes (Cin7: Stocktake). Starting one freezes what each product should hold in each bin (and
 * unbinned) of a warehouse — the whole warehouse, or chosen products or a category. Counted on screen or by scanner;
 * the variance report shows the difference and its value; posting (a manager) books the variances as COUNT
 * adjustments, which the ledger journals like any adjustment. Lines not counted are left as they are.
 */
@Service
@RequiredArgsConstructor
public class StockTakeService {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final BinService bins;
    private final StockAdjustmentService adjustments;
    private final AuditService auditService;

    public record Line(Long id, Long productId, String sku, String name, String barcode, Long binId, String bin, Integer walkOrder,
                       BigDecimal expected, BigDecimal counted, BigDecimal variance, BigDecimal unitCost, BigDecimal varianceValue) {}

    public record StockTake(Long id, String reference, Long warehouseId, String warehouse, String scope, String note, String status,
                            LocalDateTime createdAt, String createdBy, LocalDateTime postedAt, String postedBy,
                            int lineCount, int countedLines, BigDecimal varianceValue, List<Line> lines) {}

    public record StartRequest(Long warehouseId, List<Long> productIds, Long categoryId, String note) {}

    public record PostResult(StockTake stockTake, int adjustments, List<String> warnings) {}

    @Transactional
    public StockTake start(StartRequest r) {
        Long t = TenantContext.get();
        Warehouse w = warehouses.findById(r.warehouseId()).orElseThrow(() -> new IllegalArgumentException("Warehouse not found"));
        if (!jdbc.queryForList("SELECT id FROM stock_takes WHERE tenant_id = ? AND warehouse_id = ? AND status = 'COUNTING'", Long.class, t, w.getId()).isEmpty()) {
            throw new IllegalStateException("A stock take of " + w.getName() + " is still being counted: post or cancel it first");
        }
        boolean partial = (r.productIds() != null && !r.productIds().isEmpty()) || r.categoryId() != null;
        List<Long> ids;
        if (!partial) {
            // Everything the warehouse holds, and every product with a bin there
            ids = jdbc.queryForList("""
                    SELECT product_id FROM stock_movements WHERE tenant_id = ? AND warehouse_id = ? GROUP BY product_id
                    HAVING SUM(CASE WHEN type IN (%s) THEN quantity ELSE -quantity END) <> 0
                    UNION SELECT product_id FROM product_bins WHERE tenant_id = ? AND warehouse_id = ?""".formatted(StockMovementRepository.IN_TYPES),
                    Long.class, t, w.getId(), t, w.getId());
        } else {
            Set<Long> s = new LinkedHashSet<>();
            if (r.productIds() != null) s.addAll(r.productIds());
            if (r.categoryId() != null) s.addAll(jdbc.queryForList("SELECT id FROM products WHERE tenant_id = ? AND category_id = ?", Long.class, t, r.categoryId()));
            ids = new ArrayList<>(s);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException(partial ? "No products chosen" : "Nothing in " + w.getName() + " to count");
        Long id = jdbc.queryForObject("INSERT INTO stock_takes (tenant_id, warehouse_id, scope, note, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, t, w.getId(), partial ? "PARTIAL" : "FULL", cut(r.note()), auditService.currentActorName());
        for (Long pid : ids) {
            Product p = products.findById(pid).orElseThrow(() -> new IllegalArgumentException("Product not found"));
            freeze(id, p, w.getId());
        }
        auditService.log("STOCK_TAKE", id, "STARTED", Map.of("warehouse", w.getName(), "products", ids.size()));
        return stockTake(id);
    }

    // One line per bin holding the product, one for the unbinned stock (or for a product with nothing anywhere)
    private void freeze(Long takeId, Product p, Long warehouseId) {
        BinService.ProductBins pb = bins.productBins(p.getId(), warehouseId);
        BigDecimal unit = adjustments.unitCost(p, warehouseId);
        for (BinService.BinStock b : pb.bins()) addLine(takeId, p.getId(), b.binId(), b.quantity(), unit);
        if (pb.unbinned().signum() != 0 || pb.bins().isEmpty()) addLine(takeId, p.getId(), null, pb.unbinned(), unit);
    }

    private Long addLine(Long takeId, Long productId, Long binId, BigDecimal expected, BigDecimal unit) {
        return jdbc.queryForObject("""
                INSERT INTO stock_take_lines (tenant_id, stock_take_id, product_id, bin_id, expected, unit_cost) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, TenantContext.get(), takeId, productId, binId, expected, unit);
    }

    /** The count of one line typed on screen; empty clears it (not counted). */
    @Transactional
    public StockTake count(Long takeId, Long lineId, BigDecimal counted) {
        counting(takeId);
        if (counted != null && counted.signum() < 0) throw new IllegalArgumentException("A count cannot be negative");
        if (jdbc.update("UPDATE stock_take_lines SET counted = ? WHERE tenant_id = ? AND stock_take_id = ? AND id = ?", counted, TenantContext.get(), takeId, lineId) == 0) {
            throw new IllegalArgumentException("Line not found");
        }
        return stockTake(takeId);
    }

    /**
     * Scanning: the code (barcode or SKU) adds one (or the quantity) to the product's line in that bin (none =
     * unbinned). A product or bin not on the stock take gets a new line, expected what is there now.
     */
    @Transactional
    public StockTake scan(Long takeId, String code, Long binId, BigDecimal quantity) {
        Map<String, Object> take = counting(takeId);
        Long t = TenantContext.get();
        String c = code == null ? "" : code.trim();
        if (c.isEmpty()) throw new IllegalArgumentException("Scan or type a barcode or SKU");
        List<Long> found = jdbc.queryForList("SELECT id FROM products WHERE tenant_id = ? AND (lower(sku) = lower(?) OR barcode = ?) ORDER BY id LIMIT 1",
                Long.class, t, c, c);
        if (found.isEmpty()) throw new IllegalArgumentException("No product with the code " + c);
        Long warehouseId = ((Number) take.get("warehouse_id")).longValue();
        if (binId != null && ((Number) bins.bin(binId).get("warehouse_id")).longValue() != warehouseId) {
            throw new IllegalArgumentException("That bin is in another warehouse");
        }
        BigDecimal q = quantity == null ? BigDecimal.ONE : quantity;
        Long productId = found.get(0);
        List<Long> line = jdbc.queryForList("SELECT id FROM stock_take_lines WHERE tenant_id = ? AND stock_take_id = ? AND product_id = ? AND bin_id IS NOT DISTINCT FROM ?",
                Long.class, t, takeId, productId, binId);
        Long lineId;
        if (line.isEmpty()) {
            Product p = products.findById(productId).orElseThrow();
            BinService.ProductBins pb = bins.productBins(productId, warehouseId);
            BigDecimal there = binId == null ? pb.unbinned()
                    : pb.bins().stream().filter(b -> b.binId().equals(binId)).map(BinService.BinStock::quantity).findFirst().orElse(BigDecimal.ZERO);
            lineId = addLine(takeId, productId, binId, there, adjustments.unitCost(p, warehouseId));
        } else lineId = line.get(0);
        jdbc.update("UPDATE stock_take_lines SET counted = GREATEST(COALESCE(counted, 0) + ?, 0) WHERE id = ?", q, lineId);
        return stockTake(takeId);
    }

    public record Found(Long id, String sku, String name, String barcode) {}

    /** A scanned code (barcode or SKU, any case) to its product: used by the phone pages to receive and adjust. */
    @Transactional(readOnly = true)
    public Found lookup(String code) {
        String c = code == null ? "" : code.trim();
        List<Found> f = jdbc.query("SELECT id, sku, name, barcode FROM products WHERE tenant_id = ? AND (lower(sku) = lower(?) OR barcode = ?) ORDER BY id LIMIT 1",
                (rs, n) -> new Found(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), TenantContext.get(), c, c);
        if (c.isEmpty() || f.isEmpty()) throw new IllegalArgumentException("No product with the code " + c);
        return f.get(0);
    }

    /**
     * Posts the variances (counted − frozen) as COUNT adjustments. Stock that moved since the freeze (a sale shipped
     * while counting) is warned about; nothing is taken below what is there now.
     */
    @Transactional
    public PostResult post(Long takeId) {
        StockAdjustmentService.requireManager();
        counting(takeId);
        StockTake st = stockTake(takeId);
        Warehouse w = warehouses.findById(st.warehouseId()).orElseThrow();
        List<String> warnings = new ArrayList<>();
        int posted = 0;
        // Outs first, so that stock found in another bin does not depend on the order of the lines
        List<Line> lines = st.lines().stream().filter(l -> l.variance() != null && l.variance().signum() != 0)
                .sorted(Comparator.comparing(Line::variance)).toList();
        for (Line l : lines) {
            Product p = products.findById(l.productId()).orElseThrow();
            BinService.ProductBins pb = bins.productBins(p.getId(), w.getId());
            BigDecimal now = l.binId() == null ? pb.unbinned()
                    : pb.bins().stream().filter(b -> b.binId().equals(l.binId())).map(BinService.BinStock::quantity).findFirst().orElse(BigDecimal.ZERO);
            String place = l.sku() + (l.bin() == null ? " (no bin)" : " in " + l.bin());
            if (now.compareTo(l.expected()) != 0) {
                warnings.add(place + ": " + plain(l.expected()) + " when the count started, " + plain(now) + " now");
            }
            BigDecimal qty = l.variance();
            if (qty.signum() < 0 && qty.negate().compareTo(now) > 0) {
                warnings.add(place + ": only " + plain(now) + " left to take away, not " + plain(qty.negate()));
                qty = now.negate();
            }
            if (qty.signum() == 0) continue;
            adjustments.postDirect(p, w, l.binId(), qty, "COUNT", "Stock take ST-" + takeId);
            posted++;
        }
        jdbc.update("UPDATE stock_takes SET status = 'POSTED', posted_at = now(), posted_by = ? WHERE id = ?", auditService.currentActorName(), takeId);
        auditService.log("STOCK_TAKE", takeId, "POSTED", Map.of("adjustments", posted, "varianceValue", st.varianceValue()));
        return new PostResult(stockTake(takeId), posted, warnings);
    }

    @Transactional
    public StockTake cancel(Long takeId) {
        counting(takeId);
        jdbc.update("UPDATE stock_takes SET status = 'CANCELLED' WHERE id = ?", takeId);
        auditService.log("STOCK_TAKE", takeId, "CANCELLED", Map.of());
        return stockTake(takeId);
    }

    @Transactional(readOnly = true)
    public List<StockTake> stockTakes(String status) {
        return jdbc.query("""
                SELECT s.*, w.name AS warehouse, COUNT(l.id) AS line_count, COUNT(l.counted) AS counted_lines,
                       COALESCE(SUM((l.counted - l.expected) * l.unit_cost), 0) AS variance_value
                  FROM stock_takes s JOIN warehouses w ON w.id = s.warehouse_id LEFT JOIN stock_take_lines l ON l.stock_take_id = s.id
                 WHERE s.tenant_id = ? AND (?::varchar IS NULL OR s.status = ?::varchar)
                 GROUP BY s.id, w.name ORDER BY s.created_at DESC LIMIT 200""", (rs, n) -> header(rs, List.of()), TenantContext.get(), status, status);
    }

    @Transactional(readOnly = true)
    public StockTake stockTake(Long id) {
        Long t = TenantContext.get();
        List<Line> lines = jdbc.query("""
                SELECT l.*, p.sku, p.name, p.barcode, b.code AS bin, b.walk_order FROM stock_take_lines l JOIN products p ON p.id = l.product_id
                  LEFT JOIN warehouse_bins b ON b.id = l.bin_id
                 WHERE l.tenant_id = ? AND l.stock_take_id = ? ORDER BY b.walk_order NULLS LAST, lower(b.code), lower(p.sku)""", (rs, n) -> {
            BigDecimal expected = rs.getBigDecimal("expected"), counted = rs.getBigDecimal("counted"), unit = rs.getBigDecimal("unit_cost");
            BigDecimal variance = counted == null ? null : counted.subtract(expected);
            return new Line(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getString("barcode"),
                    rs.getObject("bin_id") == null ? null : rs.getLong("bin_id"), rs.getString("bin"), (Integer) rs.getObject("walk_order"),
                    expected, counted, variance, unit,
                    variance == null || unit == null ? null : variance.multiply(unit).setScale(2, RoundingMode.HALF_UP));
        }, t, id);
        return jdbc.query("""
                SELECT s.*, w.name AS warehouse, COUNT(l.id) AS line_count, COUNT(l.counted) AS counted_lines,
                       COALESCE(SUM((l.counted - l.expected) * l.unit_cost), 0) AS variance_value
                  FROM stock_takes s JOIN warehouses w ON w.id = s.warehouse_id LEFT JOIN stock_take_lines l ON l.stock_take_id = s.id
                 WHERE s.tenant_id = ? AND s.id = ? GROUP BY s.id, w.name""", rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Stock take not found");
            return header(rs, lines);
        }, t, id);
    }

    private StockTake header(java.sql.ResultSet rs, List<Line> lines) throws java.sql.SQLException {
        return new StockTake(rs.getLong("id"), "ST-" + rs.getLong("id"), rs.getLong("warehouse_id"), rs.getString("warehouse"), rs.getString("scope"),
                rs.getString("note"), rs.getString("status"), ts(rs.getTimestamp("created_at")), rs.getString("created_by"), ts(rs.getTimestamp("posted_at")),
                rs.getString("posted_by"), rs.getInt("line_count"), rs.getInt("counted_lines"),
                rs.getBigDecimal("variance_value").setScale(2, RoundingMode.HALF_UP), lines);
    }

    private Map<String, Object> counting(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM stock_takes WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        if (r.isEmpty()) throw new IllegalArgumentException("Stock take not found");
        if (!"COUNTING".equals(r.get(0).get("status"))) throw new IllegalStateException("This stock take is " + String.valueOf(r.get(0).get("status")).toLowerCase());
        return r.get(0);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
