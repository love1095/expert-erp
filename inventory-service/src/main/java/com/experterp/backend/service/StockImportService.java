package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.event.StockChangedEvent;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Brings stock to the quantities of a Cin7 Core stock file (Inventory → Availability → Export,
 * AvailabilityReport_YYYY-MM-DD.csv): the opening balances when moving from Cin7.
 * <p>
 * One warehouse at a time. For each SKU in the file, the difference between its OnHand and the product's current
 * stock in that warehouse is posted as an ADJUSTMENT_IN / ADJUSTMENT_OUT movement (reference STOCK_IMPORT), so
 * the history stays complete and importing the same file again changes nothing. Stock coming in is costed at
 * StockValue / OnHand from the file — £0 included, so the opening stock value equals Cin7's — and only at the
 * product's cost price when the file has no StockValue. Products not in the file are left alone.
 * <p>
 * A preview runs the same checks and figures, then rolls back.
 */
@Service
@RequiredArgsConstructor
public class StockImportService {

    private static final int MAX_WARNINGS = 200;
    private static final int MAX_CHANGES_LISTED = 100;

    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockMovementRepository movementRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final CostingService costingService;

    public record Change(String sku, String name, BigDecimal before, BigDecimal after, BigDecimal unitCost) {}

    public record ImportResult(
            boolean applied, String warehouseName, String fileLocation,
            int rows, int changed, int unchanged, int unknownSkus,
            BigDecimal unitsBefore, BigDecimal unitsAfter,
            // Σ quantity × unit cost of the stock in the file for the products found
            BigDecimal valueAfter,
            // Largest changes first, at most MAX_CHANGES_LISTED
            List<Change> changes,
            List<String> warnings) {}

    private record Row(String sku, BigDecimal onHand, BigDecimal value) {}

    /** The differences worked out for one warehouse, not written yet. */
    private record Plan(Warehouse warehouse, List<StockMovement> movements, List<Change> changes, int rows, int unchanged, int unknown,
                        BigDecimal unitsBefore, BigDecimal unitsAfter, BigDecimal valueAfter) {}

