package com.experterp.backend.service;

import com.experterp.backend.entity.*;
import com.experterp.backend.exception.PlanLimitExceededException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CategoryRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Imports and exports products in the Cin7 Core inventory list layout (InventoryList_YYYY-MM-DD.csv).
 * <p>
 * Import: one row per SKU (ProductCode). An existing SKU is updated, a new one created; columns missing from the
 * file leave saved values alone. Categories and suppliers are created by name when missing. Cin7 uses 0 and
 * 9999.99 as "no price" placeholders; both are imported as empty. Optionally, ACTIVE products that are not in the
 * file are marked DEPRECATED (for a full catalogue export). Importing the same file twice changes nothing.
 * Rows are handled in chunks so ~15,000 products stay quick; the whole import is one transaction.
 * <p>
 * Export writes the same columns, so an exported file can be edited and imported again.
 */
@Service
@RequiredArgsConstructor
public class ProductImportService {

    // Columns written by export (and read by import), in Cin7's names
    static final List<String> COLUMNS = List.of(
            "ProductCode", "Name", "Category", "Brand", "Type", "Status", "Sellable", "Barcode", "DefaultUnitOfMeasure",
            "AverageCost", "PriceTier1", "PriceTier2", "PriceTier3", "PriceTier4",
            "MinimumBeforeReorder", "ReorderQuantity", "LastSuppliedBy", "SupplierProductCode", "SupplierProductName",
            "SupplierFixedPrice", "Weight", "WeightUnits", "Length", "Width", "Height", "DimensionUnits",
            "ProductFamilySKU", "ProductFamilyName", "ProductFamilyOption1Name", "ProductFamilyOption1Value",
            "ProductFamilyOption2Name", "ProductFamilyOption2Value", "ProductFamilyOption3Name", "ProductFamilyOption3Value",
            "CommaDelimitedTags", "PurchaseTaxRule", "SaleTaxRule", "HSCode", "CountryOfOrigin",
            "Description", "ShortDescription", "InternalNote");

    private static final int CHUNK = 500;
    private static final int MAX_WARNINGS = 200;
    // Cin7's "no price" placeholder
    private static final BigDecimal PLACEHOLDER_PRICE = new BigDecimal("9999.99");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final SubscriptionService subscriptionService;
    private final AuditService auditService;
    private final EntityManager em;

    public record ImportResult(int rows, int created, int updated, int unchanged, int deprecated,
                               int categoriesCreated, int suppliersCreated, List<String> warnings) {}

    @Transactional
    public ImportResult importCsv(InputStream in, boolean deprecateMissing) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();

