package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.VariantOption;
import com.experterp.backend.exception.PlanLimitExceededException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Imports src/test/resources/products/cin7-inventory.csv: made-up rows in the Cin7 Core inventory list layout. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductImportServiceTest {

    @Autowired ProductImportService importService;
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Products " + id);
        t.setSlug("products-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void importsEveryColumnTheErpKeeps() throws IOException {
        ProductImportService.ImportResult result = importService.importCsv(fixture(), false);

        assertThat(result.rows()).isEqualTo(5);
        assertThat(result.created()).isEqualTo(5);
        assertThat(result.categoriesCreated()).isEqualTo(4);
        assertThat(result.suppliersCreated()).isEqualTo(2);

        var polo = productService.findById(id("TST-POLO-NAV-M"));
        assertThat(polo.getName()).isEqualTo("Test Polo Navy M");
        assertThat(polo.getCategoryName()).isEqualTo("Workwear");
        assertThat(polo.getBrand()).isEqualTo("Testbrand");
        assertThat(polo.getUnit()).isEqualTo("Each");
        assertThat(polo.getSellPrice()).isEqualByComparingTo("9.99");
        assertThat(polo.getTradePrice()).isEqualByComparingTo("7.50");
        assertThat(polo.getCostPrice()).isEqualByComparingTo("4.50");
        assertThat(polo.getMinStock()).isEqualByComparingTo("2");
        assertThat(polo.getReorderQuantity()).isEqualByComparingTo("10");
        assertThat(polo.getDefaultSupplierName()).isEqualTo("Test Supplier Ltd");
        assertThat(polo.getSupplierProductCode()).isEqualTo("SUP-1");
        assertThat(polo.getFamilySku()).isEqualTo("TST-POLO");
        assertThat(polo.getFamilyName()).isEqualTo("Test Polo Shirt");
        assertThat(polo.getVariantOptions()).containsExactly(new VariantOption("Colour", "Navy"), new VariantOption("Size", "M"));
        assertThat(polo.getDescription()).contains("<p>Soft polo</p>");
        assertThat(polo.getDetails().getWeight()).isEqualByComparingTo("180");
        assertThat(polo.getDetails().getWeightUnit()).isEqualTo("g");
        assertThat(polo.getDetails().getSaleTaxRule()).isEqualTo("20% (VAT on Income)");
        assertThat(polo.getDetails().getSupplierPrice()).isEqualByComparingTo("4.5");
        assertThat(polo.getDetails().getTags()).isEqualTo("Polos & T-Shirts");
        // 0 is Cin7's "not set": no tier 3, and no weight unit without a weight
        assertThat(polo.getDetails().getPriceTier3()).isNull();
        assertThat(productService.findById(id("TST-POLO-NAV-L")).getDetails().getWeightUnit()).isNull();

        // 9999.99 is Cin7's "no price" placeholder
        var boot = productService.findById(id("TST-BOOT-9"));
        assertThat(boot.getSellPrice()).isNull();
        assertThat(boot.getCostPrice()).isNull();
        assertThat(boot.getBarcode()).isEqualTo("5012345678900");
        assertThat(boot.getDescription()).isEqualTo("Steel toe\nWaterproof");
        assertThat(boot.getDetails().getInternalNote()).isEqualTo("See https://example.com/datasheet");

        var old = productService.findById(id("TST-OLD"));
        assertThat(old.getStatus()).isEqualTo(Product.Status.DEPRECATED);
        assertThat(old.getDetails().getPriceTier3()).isEqualByComparingTo("40");
        var delivery = productService.findById(id("TST-DELIVERY"));
        assertThat(delivery.getType()).isEqualTo(Product.Type.SERVICE);
        assertThat(delivery.isSellable()).isFalse();
    }

    @Test
    void importingTheSameFileAgainChangesNothing_andAChangedRowIsUpdated() throws IOException {
        importService.importCsv(fixture(), false);

        ProductImportService.ImportResult again = importService.importCsv(fixture(), false);
        assertThat(again.created()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(again.unchanged()).isEqualTo(5);
        assertThat(again.categoriesCreated()).isZero();

        String changed = text().replace("Test Polo Navy M,", "Test Polo Navy Medium,");
        ProductImportService.ImportResult third = importService.importCsv(stream(changed), false);
        assertThat(third.updated()).isEqualTo(1);
        assertThat(productService.findById(id("TST-POLO-NAV-M")).getName()).isEqualTo("Test Polo Navy Medium");
    }

    @Test
    void productsNotInTheFileCanBeMarkedDeprecated() throws IOException {
        Product extra = new Product();
        extra.setSku("NOT-IN-CIN7");
        extra.setName("Old ERP product");
        extra.setUnit("pcs");
        productRepository.save(extra);

        ProductImportService.ImportResult result = importService.importCsv(fixture(), true);

        assertThat(result.deprecated()).isEqualTo(1);
        assertThat(productService.findById(extra.getId()).getStatus()).isEqualTo(Product.Status.DEPRECATED);
        // The list shows active products by default, like Cin7
        assertThat(productService.findAll(null, Product.Status.ACTIVE, null, 0, 50).getContent())
                .extracting(r -> r.getSku()).containsExactlyInAnyOrder("TST-POLO-NAV-M", "TST-POLO-NAV-L", "TST-BOOT-9", "TST-DELIVERY");
        assertThat(productService.findAll(null, null, "polo navy", 0, 50).getTotalElements()).isEqualTo(2);
    }

    @Test
    void exportWritesTheSameLayoutBack() throws IOException {
        importService.importCsv(fixture(), false);

        StringWriter out = new StringWriter();
        importService.exportCsv(null, out);
        String csv = out.toString();
        assertThat(csv).startsWith("﻿ProductCode,Name,Category,Brand");
        assertThat(csv).contains("TST-POLO-NAV-M,Test Polo Navy M,Workwear,Testbrand,Stock,ACTIVE,Yes,,Each,4.5,9.99,7.5,");
        assertThat(csv).contains("\"Steel toe\nWaterproof\"");

        // Re-importing the export changes nothing
        ProductImportService.ImportResult again = importService.importCsv(stream(csv), false);
        assertThat(again.unchanged()).isEqualTo(5);
    }

    @Test
    void theImportRespectsThePlanLimit() {
        // A new organization is on the FREE plan (50 products)
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 48; i++) {
                Product p = new Product();
                p.setSku("EXISTING-" + i);
                p.setName("Existing " + i);
                p.setUnit("pcs");
                productRepository.save(p);
            }
        });

        assertThatThrownBy(() -> importService.importCsv(fixture(), false))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("adds 5 products").hasMessageContaining("Upgrade");
        assertThat(productRepository.findBySku("TST-BOOT-9")).isEmpty();
    }

    @Test
    void aFileWithoutProductCodesIsRefused() {
        assertThatThrownBy(() -> importService.importCsv(stream("Name,Price\nX,1\n"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ProductCode");
    }

    @Test
    void aDamagedFileIsRefusedWithAReason() {
        assertThatThrownBy(() -> importService.importCsv(stream("ProductCode,Name,Description\nA,B,\"never closed\n"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a valid CSV");
    }

    private Long id(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getId();
    }

    private static InputStream fixture() {
        return ProductImportServiceTest.class.getResourceAsStream("/products/cin7-inventory.csv");
    }

    private static String text() throws IOException {
        try (InputStream in = fixture()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
