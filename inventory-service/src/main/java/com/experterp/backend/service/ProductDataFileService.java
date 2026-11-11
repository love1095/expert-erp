package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.security.Permission;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The files on the products list's Export and Import menus, in Cin7 Core's own file names and columns, so each file
 * exported here can be imported back (here or into Cin7):
 * <ul>
 *   <li>Inventory list, with or without descriptions ({@link ProductImportService});</li>
 *   <li>Stock on hand: one row per batch of stock still in a warehouse (its FIFO cost layer), with its cost and the day it
 *       came in; imported by {@link StockImportService#importStockOnHand};</li>
 *   <li>Units of measure (Cin7's AdditionalUOM file): the product's extra units and how many base units each holds;</li>
 *   <li>Assembly BOM: the components of each kit;</li>
 *   <li>Simple prices: price tiers 1–4 per product (Cin7 has 10 tiers, 5–10 are not used here);</li>
 *   <li>Custom prices: the customers' file ({@link CustomerExportService}), also offered here as in Cin7.</li>
 * </ul>
 * Everything of that type is exported, whatever the list shows (like Cin7). Cost columns are left out without the
 * permission to see costs. An import refuses a file of another type than the one chosen.
 * <p>
 * Not one transaction: units and kits are saved product by product (each its own transaction through
 * {@link ProductUnitService} / {@link KitService}), so a product that cannot be changed is reported and skipped
 * while the others are saved.
 */
@Service
@RequiredArgsConstructor
public class ProductDataFileService {

    static final String[] STOCK_ON_HAND = {"ProductCode", "Quantity", "AverageCost", "Location", "Bin", "BatchOrSerialNumber",
            "ExpiryDate_YYYYMMDD", "ReceivedDate_YYYYMMDD"};
    static final String[] UNITS = {"Action", "BaseSKU", "BaseProductName", "BaseUnitsOfMeasure", "AdditionalUnitsOfMeasureSKU",
            "AdditionalUnitsOfMeasureProductName", "AdditionalUnitsOfMeasureName", "NumberOfBaseUnitsInAdditionalUnit", "IAmSellingThisProduct"};
    static final String[] ASSEMBLY_BOM = {"Action", "ProductSKU", "ProductName", "ComponentSKU", "ComponentName", "Quantity",
            "WastageQuantity_ForStockComponentOnly", "WastagePercent_ForStockComponentOnly", "CostPercentage_ForStockComponentOnly",
            "PriceTier_ForServiceComponentOnly", "ExpenseAccount_ForServiceComponentOnly", "EstimatedUnitCost"};
    static final String[] SIMPLE_PRICES = {"ProductCode", "Name", "PriceTier1", "PriceTier2", "PriceTier3", "PriceTier4", "PriceTier5",
            "PriceTier6", "PriceTier7", "PriceTier8", "PriceTier9", "PriceTier10"};

    private static final int MAX_WARNINGS = 200;
    private static final BigDecimal PLACEHOLDER_PRICE = new BigDecimal("9999.99");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** The products' files, as on the Export / Import menus. */
    public enum Kind {
        INVENTORY_LIST("InventoryList", "inventory list", true),
        INVENTORY_LIST_NO_DESCRIPTIONS("InventoryListWithoutDescriptions", "inventory list (without descriptions)", false),
        STOCK_ON_HAND("StockOnHand", "stock on hand", true),
        UNITS_OF_MEASURE("AdditionalUOM", "units of measure", true),
        ASSEMBLY_BOM("AssemblyBOM", "assembly BOM", true),
        SIMPLE_PRICES("SimplePrices", "simple prices", true),
        CUSTOM_PRICES("CustomPrices", "custom prices", true);

        final String file;
        final String label;
        final boolean importable;

        Kind(String file, String label, boolean importable) {
            this.file = file;
            this.label = label;
            this.importable = importable;
        }
    }

    /** What an import did (the inventory list and stock on hand have results of their own). */
    public record ImportResult(String file, int rows, int changed, int unchanged, List<String> warnings) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final ProductImportService inventoryList;
    private final StockImportService stockImport;
    private final CustomerExportService customerExport;
    private final CustomerImportService customerImport;
    private final ProductUnitService unitService;
    private final KitService kitService;
    private final AuditService auditService;

    public String fileName(Kind kind) {
        return kind.file + "_" + LocalDate.now() + ".csv";
    }

    /** Which product file this is, from its columns (null: none of them). */
    static Kind kindOf(CsvColumns cols) {
        if (cols.has("BaseSKU") && cols.has("AdditionalUnitsOfMeasureName")) return Kind.UNITS_OF_MEASURE;
        if (cols.has("ProductSKU") && cols.has("ComponentSKU")) return Kind.ASSEMBLY_BOM;
        if (cols.has("ProductSKU") && cols.has("CustomerName") && cols.has("Price")) return Kind.CUSTOM_PRICES;
        if (cols.has("ProductCode") && cols.has("Quantity") && cols.has("Location")) return Kind.STOCK_ON_HAND;
        if (cols.has("ProductCode") && cols.has("Name")) {
            // The inventory list has the product's own columns; the simple prices file only code, name and tiers
            boolean product = cols.has("Category") || cols.has("Type") || cols.has("Status") || cols.has("Brand") || cols.has("Barcode");
            if (!product && cols.has("PriceTier1")) return Kind.SIMPLE_PRICES;
            return Kind.INVENTORY_LIST;
        }
        return null;
    }

    // ---- export ----

    public void export(Kind kind, Writer out) throws IOException {
        switch (kind) {
            case INVENTORY_LIST -> inventoryList.exportCsv(null, true, out);
            case INVENTORY_LIST_NO_DESCRIPTIONS -> inventoryList.exportCsv(null, false, out);
            case CUSTOM_PRICES -> customerExport.export(CustomerImportService.Kind.CUSTOM_PRICES, out);
            default -> {
                Writer buffered = new BufferedWriter(out);
                // BOM so Excel opens it as UTF-8
                buffered.write('﻿');
                String[] header = switch (kind) {
                    case STOCK_ON_HAND -> STOCK_ON_HAND;
                    case UNITS_OF_MEASURE -> UNITS;
                    case ASSEMBLY_BOM -> ASSEMBLY_BOM;
                    default -> SIMPLE_PRICES;
                };
                CSVPrinter printer = new CSVPrinter(buffered, CSVFormat.DEFAULT.builder().setHeader(header).build());
                switch (kind) {
                    case STOCK_ON_HAND -> stockOnHand(printer);
                    case UNITS_OF_MEASURE -> units(printer);
                    case ASSEMBLY_BOM -> assemblyBom(printer);
                    default -> simplePrices(printer);
                }
                printer.flush();
            }
        }
        auditService.log("PRODUCT", null, "EXPORT", Map.of("file", kind.file));
    }

    private MapSqlParameterSource tenant() {
        return new MapSqlParameterSource("t", TenantContext.get());
    }

    /**
     * What is left of each batch of stock (FIFO cost layer), merged per product, warehouse, day received and unit cost.
     * Bin: the product's bin in that warehouse. Batch / serial and expiry come with batch tracking (S2).
     */
    private void stockOnHand(CSVPrinter printer) {
        boolean costs = Permission.current(Permission.PRODUCT_COST);
        jdbc.query("""
                SELECT p.sku, w.name AS warehouse, b.code AS bin, CAST(l.received_at AS date) AS received, l.unit_cost, SUM(l.remaining) AS qty
                  FROM cost_layers l
                  JOIN products p ON p.id = l.product_id
                  JOIN warehouses w ON w.id = l.warehouse_id
                  LEFT JOIN product_bins pb ON pb.tenant_id = l.tenant_id AND pb.product_id = l.product_id AND pb.warehouse_id = l.warehouse_id
                  LEFT JOIN warehouse_bins b ON b.id = pb.bin_id
                 WHERE l.tenant_id = :t AND l.remaining > 0
                 GROUP BY p.sku, w.name, b.code, CAST(l.received_at AS date), l.unit_cost
                 ORDER BY p.sku, w.name, received, l.unit_cost""", tenant(), rs -> {
            try {
                printer.printRecord(rs.getString("sku"), dec(rs.getBigDecimal("qty"), 4),
                        costs ? dec(rs.getBigDecimal("unit_cost"), 6) : "", rs.getString("warehouse"), nz(rs.getString("bin")), "", "",
                        rs.getDate("received").toLocalDate().format(YYYYMMDD));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Cin7 makes each extra unit a product of its own with a SKU; here a unit has no SKU of its own, so that column is empty. */
    private void units(CSVPrinter printer) {
        jdbc.query("""
                SELECT p.sku, p.name, p.unit, u.name AS unit_name, u.factor
                  FROM product_units u JOIN products p ON p.id = u.product_id
                 WHERE u.tenant_id = :t ORDER BY p.sku, u.factor, lower(u.name)""", tenant(), rs -> {
            try {
                printer.printRecord("", rs.getString("sku"), rs.getString("name"), rs.getString("unit"), "",
                        rs.getString("name") + " (" + rs.getString("unit_name") + ")", rs.getString("unit_name"),
                        plain(rs.getBigDecimal("factor")), "True");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private void assemblyBom(CSVPrinter printer) {
        boolean costs = Permission.current(Permission.PRODUCT_COST);
        jdbc.query("""
                SELECT k.sku AS kit_sku, k.name AS kit_name, c.sku, c.name, kc.quantity, c.cost_price
                  FROM kit_components kc JOIN products k ON k.id = kc.kit_id JOIN products c ON c.id = kc.component_id
                 WHERE kc.tenant_id = :t ORDER BY k.sku, c.sku""", tenant(), rs -> {
            try {
                printer.printRecord("", rs.getString("kit_sku"), rs.getString("kit_name"), rs.getString("sku"), rs.getString("name"),
                        dec(rs.getBigDecimal("quantity"), 4), "", "", "", "", "",
                        costs && rs.getBigDecimal("cost_price") != null ? dec(rs.getBigDecimal("cost_price"), 4) : "");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Tier 1 = the product's sell price, tier 2 = its trade price, tiers 3 and 4 from its details (as the inventory list). */
    private void simplePrices(CSVPrinter printer) {
        jdbc.query("""
                SELECT sku, name, sell_price, trade_price, details ->> 'priceTier3' AS t3, details ->> 'priceTier4' AS t4
                  FROM products WHERE tenant_id = :t ORDER BY sku""", tenant(), rs -> {
            try {
                printer.printRecord(rs.getString("sku"), rs.getString("name"), price(rs.getBigDecimal("sell_price")),
                        price(rs.getBigDecimal("trade_price")), price(decimal(rs.getString("t3"))), price(decimal(rs.getString("t4"))),
                        "", "", "", "", "", "");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    // ---- import ----

    /**
     * Imports one of the product files. {@code apply} is only for stock on hand: false previews the stock changes
     * without writing them. Returns the importer's own result.
     */
    public Object importFile(Kind kind, byte[] bytes, String fileName, boolean deprecateMissing, boolean apply) throws IOException {
        if (!kind.importable) throw new IllegalArgumentException("Import the " + kind.label + " as an inventory list (Import → Inventory list)");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            Kind found = kindOf(cols);
            if (found != kind) {
                throw new IllegalArgumentException(found == null
                        ? "This is not Cin7's " + kind.label + " file: its columns are not the ones Cin7 exports"
                        : "This is Cin7's " + found.label + " file, not the " + kind.label + ": choose Import → " + menuName(found));
            }
            return switch (kind) {
                case INVENTORY_LIST -> inventoryList.importCsv(new ByteArrayInputStream(bytes), deprecateMissing);
                case STOCK_ON_HAND -> stockImport.importStockOnHand(new ByteArrayInputStream(bytes), fileName, apply);
                case CUSTOM_PRICES -> customerImport.importCsv(new ByteArrayInputStream(bytes), CustomerImportService.Kind.CUSTOM_PRICES);
                case UNITS_OF_MEASURE -> importUnits(parser, cols);
                case ASSEMBLY_BOM -> importBom(parser, cols);
                case SIMPLE_PRICES -> importPrices(parser, cols);
                default -> throw new IllegalStateException();
            };
        }
    }

    static String menuName(Kind k) {
        String l = k.label;
        return switch (k) {
            case ASSEMBLY_BOM -> "Assembly BOM";
            default -> Character.toUpperCase(l.charAt(0)) + l.substring(1);
        };
    }

    private record UnitLine(String name, BigDecimal factor) {}

    /**
     * Per product in the file, its extra units become the file's (a unit already there keeps its barcode and prices).
     * Rows with Action "Delete" or "Remove" drop that unit. Products not in the file keep theirs.
     */
    private ImportResult importUnits(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, UnitLine>> bySku = new LinkedHashMap<>();
        Map<String, Set<String>> removed = new HashMap<>();
        int rows = 0;
        for (CSVRecord r : parser) {
            rows++;
            String sku = cols.get(r, "BaseSKU");
            String name = cols.get(r, "AdditionalUnitsOfMeasureName");
            if (sku == null || name == null) {
                warn(warnings, "Line " + r.getParser().getCurrentLineNumber() + ": no BaseSKU or AdditionalUnitsOfMeasureName, skipped");
                continue;
            }
            Map<String, UnitLine> units = bySku.computeIfAbsent(sku, k -> new LinkedHashMap<>());
            if (isDelete(cols.get(r, "Action"))) {
                removed.computeIfAbsent(sku, k -> new HashSet<>()).add(name.toLowerCase(Locale.ROOT));
                continue;
            }
            BigDecimal factor = decimal(cols.get(r, "NumberOfBaseUnitsInAdditionalUnit"));
            if (factor == null || factor.signum() <= 0) {
                warn(warnings, sku + " " + name + ": NumberOfBaseUnitsInAdditionalUnit '" + cols.get(r, "NumberOfBaseUnitsInAdditionalUnit")
                        + "' is not a number above 0, skipped");
                continue;
            }
            units.put(name.toLowerCase(Locale.ROOT), new UnitLine(name, factor));
        }
        Map<String, Product> products = products(bySku.keySet());
        int changed = 0, unchanged = 0;
        for (Map.Entry<String, Map<String, UnitLine>> e : bySku.entrySet()) {
            Product p = products.get(e.getKey());
            if (p == null) {
                warn(warnings, e.getKey() + ": not a product here, its units are skipped");
                continue;
            }
            try {
                Map<String, ProductUnitService.Unit> have = new LinkedHashMap<>();
                unitService.units(p.getId()).forEach(u -> have.put(u.name().toLowerCase(Locale.ROOT), u));
                // Units kept: those in the file, plus those already there that the file neither lists nor removes
                Map<String, ProductUnitService.UnitRequest> next = new LinkedHashMap<>();
                Set<String> drop = removed.getOrDefault(e.getKey(), Set.of());
                have.forEach((k, u) -> {
                    if (!drop.contains(k)) next.put(k, new ProductUnitService.UnitRequest(u.name(), u.factor(), u.barcode(), u.sellPrice(), u.buyPrice()));
                });
                e.getValue().forEach((k, line) -> {
                    ProductUnitService.Unit old = have.get(k);
                    next.put(k, new ProductUnitService.UnitRequest(line.name(), line.factor(), old == null ? null : old.barcode(),
                            old == null ? null : old.sellPrice(), old == null ? null : old.buyPrice()));
                });
                if (sameUnits(have, next)) {
                    unchanged++;
                    continue;
                }
                unitService.save(p.getId(), new ArrayList<>(next.values()));
                changed++;
            } catch (RuntimeException ex) {
                warn(warnings, e.getKey() + ": " + ex.getMessage() + ", its units are left as they were");
            }
        }
        auditService.log("PRODUCT", null, "IMPORT", Map.of("file", Kind.UNITS_OF_MEASURE.file, "rows", rows, "changed", changed));
        return new ImportResult(Kind.UNITS_OF_MEASURE.file, rows, changed, unchanged, warnings);
    }

    private static boolean sameUnits(Map<String, ProductUnitService.Unit> have, Map<String, ProductUnitService.UnitRequest> next) {
        if (have.size() != next.size()) return false;
        for (Map.Entry<String, ProductUnitService.UnitRequest> e : next.entrySet()) {
            ProductUnitService.Unit u = have.get(e.getKey());
            if (u == null || !u.name().equals(e.getValue().name()) || u.factor().compareTo(e.getValue().factor()) != 0) return false;
        }
        return true;
    }

    private record BomLine(String sku, BigDecimal quantity) {}

    /** Per kit in the file, its components become the file's lines. The product must already be a kit (made on its page). */
    private ImportResult importBom(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, BomLine>> byKit = new LinkedHashMap<>();
        int rows = 0;
        for (CSVRecord r : parser) {
            rows++;
            String kit = cols.get(r, "ProductSKU");
            String component = cols.get(r, "ComponentSKU");
            if (kit == null || component == null) {
                warn(warnings, "Line " + r.getParser().getCurrentLineNumber() + ": no ProductSKU or ComponentSKU, skipped");
                continue;
            }
            Map<String, BomLine> lines = byKit.computeIfAbsent(kit, k -> new LinkedHashMap<>());
            if (isDelete(cols.get(r, "Action"))) continue;
            BigDecimal qty = decimal(cols.get(r, "Quantity"));
            if (qty == null || qty.signum() <= 0) {
                warn(warnings, kit + " " + component + ": Quantity '" + cols.get(r, "Quantity") + "' is not a number above 0, skipped");
                continue;
            }
            BomLine was = lines.get(component);
            lines.put(component, new BomLine(component, was == null ? qty : was.quantity().add(qty)));
        }
        Set<String> skus = new HashSet<>(byKit.keySet());
        byKit.values().forEach(m -> skus.addAll(m.keySet()));
        Map<String, Product> products = products(skus);
        int changed = 0, unchanged = 0;
        for (Map.Entry<String, Map<String, BomLine>> e : byKit.entrySet()) {
            Product kit = products.get(e.getKey());
            if (kit == null) {
                warn(warnings, e.getKey() + ": not a product here, skipped");
                continue;
            }
            if (kit.getType() != Product.Type.KIT) {
                warn(warnings, e.getKey() + ": not a kit (make its type Kit on the product page first), skipped");
                continue;
            }
            List<KitService.ComponentRequest> list = new ArrayList<>();
            boolean missing = false;
            for (BomLine line : e.getValue().values()) {
                Product c = products.get(line.sku());
                if (c == null) {
                    warn(warnings, e.getKey() + ": component " + line.sku() + " is not a product here, the kit is left as it was");
                    missing = true;
                    break;
                }
                list.add(new KitService.ComponentRequest(c.getId(), line.quantity()));
            }
            if (missing) continue;
            try {
                Map<Long, BigDecimal> have = kitService.recipe(kit.getId());
                Map<Long, BigDecimal> want = new HashMap<>();
                list.forEach(c -> want.put(c.componentId(), c.quantity()));
                if (sameRecipe(have, want)) {
                    unchanged++;
                    continue;
                }
                kitService.setComponents(kit.getId(), list);
                changed++;
            } catch (RuntimeException ex) {
                warn(warnings, e.getKey() + ": " + ex.getMessage() + ", the kit is left as it was");
            }
        }
        auditService.log("PRODUCT", null, "IMPORT", Map.of("file", Kind.ASSEMBLY_BOM.file, "rows", rows, "changed", changed));
        return new ImportResult(Kind.ASSEMBLY_BOM.file, rows, changed, unchanged, warnings);
    }

    private static boolean sameRecipe(Map<Long, BigDecimal> have, Map<Long, BigDecimal> want) {
        if (have.size() != want.size()) return false;
        for (Map.Entry<Long, BigDecimal> e : want.entrySet()) {
            BigDecimal h = have.get(e.getKey());
            if (h == null || h.compareTo(e.getValue()) != 0) return false;
        }
        return true;
    }

    /**
     * Price tiers 1–4 of products that exist (a price file never creates products). 0 or 9999.99 = no price, as in the
     * inventory list. A tier column missing from the file leaves that tier alone.
     */
    private ImportResult importPrices(CSVParser parser, CsvColumns cols) {
        List<String> warnings = new ArrayList<>();
        Map<String, CSVRecord> bySku = new LinkedHashMap<>();
        int rows = 0;
        boolean higherTiers = false;
        for (CSVRecord r : parser) {
            rows++;
            String sku = cols.get(r, "ProductCode");
            if (sku == null) {
                warn(warnings, "Line " + r.getParser().getCurrentLineNumber() + ": no ProductCode, skipped");
                continue;
            }
            if (bySku.put(sku, r) != null) warn(warnings, sku + ": appears more than once, the last row is used");
            for (int i = 5; i <= 10 && !higherTiers; i++) {
                BigDecimal v = decimal(cols.get(r, "PriceTier" + i));
                if (v != null && v.signum() > 0 && v.compareTo(PLACEHOLDER_PRICE) < 0) higherTiers = true;
            }
        }
        if (higherTiers) warnings.add(0, "Price tiers 5 to 10 are not used here: only tiers 1 to 4 were imported");
        Map<String, Product> products = products(bySku.keySet());
        int changed = 0, unchanged = 0;
        List<Object[]> updates = new ArrayList<>();
        for (Map.Entry<String, CSVRecord> e : bySku.entrySet()) {
            Product p = products.get(e.getKey());
            if (p == null) {
                warn(warnings, e.getKey() + ": not a product here, skipped");
                continue;
            }
            CSVRecord r = e.getValue();
            BigDecimal t1 = cols.has("PriceTier1") ? tier(cols.get(r, "PriceTier1"), e.getKey(), "PriceTier1", warnings) : p.getSellPrice();
            BigDecimal t2 = cols.has("PriceTier2") ? tier(cols.get(r, "PriceTier2"), e.getKey(), "PriceTier2", warnings) : p.getTradePrice();
            BigDecimal t3 = cols.has("PriceTier3") ? tier(cols.get(r, "PriceTier3"), e.getKey(), "PriceTier3", warnings) : p.getDetails().getPriceTier3();
            BigDecimal t4 = cols.has("PriceTier4") ? tier(cols.get(r, "PriceTier4"), e.getKey(), "PriceTier4", warnings) : p.getDetails().getPriceTier4();
            if (same(t1, p.getSellPrice()) && same(t2, p.getTradePrice()) && same(t3, p.getDetails().getPriceTier3())
                    && same(t4, p.getDetails().getPriceTier4())) {
                unchanged++;
                continue;
            }
            updates.add(new Object[]{p.getId(), t1, t2, t3, t4});
            changed++;
        }
        for (Object[] u : updates) {
            jdbc.update("""
                    UPDATE products SET sell_price = :t1, trade_price = :t2,
                           details = (details - 'priceTier3' - 'priceTier4')
                                     || jsonb_strip_nulls(jsonb_build_object('priceTier3', CAST(:t3 AS numeric), 'priceTier4', CAST(:t4 AS numeric))),
                           updated_at = now()
                     WHERE tenant_id = :t AND id = :id""",
                    tenant().addValue("id", u[0]).addValue("t1", u[1]).addValue("t2", u[2]).addValue("t3", u[3]).addValue("t4", u[4]));
        }
        auditService.log("PRODUCT", null, "IMPORT", Map.of("file", Kind.SIMPLE_PRICES.file, "rows", rows, "changed", changed));
        return new ImportResult(Kind.SIMPLE_PRICES.file, rows, changed, unchanged, warnings);
    }

    private BigDecimal tier(String s, String sku, String column, List<String> warnings) {
        if (s == null) return null;
        BigDecimal v = decimal(s);
        if (v == null) {
            warn(warnings, sku + ": " + column + " '" + s + "' is not a number, left empty");
            return null;
        }
        return v.signum() <= 0 || v.compareTo(PLACEHOLDER_PRICE) >= 0 ? null : v;
    }

    private Map<String, Product> products(Collection<String> skus) {
        Map<String, Product> out = new HashMap<>();
        List<String> list = new ArrayList<>(skus);
        for (int from = 0; from < list.size(); from += 500) {
            productRepository.findBySkuIn(list.subList(from, Math.min(from + 500, list.size()))).forEach(p -> out.put(p.getSku(), p));
        }
        return out;
    }

    // ---- helpers ----

    private static boolean isDelete(String action) {
        return action != null && (action.equalsIgnoreCase("Delete") || action.equalsIgnoreCase("Remove"));
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String dec(BigDecimal v, int scale) {
        return v == null ? "" : v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String price(BigDecimal v) {
        return v == null ? "" : v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
    }
}