    @Transactional
    public ImportResult importCsv(InputStream in, String fileName, Long warehouseId, boolean apply) throws IOException {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));
        List<String> warnings = new ArrayList<>();
        Set<String> locations = new LinkedHashSet<>();
        Map<String, Row> rows = read(in, locations, warnings);
        if (locations.size() > 1) {
            throw new IllegalArgumentException("The file has stock for several locations (" + String.join(", ", locations)
                    + "). Export one location at a time from Cin7.");
        }
        String reference = reference(fileName);
        Plan plan = plan(rows, warehouse, reference, warnings);
        return finish(List.of(plan), warehouse.getName(), locations.isEmpty() ? null : locations.iterator().next(), reference, apply, warnings);
    }

    /**
     * Cin7's "Stock on hand" file (Products → Export → Stock on hand, StockOnHand_YYYY-MM-DD.csv: ProductCode, Quantity,
     * AverageCost, Location, Bin, …), also what {@link ProductFileService} exports. One row per batch of stock (it may
     * repeat a product); each Location is the warehouse of that name, and every warehouse in the file gets the file's
     * quantities the same way as {@link #importCsv}. Stock in a warehouse that the file does not name is left alone.
     */
    @Transactional
    public ImportResult importStockOnHand(InputStream in, String fileName, boolean apply) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Row>> byLocation = readStockOnHand(in, warnings);
        Map<String, Warehouse> warehouses = new HashMap<>();
        warehouseRepository.findAll().forEach(w -> warehouses.putIfAbsent(w.getName().trim().toLowerCase(Locale.ROOT), w));
        List<String> unknown = byLocation.keySet().stream().filter(l -> !warehouses.containsKey(l.toLowerCase(Locale.ROOT))).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("No warehouse called " + String.join(", ", unknown)
                    + ": add it under Warehouses first (or rename it to match), nothing was imported");
        }
        String reference = reference(fileName);
        List<Plan> plans = new ArrayList<>();
        for (Map.Entry<String, Map<String, Row>> e : byLocation.entrySet()) {
            plans.add(plan(e.getValue(), warehouses.get(e.getKey().toLowerCase(Locale.ROOT)), reference, warnings));
        }
        String names = String.join(", ", plans.stream().map(pl -> pl.warehouse().getName()).toList());
        return finish(plans, names, names.isEmpty() ? null : names, reference, apply, warnings);
    }

    private static String reference(String fileName) {
        String reference = fileName == null || fileName.isBlank() ? "Stock import" : fileName;
        return reference.length() > 255 ? reference.substring(0, 255) : reference;
    }

    private Plan plan(Map<String, Row> rows, Warehouse warehouse, String reference, List<String> warnings) {
        Long warehouseId = warehouse.getId();
        // Products by SKU (exact), locked so nothing else changes their stock while we post the differences
        Long tenantId = TenantContext.get();
        Map<String, Long> idsBySku = new HashMap<>();
        jdbc.query("SELECT id, sku FROM products WHERE tenant_id = :t AND sku IN (:skus) FOR UPDATE",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("skus", rows.isEmpty() ? List.of("") : rows.keySet()),
                rs -> { idsBySku.put(rs.getString("sku"), rs.getLong("id")); });

        Map<Long, BigDecimal> current = new HashMap<>();
        if (!idsBySku.isEmpty()) {
            jdbc.query("SELECT product_id, SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") " +
                       "                       THEN quantity ELSE -quantity END) AS qty " +
                       "  FROM stock_movements WHERE tenant_id = :t AND warehouse_id = :w AND product_id IN (:ids) " +
                       " GROUP BY product_id",
                    new MapSqlParameterSource().addValue("t", tenantId).addValue("w", warehouseId)
                            .addValue("ids", idsBySku.values()),
                    rs -> { current.put(rs.getLong("product_id"), rs.getBigDecimal("qty")); });
        }

        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(idsBySku.values()).forEach(p -> products.put(p.getId(), p));

        int unknown = 0, unchanged = 0;
        BigDecimal unitsBefore = BigDecimal.ZERO, unitsAfter = BigDecimal.ZERO, valueAfter = BigDecimal.ZERO;
        List<Change> changes = new ArrayList<>();
        List<StockMovement> movements = new ArrayList<>();

        for (Row row : rows.values()) {
            Long id = idsBySku.get(row.sku());
            if (id == null) {
                // Rows with no stock for a product we don't have are harmless
                if (row.onHand().signum() != 0) {
                    unknown++;
                    warn(warnings, row.sku() + ": not a product in the ERP, " + plain(row.onHand()) + " on hand not imported");
                }
                continue;
            }
            Product product = products.get(id);
            BigDecimal before = current.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal after = row.onHand();
            BigDecimal unitCost = unitCost(row, product);
            unitsBefore = unitsBefore.add(before);
            unitsAfter = unitsAfter.add(after);
            if (unitCost != null) valueAfter = valueAfter.add(after.multiply(unitCost));

            BigDecimal diff = after.subtract(before);
            if (diff.signum() == 0) { unchanged++; continue; }
            changes.add(new Change(row.sku(), product.getName(), before, after, diff.signum() > 0 ? unitCost : null));

            StockMovement m = new StockMovement();
            m.setProduct(product);
            m.setWarehouse(warehouse);
            m.setQuantity(diff.abs());
            m.setType(diff.signum() > 0 ? MovementType.ADJUSTMENT_IN : MovementType.ADJUSTMENT_OUT);
            m.setReferenceType(ReferenceType.STOCK_IMPORT);
            m.setReferenceId(reference);
            m.setNotes("Stock import: " + plain(before) + " → " + plain(after));
            if (diff.signum() > 0) m.setUnitCost(unitCost);
            movements.add(m);
        }
        return new Plan(warehouse, movements, changes, rows.size(), unchanged, unknown, unitsBefore, unitsAfter, valueAfter);
    }

    private ImportResult finish(List<Plan> plans, String warehouseName, String fileLocation, String reference, boolean apply,
                                List<String> warnings) {
        int rows = 0, unchanged = 0, unknown = 0;
        BigDecimal unitsBefore = BigDecimal.ZERO, unitsAfter = BigDecimal.ZERO, valueAfter = BigDecimal.ZERO;
        List<Change> changes = new ArrayList<>();
        List<StockMovement> movements = new ArrayList<>();
        for (Plan p : plans) {
            rows += p.rows(); unchanged += p.unchanged(); unknown += p.unknown();
            unitsBefore = unitsBefore.add(p.unitsBefore()); unitsAfter = unitsAfter.add(p.unitsAfter()); valueAfter = valueAfter.add(p.valueAfter());
            changes.addAll(p.changes());
            movements.addAll(p.movements());
        }
        changes.sort(Comparator.comparing((Change c) -> c.after().subtract(c.before()).abs()).reversed()
                .thenComparing(Change::sku));
        ImportResult result = new ImportResult(apply, warehouseName, fileLocation,
                rows, movements.size(), unchanged, unknown, unitsBefore, unitsAfter,
                valueAfter.setScale(2, RoundingMode.HALF_UP),
                changes.subList(0, Math.min(MAX_CHANGES_LISTED, changes.size())), warnings);

        if (!apply) {
            // Preview: nothing is written, and the product locks are released at once
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return result;
        }

        for (StockMovement saved : movementRepository.saveAll(movements)) {
            costingService.record(saved);
            eventPublisher.publishEvent(new StockChangedEvent(this, saved.getProduct().getId(),
                    saved.getWarehouse().getId(), saved.getQuantity(), saved.getType(), saved.getReferenceId()));
        }
        // One audit entry per warehouse (each movement is in the stock history)
        for (Plan p : plans) {
            auditService.log("STOCK_IMPORT", p.warehouse().getId(), "IMPORT", Map.of(
                    "file", reference, "warehouse", p.warehouse().getName(), "rows", p.rows(),
                    "changed", p.movements().size(), "unknownSkus", p.unknown(),
                    "unitsBefore", p.unitsBefore(), "unitsAfter", p.unitsAfter()));
        }
        return result;
    }

    /** Stock on hand rows per location, quantities and values added up per SKU. */
    private static Map<String, Map<String, Row>> readStockOnHand(InputStream in, List<String> warnings) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '\uFEFF') reader.reset();

        Map<String, Map<String, Row>> out = new LinkedHashMap<>();
        // A SKU whose rows lack a cost in some row: its value is unknown (costed at the product's cost price)
        Set<String> uncosted = new HashSet<>();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            if (!cols.has("ProductCode") || !cols.has("Quantity") || !cols.has("Location")) {
                throw new IllegalArgumentException("This is not a stock on hand file: it needs the columns ProductCode, Quantity and Location");
            }
            for (CSVRecord r : parser) {
                String sku = cols.get(r, "ProductCode");
                if (sku == null) continue;
                String location = cols.get(r, "Location");
                if (location == null) {
                    warn(warnings, sku + ": no Location, row skipped");
                    continue;
                }
                BigDecimal qty = number(cols.get(r, "Quantity"));
                if (qty == null || qty.signum() < 0) {
                    warn(warnings, sku + ": Quantity '" + cols.get(r, "Quantity") + "' is not a quantity, row skipped");
                    continue;
                }
                BigDecimal cost = number(cols.get(r, "AverageCost"));
                Map<String, Row> rows = out.computeIfAbsent(location, k -> new LinkedHashMap<>());
                String key = location.toLowerCase(Locale.ROOT) + "|" + sku;
                if (cost == null) uncosted.add(key);
                Row was = rows.get(sku);
                BigDecimal value = cost == null ? null : qty.multiply(cost);
                if (was == null) {
                    rows.put(sku, new Row(sku, qty, value));
                } else {
                    rows.put(sku, new Row(sku, was.onHand().add(qty), was.value() == null || value == null ? null : was.value().add(value)));
                }
                if (uncosted.contains(key)) rows.put(sku, new Row(sku, rows.get(sku).onHand(), null));
            }
        }
        // Locations merged ignoring case (the last spelling is kept as the key)
        Map<String, Map<String, Row>> merged = new LinkedHashMap<>();
        Map<String, String> spelling = new HashMap<>();
        for (Map.Entry<String, Map<String, Row>> e : out.entrySet()) {
            String k = spelling.computeIfAbsent(e.getKey().toLowerCase(Locale.ROOT), x -> e.getKey());
            Map<String, Row> target = merged.computeIfAbsent(k, x -> new LinkedHashMap<>());
            e.getValue().forEach((sku, row) -> target.merge(sku, row, (x, y) -> new Row(sku, x.onHand().add(y.onHand()),
                    x.value() == null || y.value() == null ? null : x.value().add(y.value()))));
        }
        return merged;
    }

    private static Map<String, Row> read(InputStream in, Set<String> locations, List<String> warnings) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();

        Map<String, Row> rows = new LinkedHashMap<>();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            String skuColumn = cols.has("SKU") ? "SKU" : "ProductCode";
            if (!cols.has(skuColumn) || !cols.has("OnHand")) {
                throw new IllegalArgumentException("This is not a Cin7 stock file: it needs the columns SKU and OnHand "
                        + "(Cin7: Inventory → Availability → Export)");
            }
            for (CSVRecord r : parser) {
                String sku = cols.get(r, skuColumn);
                if (sku == null) continue;
                String location = cols.get(r, "Location");
                if (location != null) locations.add(location);
                BigDecimal onHand = number(cols.get(r, "OnHand"));
                if (onHand == null || onHand.signum() < 0) {
                    warn(warnings, sku + ": OnHand '" + cols.get(r, "OnHand") + "' is not a quantity, row skipped");
                    continue;
                }
                if (rows.containsKey(sku)) {
                    warn(warnings, sku + ": appears more than once, only the first row is used");
                    continue;
                }
                rows.put(sku, new Row(sku, onHand, number(cols.get(r, "StockValue"))));
            }
        }
        return rows;
    }

    // Cin7's own valuation (StockValue / OnHand, £0 included) so opening stock reconciles with Cin7;
    // the product's cost price only when the row has no stock value
    private static BigDecimal unitCost(Row row, Product product) {
        if (row.value() != null && row.onHand().signum() > 0) {
            return row.value().divide(row.onHand(), 4, RoundingMode.HALF_UP);
        }
        return product.getCostPrice();
    }

    private static BigDecimal number(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
    }
}