        try (CSVParser parser = CSVParser.parse(reader, format)) {
            CsvColumns cols = new CsvColumns(parser.getHeaderNames());
            if (!cols.has("ProductCode") || !cols.has("Name")) {
                throw new IllegalArgumentException("Not a Cin7 inventory list: the file needs 'ProductCode' and 'Name' columns");
            }

            List<String> warnings = new ArrayList<>();
            // Last row wins when a SKU appears twice
            Map<String, CSVRecord> bySku = new LinkedHashMap<>();
            int rows = 0;
            for (CSVRecord r : records(parser)) {
                rows++;
                String sku = cols.get(r, "ProductCode");
                if (sku == null || cols.get(r, "Name") == null) {
                    warn(warnings, "Line " + r.getParser().getCurrentLineNumber() + ": no ProductCode or Name, skipped");
                    continue;
                }
                if (bySku.put(sku, r) != null) warn(warnings, sku + ": appears more than once, the last row is used");
            }

            Set<String> existingSkus = productRepository.findAllSkus();
            long newProducts = bySku.keySet().stream().filter(s -> !existingSkus.contains(s)).count();
            checkPlan(existingSkus.size(), newProducts);

            Lookups lookups = new Lookups();
            int created = 0, updated = 0, unchanged = 0;
            List<String> skus = new ArrayList<>(bySku.keySet());
            for (int from = 0; from < skus.size(); from += CHUNK) {
                List<String> chunk = skus.subList(from, Math.min(from + CHUNK, skus.size()));
                Map<String, Product> existing = new HashMap<>();
                productRepository.findBySkuIn(chunk).forEach(p -> existing.put(p.getSku(), p));
                for (String sku : chunk) {
                    Product p = existing.get(sku);
                    boolean isNew = p == null;
                    if (isNew) {
                        p = new Product();
                        p.setSku(sku);
                    }
                    String before = isNew ? null : fingerprint(p);
                    apply(p, cols, bySku.get(sku), lookups, warnings);
                    if (isNew) {
                        productRepository.save(p);
                        created++;
                    } else if (before.equals(fingerprint(p))) {
                        unchanged++;
                    } else {
                        p.setUpdatedAt(LocalDateTime.now());
                        updated++;
                    }
                }
                // Keep the persistence context small
                em.flush();
                em.clear();
            }

            int deprecated = 0;
            if (deprecateMissing) {
                List<Long> missing = productRepository.findActiveIdsAndSkus().stream()
                        .filter(row -> !bySku.containsKey((String) row[1])).map(row -> (Long) row[0]).toList();
                LocalDateTime now = LocalDateTime.now();
                for (int from = 0; from < missing.size(); from += CHUNK) {
                    deprecated += productRepository.deprecate(TenantContext.get(),
                            missing.subList(from, Math.min(from + CHUNK, missing.size())), now);
                }
            }

            auditService.log("PRODUCT", null, "IMPORT", Map.of("rows", rows, "created", created, "updated", updated,
                    "deprecated", deprecated, "categoriesCreated", lookups.categoriesCreated, "suppliersCreated", lookups.suppliersCreated));
            return new ImportResult(rows, created, updated, unchanged, deprecated,
                    lookups.categoriesCreated, lookups.suppliersCreated, warnings);
        }
    }

    /** All rows; a damaged file (e.g. cut off inside a quoted description) is the user's to fix, not a server error. */
    private static List<CSVRecord> records(CSVParser parser) {
        try {
            return parser.getRecords();
        } catch (UncheckedIOException | IllegalStateException e) {
            throw new IllegalArgumentException("The file is not a valid CSV (near line " + parser.getCurrentLineNumber()
                    + "): " + e.getMessage());
        }
    }

    /** Writes products (all, or only one status) as a Cin7-style CSV. */
    @Transactional(readOnly = true)
    public void exportCsv(Product.Status status, Writer out) throws IOException {
        Writer buffered = new BufferedWriter(out);
        // BOM so Excel opens it as UTF-8
        buffered.write('﻿');
        CSVPrinter printer = new CSVPrinter(buffered, CSVFormat.DEFAULT.builder().setHeader(COLUMNS.toArray(String[]::new)).build());
        int page = 0;
        Slice<Product> slice;
        do {
            slice = productRepository.findForExport(status, PageRequest.of(page++, CHUNK, Sort.by("sku")));
            for (Product p : slice) printer.printRecord(row(p));
            printer.flush();
            em.clear();
        } while (slice.hasNext());
        printer.flush();
    }

    // ---- mapping ----

    private void apply(Product p, CsvColumns cols, CSVRecord r, Lookups lookups, List<String> warnings) {
        String sku = p.getSku();
        set(cols, r, "Name", p::setName);
        if (cols.has("Category")) {
            String name = cols.get(r, "Category");
            p.setCategory(name == null ? null : em.getReference(Category.class, lookups.category(name)));
        }
        set(cols, r, "Brand", p::setBrand);
        if (cols.has("Type")) p.setType("Service".equalsIgnoreCase(cols.get(r, "Type")) ? Product.Type.SERVICE : Product.Type.STOCK);
        if (cols.has("Status")) {
            String s = cols.get(r, "Status");
            p.setStatus(s != null && s.equalsIgnoreCase("DEPRECATED") ? Product.Status.DEPRECATED : Product.Status.ACTIVE);
        }
        if (cols.has("Sellable")) p.setSellable(!"No".equalsIgnoreCase(cols.get(r, "Sellable")));
        set(cols, r, "Barcode", p::setBarcode);
        if (cols.has("DefaultUnitOfMeasure")) {
            String unit = cols.get(r, "DefaultUnitOfMeasure");
            p.setUnit(unit == null ? "Each" : unit);
        } else if (p.getUnit() == null) {
            p.setUnit("Each");
        }

        price(cols, r, "AverageCost", p::setCostPrice, sku, warnings);
        price(cols, r, "PriceTier1", p::setSellPrice, sku, warnings);
        price(cols, r, "PriceTier2", p::setTradePrice, sku, warnings);
        quantity(cols, r, "MinimumBeforeReorder", p::setMinStock, sku, warnings);
        quantity(cols, r, "ReorderQuantity", p::setReorderQuantity, sku, warnings);
        if (cols.has("LastSuppliedBy")) {
            String name = cols.get(r, "LastSuppliedBy");
            p.setDefaultSupplier(name == null ? null : em.getReference(Supplier.class, lookups.supplier(name)));
        }
        set(cols, r, "SupplierProductCode", p::setSupplierProductCode);

        if (cols.has("ProductFamilySKU")) {
            p.setFamilySku(cols.get(r, "ProductFamilySKU"));
            p.setFamilyName(p.getFamilySku() == null ? null : cols.get(r, "ProductFamilyName"));
            List<VariantOption> options = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                String name = cols.get(r, "ProductFamilyOption" + i + "Name");
                String value = cols.get(r, "ProductFamilyOption" + i + "Value");
                if (name != null && value != null) options.add(new VariantOption(name, value));
            }
            p.setVariantOptions(p.getFamilySku() == null || options.isEmpty() ? null : options);
        }

        set(cols, r, "Description", p::setDescription);
        set(cols, r, "ShortDescription", p::setShortDescription);

        // Reference fields: a copy, so dirty checking sees the change
        ProductDetails d = copy(p.getDetails());
        set(cols, r, "CommaDelimitedTags", d::setTags);
        price(cols, r, "PriceTier3", d::setPriceTier3, sku, warnings);
        price(cols, r, "PriceTier4", d::setPriceTier4, sku, warnings);
        set(cols, r, "SupplierProductName", d::setSupplierProductName);
        price(cols, r, "SupplierFixedPrice", d::setSupplierPrice, sku, warnings);
        quantity(cols, r, "Weight", d::setWeight, sku, warnings);
        quantity(cols, r, "Length", d::setLength, sku, warnings);
        quantity(cols, r, "Width", d::setWidth, sku, warnings);
        quantity(cols, r, "Height", d::setHeight, sku, warnings);
        // Units only mean something next to a value
        if (cols.has("WeightUnits")) d.setWeightUnit(d.getWeight() == null ? null : cols.get(r, "WeightUnits"));
        if (cols.has("DimensionUnits")) {
            boolean anySize = d.getLength() != null || d.getWidth() != null || d.getHeight() != null;
            d.setDimensionUnit(anySize ? cols.get(r, "DimensionUnits") : null);
        }
        set(cols, r, "PurchaseTaxRule", d::setPurchaseTaxRule);
        set(cols, r, "SaleTaxRule", d::setSaleTaxRule);
        set(cols, r, "HSCode", d::setHsCode);
        set(cols, r, "CountryOfOrigin", d::setCountryOfOrigin);
        set(cols, r, "InternalNote", d::setInternalNote);
        p.setDetails(d);
    }

    private List<Object> row(Product p) {
        ProductDetails d = p.getDetails() == null ? new ProductDetails() : p.getDetails();
        List<VariantOption> o = p.getVariantOptions() == null ? List.of() : p.getVariantOptions();
        List<Object> v = new ArrayList<>();
        v.add(p.getSku());
        v.add(p.getName());
        v.add(p.getCategory() == null ? "" : p.getCategory().getName());
        v.add(nz(p.getBrand()));
        v.add(p.getType() == Product.Type.SERVICE ? "Service" : "Stock");
        v.add(p.getStatus().name());
        v.add(p.isSellable() ? "Yes" : "No");
        v.add(nz(p.getBarcode()));
        v.add(p.getUnit());
        v.add(num(p.getCostPrice()));
        v.add(num(p.getSellPrice()));
        v.add(num(p.getTradePrice()));
        v.add(num(d.getPriceTier3()));
        v.add(num(d.getPriceTier4()));
        v.add(num(p.getMinStock()));
        v.add(num(p.getReorderQuantity()));
        v.add(p.getDefaultSupplier() == null ? "" : p.getDefaultSupplier().getName());
        v.add(nz(p.getSupplierProductCode()));
        v.add(nz(d.getSupplierProductName()));
        v.add(num(d.getSupplierPrice()));
        v.add(num(d.getWeight()));
        v.add(nz(d.getWeightUnit()));
        v.add(num(d.getLength()));
        v.add(num(d.getWidth()));
        v.add(num(d.getHeight()));
        v.add(nz(d.getDimensionUnit()));
        v.add(nz(p.getFamilySku()));
        v.add(nz(p.getFamilyName()));
        for (int i = 0; i < 3; i++) {
            v.add(i < o.size() ? o.get(i).name() : "");
            v.add(i < o.size() ? o.get(i).value() : "");
        }
        v.add(nz(d.getTags()));
        v.add(nz(d.getPurchaseTaxRule()));
        v.add(nz(d.getSaleTaxRule()));
        v.add(nz(d.getHsCode()));
        v.add(nz(d.getCountryOfOrigin()));
        v.add(nz(p.getDescription()));
        v.add(nz(p.getShortDescription()));
        v.add(nz(d.getInternalNote()));
        return v;
    }

    /** All imported fields, to tell whether a row changed anything. */
    private static String fingerprint(Product p) {
        return String.join("\u0001", Arrays.asList(p.getName(), p.getUnit(), String.valueOf(p.getStatus()), String.valueOf(p.getType()),
                String.valueOf(p.isSellable()), p.getBrand(), p.getBarcode(), plain(p.getCostPrice()), plain(p.getSellPrice()),
                plain(p.getTradePrice()), plain(p.getMinStock()), plain(p.getReorderQuantity()), p.getSupplierProductCode(),
                p.getFamilySku(), p.getFamilyName(), String.valueOf(p.getVariantOptions()), p.getDescription(),
                p.getShortDescription(), String.valueOf(normalized(p.getDetails())),
                p.getCategory() == null ? null : String.valueOf(p.getCategory().getId()),
                p.getDefaultSupplier() == null ? null : String.valueOf(p.getDefaultSupplier().getId())).stream()
                .map(s -> s == null ? "" : s).toList());
    }

    // ---- helpers ----

    private void checkPlan(long current, long adding) {
        Plan plan = subscriptionService.getCurrentPlan(TenantContext.get());
        if (plan.getMaxProducts() >= 0 && current + adding > plan.getMaxProducts()) {
            throw new PlanLimitExceededException("products", plan.getMaxProducts(), plan.getTier().name(),
                    "This import adds " + adding + " products, but the " + plan.getTier().name() + " plan allows "
                            + plan.getMaxProducts() + " in total (you have " + current + "). Upgrade the plan first.");
        }
    }

    /** Category and supplier ids by name (case-insensitive), created on first use. */
    private final class Lookups {
        final Map<String, Long> categories = new HashMap<>();
        final Map<String, Long> suppliers = new HashMap<>();
        int categoriesCreated, suppliersCreated;

        Lookups() {
            categoryRepository.findAll().forEach(c -> categories.putIfAbsent(key(c.getName()), c.getId()));
            supplierRepository.findAll().forEach(s -> suppliers.putIfAbsent(key(s.getName()), s.getId()));
        }

        Long category(String name) {
            return categories.computeIfAbsent(key(name), k -> {
                Category c = new Category();
                c.setName(name);
                categoriesCreated++;
                return categoryRepository.save(c).getId();
            });
        }

        Long supplier(String name) {
            return suppliers.computeIfAbsent(key(name), k -> {
                Supplier s = new Supplier();
                s.setName(name);
                suppliersCreated++;
                return supplierRepository.save(s).getId();
            });
        }

        private String key(String name) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }

    private interface Setter<T> {
        void set(T value);
    }

    private static void set(CsvColumns cols, CSVRecord r, String column, Setter<String> setter) {
        if (cols.has(column)) setter.set(cols.get(r, column));
    }

    /** Money: 0 and 9999.99 mean "not set" in Cin7. */
    private static void price(CsvColumns cols, CSVRecord r, String column, Setter<BigDecimal> setter, String sku, List<String> warnings) {
        if (!cols.has(column)) return;
        BigDecimal v = decimal(cols.get(r, column), sku, column, warnings);
        setter.set(v == null || v.signum() <= 0 || v.compareTo(PLACEHOLDER_PRICE) >= 0 ? null : v);
    }

    /** Quantities and sizes: 0 means "not set" in Cin7. */
    private static void quantity(CsvColumns cols, CSVRecord r, String column, Setter<BigDecimal> setter, String sku, List<String> warnings) {
        if (!cols.has(column)) return;
        BigDecimal v = decimal(cols.get(r, column), sku, column, warnings);
        setter.set(v == null || v.signum() <= 0 ? null : v);
    }

    private static BigDecimal decimal(String s, String sku, String column, List<String> warnings) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            warn(warnings, sku + ": " + column + " '" + s + "' is not a number, left empty");
            return null;
        }
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
    }

    private static ProductDetails copy(ProductDetails d) {
        ProductDetails c = new ProductDetails();
        if (d == null) return c;
        c.setTags(d.getTags());
        c.setPriceTier3(d.getPriceTier3());
        c.setPriceTier4(d.getPriceTier4());
        c.setSupplierProductName(d.getSupplierProductName());
        c.setSupplierPrice(d.getSupplierPrice());
        c.setWeight(d.getWeight());
        c.setWeightUnit(d.getWeightUnit());
        c.setLength(d.getLength());
        c.setWidth(d.getWidth());
        c.setHeight(d.getHeight());
        c.setDimensionUnit(d.getDimensionUnit());
        c.setPurchaseTaxRule(d.getPurchaseTaxRule());
        c.setSaleTaxRule(d.getSaleTaxRule());
        c.setHsCode(d.getHsCode());
        c.setCountryOfOrigin(d.getCountryOfOrigin());
        c.setInternalNote(d.getInternalNote());
        return c;
    }

    // BigDecimal scale differs between file (2.57) and database (2.5700): compare by value
    private static String normalized(ProductDetails d) {
        if (d == null) return "";
        ProductDetails c = copy(d);
        c.setPriceTier3(strip(d.getPriceTier3()));
        c.setPriceTier4(strip(d.getPriceTier4()));
        c.setSupplierPrice(strip(d.getSupplierPrice()));
        c.setWeight(strip(d.getWeight()));
        c.setLength(strip(d.getLength()));
        c.setWidth(strip(d.getWidth()));
        c.setHeight(strip(d.getHeight()));
        return c.toString();
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
