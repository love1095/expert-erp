package com.experterp.backend.service;

import com.experterp.backend.event.StockChangedEvent;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase N1: bins (spec §4) — locations inside a warehouse with the order a picker walks them, a default bin per product,
 * and the stock in each bin. Bin stock is kept as its own ledger beside the stock movements, so it never changes a
 * warehouse's stock, only where in the warehouse it is; what no bin holds is "unbinned".
 * <ul>
 *   <li>stock coming in (receipt, return, transfer in, adjustment in) goes into the product's default bin, if it has one;</li>
 *   <li>stock leaving (shipment, adjustment out, transfer out, return to supplier) is taken from the unbinned stock first,
 *       then from the bins (last in the walk first) — picking (N2) takes it out of its bins before it ships;</li>
 *   <li>stock moved from bin to bin (or into / out of no bin), never more than a bin holds;</li>
 *   <li>opening bins from Cin7's Availability export (its Bin column).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BinService {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public record Bin(Long id, Long warehouseId, String code, int walkOrder, boolean active, BigDecimal units, int products) {}

    public record BinRequest(Long warehouseId, String code, Integer walkOrder, Boolean active) {}

    public record BinStock(Long binId, String code, int walkOrder, BigDecimal quantity) {}

    public record ProductBins(Long productId, Long warehouseId, BigDecimal warehouseStock, BigDecimal unbinned, Long defaultBinId,
                              List<BinStock> bins) {}

    // ---- bins ----

    @Transactional(readOnly = true)
    public List<Bin> bins(Long warehouseId) {
        return jdbc.query("""
                SELECT b.*, COALESCE(s.units, 0) AS units, COALESCE(s.products, 0) AS products FROM warehouse_bins b
                  LEFT JOIN (SELECT bin_id, SUM(q) AS units, COUNT(*) FILTER (WHERE q <> 0) AS products FROM (
                               SELECT bin_id, product_id, SUM(quantity) AS q FROM bin_movements WHERE tenant_id = ? GROUP BY bin_id, product_id) x
                             GROUP BY bin_id) s ON s.bin_id = b.id
                 WHERE b.tenant_id = ? AND b.warehouse_id = ? ORDER BY b.walk_order, lower(b.code)""", (rs, n) -> new Bin(rs.getLong("id"),
                rs.getLong("warehouse_id"), rs.getString("code"), rs.getInt("walk_order"), rs.getBoolean("active"), rs.getBigDecimal("units"),
                rs.getInt("products")), TenantContext.get(), TenantContext.get(), warehouseId);
    }

    @Transactional
    public Bin saveBin(Long id, BinRequest r) {
        Long t = TenantContext.get();
        String code = r.code() == null ? "" : r.code().trim();
        if (code.isEmpty() || code.length() > 60) throw new IllegalArgumentException("The bin code is required (at most 60 characters)");
        if (id == null) {
            if (r.warehouseId() == null) throw new IllegalArgumentException("Choose the warehouse");
            requireWarehouse(r.warehouseId());
            if (exists(r.warehouseId(), code, null)) throw new IllegalArgumentException("Bin " + code + " is in this warehouse already");
            id = jdbc.queryForObject("INSERT INTO warehouse_bins (tenant_id, warehouse_id, code, walk_order) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, t, r.warehouseId(), code, r.walkOrder() == null ? nextWalk(r.warehouseId()) : r.walkOrder());
            auditService.log("BIN", id, "CREATE", Map.of("code", code));
        } else {
            Map<String, Object> b = bin(id);
            Long wh = ((Number) b.get("warehouse_id")).longValue();
            if (exists(wh, code, id)) throw new IllegalArgumentException("Bin " + code + " is in this warehouse already");
            boolean active = r.active() == null || r.active();
            if (!active && binQuantity(id).signum() != 0) throw new IllegalStateException("Bin " + b.get("code") + " holds stock: move it out first");
            jdbc.update("UPDATE warehouse_bins SET code = ?, walk_order = ?, active = ? WHERE id = ?", code,
                    r.walkOrder() == null ? b.get("walk_order") : r.walkOrder(), active, id);
            auditService.log("BIN", id, "UPDATE", Map.of("code", code, "active", active));
        }
        Long finalId = id;
        Long wh = ((Number) bin(id).get("warehouse_id")).longValue();
        return bins(wh).stream().filter(x -> x.id().equals(finalId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteBin(Long id) {
        Map<String, Object> b = bin(id);
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bin_movements WHERE bin_id = ?)", Boolean.class, id);
        if (Boolean.TRUE.equals(used)) throw new IllegalStateException("Bin " + b.get("code") + " has had stock: make it inactive instead");
        jdbc.update("DELETE FROM product_bins WHERE bin_id = ?", id);
        jdbc.update("DELETE FROM warehouse_bins WHERE id = ?", id);
        auditService.log("BIN", id, "DELETE", Map.of("code", b.get("code")));
    }

    /** The bin a product goes into when it comes into this warehouse (null: none). */
    @Transactional
    public void setDefaultBin(Long productId, Long warehouseId, Long binId) {
        Long t = TenantContext.get();
        if (binId == null) {
            jdbc.update("DELETE FROM product_bins WHERE tenant_id = ? AND product_id = ? AND warehouse_id = ?", t, productId, warehouseId);
        } else {
            Map<String, Object> b = bin(binId);
            if (((Number) b.get("warehouse_id")).longValue() != warehouseId) throw new IllegalArgumentException("That bin is in another warehouse");
            if (!Boolean.TRUE.equals(b.get("active"))) throw new IllegalArgumentException("Bin " + b.get("code") + " is inactive");
            jdbc.update("""
                    INSERT INTO product_bins (tenant_id, product_id, warehouse_id, bin_id) VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, product_id, warehouse_id) DO UPDATE SET bin_id = EXCLUDED.bin_id""", t, productId, warehouseId, binId);
        }
        auditService.log("PRODUCT", productId, "DEFAULT_BIN", Map.of("warehouseId", warehouseId, "binId", String.valueOf(binId)));
    }

    // ---- stock per bin ----

    @Transactional(readOnly = true)
    public ProductBins productBins(Long productId, Long warehouseId) {
        Long t = TenantContext.get();
        List<BinStock> bins = jdbc.query("""
                SELECT b.id, b.code, b.walk_order, SUM(m.quantity) AS q FROM bin_movements m JOIN warehouse_bins b ON b.id = m.bin_id
                 WHERE m.tenant_id = ? AND m.product_id = ? AND m.warehouse_id = ? GROUP BY b.id, b.code, b.walk_order HAVING SUM(m.quantity) <> 0
                 ORDER BY b.walk_order, lower(b.code)""", (rs, n) -> new BinStock(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4)),
                t, productId, warehouseId);
        BigDecimal stock = warehouseStock(productId, warehouseId);
        BigDecimal inBins = bins.stream().map(BinStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        Long def = jdbc.queryForList("SELECT bin_id FROM product_bins WHERE tenant_id = ? AND product_id = ? AND warehouse_id = ?", Long.class, t, productId, warehouseId)
                .stream().findFirst().orElse(null);
        return new ProductBins(productId, warehouseId, stock, stock.subtract(inBins).max(BigDecimal.ZERO), def, bins);
    }

    /** Moves stock inside a warehouse: from a bin (null = unbinned) to a bin (null = unbinned); never more than is there. */
    @Transactional
    public ProductBins move(Long productId, Long warehouseId, Long fromBin, Long toBin, BigDecimal qty, String reference) {
        if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("Give the quantity to move");
        if (Objects.equals(fromBin, toBin)) throw new IllegalArgumentException("Choose two different places");
        ProductBins now = productBins(productId, warehouseId);
        BigDecimal there = fromBin == null ? now.unbinned()
                : now.bins().stream().filter(b -> b.binId().equals(fromBin)).map(BinStock::quantity).findFirst().orElse(BigDecimal.ZERO);
        if (qty.compareTo(there) > 0) throw new IllegalStateException("Only " + there.stripTrailingZeros().toPlainString() + " there to move");
        if (toBin != null) requireBinIn(toBin, warehouseId, true);
        if (fromBin != null) requireBinIn(fromBin, warehouseId, false);
        if (fromBin != null) post(productId, warehouseId, fromBin, qty.negate(), "MOVED", reference);
        if (toBin != null) post(productId, warehouseId, toBin, qty, "MOVED", reference);
        auditService.log("BIN", toBin != null ? toBin : fromBin, "MOVE", Map.of("productId", productId, "quantity", qty,
                "from", String.valueOf(fromBin), "to", String.valueOf(toBin)));
        return productBins(productId, warehouseId);
    }

    /** Records stock going into or out of a bin (picking, counting…): used by other services; nothing below zero. */
    @Transactional
    public void post(Long productId, Long warehouseId, Long binId, BigDecimal quantity, String reason, String reference) {
        if (quantity.signum() < 0) {
            BigDecimal there = binStock(productId, binId);
            if (there.add(quantity).signum() < 0) {
                throw new IllegalStateException("Bin " + bin(binId).get("code") + " holds only " + there.stripTrailingZeros().toPlainString());
            }
        }
        jdbc.update("""
                INSERT INTO bin_movements (tenant_id, product_id, warehouse_id, bin_id, quantity, reason, reference, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                TenantContext.get(), productId, warehouseId, binId, quantity, reason, cut(reference, 255), auditService.currentActorName());
    }

    /**
     * Keeps the bins in step with the stock (runs inside the transaction that moved it): what comes in goes to the
     * product's default bin; what leaves is taken from the unbinned stock first, then from the bins, last in the walk first.
     */
    @EventListener
    public void onStockChanged(StockChangedEvent e) {
        Long t = TenantContext.get();
        if (t == null || e.getProductId() == null || e.getWarehouseId() == null) return;
        if (e.getType().isIn()) {
            List<Long> def = jdbc.queryForList("""
                    SELECT pb.bin_id FROM product_bins pb JOIN warehouse_bins b ON b.id = pb.bin_id AND b.active
                     WHERE pb.tenant_id = ? AND pb.product_id = ? AND pb.warehouse_id = ?""", Long.class, t, e.getProductId(), e.getWarehouseId());
            if (!def.isEmpty()) post(e.getProductId(), e.getWarehouseId(), def.get(0), e.getQuantity(), "RECEIVED", e.getReferenceId());
            return;
        }
        fit(e.getProductId(), e.getWarehouseId(), e.getReferenceId());
    }

    // The bins never hold more than the warehouse has: the excess leaves the bins, last in the walk first
    private void fit(Long productId, Long warehouseId, String reference) {
        ProductBins pb = productBins(productId, warehouseId);
        BigDecimal inBins = pb.bins().stream().map(BinStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal excess = inBins.subtract(pb.warehouseStock());
        if (excess.signum() <= 0) return;
        List<BinStock> lastFirst = new ArrayList<>(pb.bins());
        Collections.reverse(lastFirst);
        for (BinStock b : lastFirst) {
            if (excess.signum() <= 0) break;
            BigDecimal take = excess.min(b.quantity());
            if (take.signum() > 0) post(productId, warehouseId, b.binId(), take.negate(), "LEFT", reference);
            excess = excess.subtract(take);
        }
    }

    // ---- opening bins from Cin7 ----

    public record ImportResult(boolean applied, int rows, int binsCreated, int changed, List<String> warnings) {}

    /**
     * Cin7's Availability export (Inventory → Availability → Export): for the rows of this warehouse that name a Bin, the
     * bin is created if new and set to the row's OnHand (the difference posted as OPENING). Rows without a bin are left
     * as unbinned stock. The bins never hold more than the warehouse has (the stock import comes first).
     */
    @Transactional
    public ImportResult importBins(InputStream in, Long warehouseId, boolean apply) throws IOException {
        requireWarehouse(warehouseId);
        Long t = TenantContext.get();
        List<String> warnings = new ArrayList<>();
        int rows = 0, created = 0, changed = 0;
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();
        try (CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true).build())) {
            CsvColumns c = new CsvColumns(parser.getHeaderNames());
            if (!c.has("Bin") || !(c.has("SKU") || c.has("ProductCode")) || !c.has("OnHand")) {
                throw new IllegalArgumentException("This is not a Cin7 stock file with bins: it needs the columns SKU, Bin and OnHand (Inventory → Availability → Export)");
            }
            String skuCol = c.has("SKU") ? "SKU" : "ProductCode";
            for (CSVRecord r : parser) {
                String bin = c.get(r, "Bin"), sku = c.get(r, skuCol);
                if (bin == null || sku == null) continue;
                rows++;
                BigDecimal onHand;
                try {
                    onHand = new BigDecimal(Objects.requireNonNull(c.get(r, "OnHand")).replace(",", ""));
                } catch (RuntimeException e) {
                    warnings.add(sku + " in " + bin + ": OnHand is not a quantity");
                    continue;
                }
                List<Long> product = jdbc.queryForList("SELECT id FROM products WHERE tenant_id = ? AND sku = ?", Long.class, t, sku);
                if (product.isEmpty()) { warnings.add(sku + ": not a product here"); continue; }
                Long binId = jdbc.queryForList("SELECT id FROM warehouse_bins WHERE tenant_id = ? AND warehouse_id = ? AND lower(code) = lower(?)", Long.class,
                        t, warehouseId, bin).stream().findFirst().orElse(null);
                if (binId == null) {
                    binId = jdbc.queryForObject("INSERT INTO warehouse_bins (tenant_id, warehouse_id, code, walk_order) VALUES (?, ?, ?, ?) RETURNING id",
                            Long.class, t, warehouseId, bin.trim(), nextWalk(warehouseId));
                    created++;
                }
                BigDecimal diff = onHand.subtract(binStock(product.get(0), binId));
                if (diff.signum() > 0) {
                    BigDecimal unbinned = productBins(product.get(0), warehouseId).unbinned();
                    if (diff.compareTo(unbinned) > 0) {
                        warnings.add(sku + " in " + bin + ": only " + unbinned.stripTrailingZeros().toPlainString() + " in the warehouse without a bin (import the stock first)");
                        diff = unbinned;
                    }
                }
                if (diff.signum() != 0) {
                    post(product.get(0), warehouseId, binId, diff, "OPENING", "Cin7 bins");
                    changed++;
                }
            }
        }
        ImportResult result = new ImportResult(apply, rows, created, changed, warnings.subList(0, Math.min(200, warnings.size())));
        if (!apply) TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        else auditService.log("BIN", null, "IMPORT", Map.of("warehouseId", warehouseId, "rows", rows, "binsCreated", created, "changed", changed));
        return result;
    }

    // ---- shared ----

    BigDecimal warehouseStock(Long productId, Long warehouseId) {
        BigDecimal v = jdbc.queryForObject("SELECT SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") THEN quantity ELSE -quantity END) "
                + "FROM stock_movements WHERE tenant_id = ? AND product_id = ? AND warehouse_id = ?", BigDecimal.class, TenantContext.get(), productId, warehouseId);
        return v == null ? BigDecimal.ZERO : v;
    }

    BigDecimal binStock(Long productId, Long binId) {
        BigDecimal v = jdbc.queryForObject("SELECT SUM(quantity) FROM bin_movements WHERE tenant_id = ? AND product_id = ? AND bin_id = ?",
                BigDecimal.class, TenantContext.get(), productId, binId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal binQuantity(Long binId) {
        BigDecimal v = jdbc.queryForObject("SELECT SUM(quantity) FROM bin_movements WHERE tenant_id = ? AND bin_id = ?", BigDecimal.class, TenantContext.get(), binId);
        return v == null ? BigDecimal.ZERO : v;
    }

    Map<String, Object> bin(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM warehouse_bins WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        if (r.isEmpty()) throw new IllegalArgumentException("Bin not found");
        return r.get(0);
    }

    private void requireBinIn(Long binId, Long warehouseId, boolean mustBeActive) {
        Map<String, Object> b = bin(binId);
        if (((Number) b.get("warehouse_id")).longValue() != warehouseId) throw new IllegalArgumentException("Bin " + b.get("code") + " is in another warehouse");
        if (mustBeActive && !Boolean.TRUE.equals(b.get("active"))) throw new IllegalArgumentException("Bin " + b.get("code") + " is inactive");
    }

    private void requireWarehouse(Long warehouseId) {
        Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM warehouses WHERE tenant_id = ? AND id = ?)", Boolean.class, TenantContext.get(), warehouseId);
        if (!Boolean.TRUE.equals(ok)) throw new IllegalArgumentException("Warehouse not found");
    }

    private boolean exists(Long warehouseId, String code, Long except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM warehouse_bins WHERE tenant_id = ? AND warehouse_id = ? AND lower(code) = lower(?) AND (?::bigint IS NULL OR id <> ?::bigint))""",
                Boolean.class, TenantContext.get(), warehouseId, code, except, except));
    }

    private int nextWalk(Long warehouseId) {
        Integer max = jdbc.queryForObject("SELECT MAX(walk_order) FROM warehouse_bins WHERE tenant_id = ? AND warehouse_id = ?", Integer.class, TenantContext.get(), warehouseId);
        return max == null ? 10 : max + 10;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
