package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockImportServiceTest {

    // The columns of Cin7's AvailabilityReport that matter, plus some it also has
    private static final String HEADER = "﻿Category,SKU,ProductName,Location,Bin,StockValue,OnHand,Available,Status\n";

    @Autowired StockImportService importService;
    @Autowired StockMovementService movementService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockMovementRepository movementRepository;
    @Autowired TenantRepository tenantRepository;

    String prefix;
    Warehouse main;
    Warehouse other;

    @BeforeEach
    void setUp() {
        useNewTenant();
        prefix = "T" + UUID.randomUUID().toString().substring(0, 6) + "-";
        main = warehouse("Main");
        other = warehouse("Other");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stockIsBroughtToTheFileQuantitiesWithCosts() throws IOException {
        Product helmet = product("HELMET", null);      // 0 → 10, costed from the file: 50 / 10
        Product gloves = product("GLOVES", "2.00");    // 8 → 3
        Product boots = product("BOOTS", "7.00");      // 4 → 4, unchanged
        Product masks = product("MASKS", "1.25");      // 0 → 6, valued at £0 by Cin7: costed at 0, as in Cin7
        Product caps = product("CAPS", "0.50");        // 0 → 2, no value in the row: product cost price
        Product vests = product("VESTS", null);        // not in the file: left alone
        add(gloves, main, 8);
        add(boots, main, 4);
        add(vests, main, 9);
        add(helmet, other, 100);                       // another warehouse is never touched

        String csv = HEADER
                + row("HELMET", "Expert Warehouse", "50.0000", "10.0000")
                + row("GLOVES", "Expert Warehouse", "6.0000", "3.0000")
                + row("BOOTS", "Expert Warehouse", "28.0000", "4.0000")
                + row("MASKS", "Expert Warehouse", "0.0000", "6.0000")
                + row("CAPS", "Expert Warehouse", "", "2.0000")
                + row("NOPE", "Expert Warehouse", "10.0000", "5.0000")        // unknown with stock: warning
                + row("NOPE-ZERO", "Expert Warehouse", "0.0000", "0.0000");   // unknown, no stock: ignored

        StockImportService.ImportResult preview = importService.importCsv(in(csv), "AvailabilityReport.csv", main.getId(), false);
        assertThat(preview.applied()).isFalse();
        assertThat(preview.changed()).isEqualTo(4);
        assertThat(preview.unchanged()).isEqualTo(1);
        assertThat(preview.unknownSkus()).isEqualTo(1);
        assertThat(preview.warnings()).singleElement().asString().contains(prefix + "NOPE");
        assertThat(preview.fileLocation()).isEqualTo("Expert Warehouse");
        // Before: 8 + 4 = 12; after: 10 + 3 + 4 + 6 + 2 = 25
        assertThat(preview.unitsBefore()).isEqualByComparingTo("12");
        assertThat(preview.unitsAfter()).isEqualByComparingTo("25");
        // Cin7's values 50 + 6 + 28 + 0, caps 2 × 0.50
        assertThat(preview.valueAfter()).isEqualByComparingTo("85.00");
        assertThat(preview.changes().get(0).sku()).isEqualTo(prefix + "HELMET");
        // A preview writes nothing
        assertThat(stock(helmet, main)).isEqualByComparingTo("0");

        StockImportService.ImportResult done = importService.importCsv(in(csv), "AvailabilityReport.csv", main.getId(), true);
        assertThat(done.applied()).isTrue();
        assertThat(stock(helmet, main)).isEqualByComparingTo("10");
        assertThat(stock(gloves, main)).isEqualByComparingTo("3");
        assertThat(stock(boots, main)).isEqualByComparingTo("4");
        assertThat(stock(masks, main)).isEqualByComparingTo("6");
        assertThat(stock(vests, main)).isEqualByComparingTo("9");
        assertThat(stock(helmet, other)).isEqualByComparingTo("100");

        StockMovement in = imported(helmet);
        assertThat(in.getType()).isEqualTo(MovementType.ADJUSTMENT_IN);
        assertThat(in.getUnitCost()).isEqualByComparingTo("5.00");
        assertThat(in.getReferenceId()).isEqualTo("AvailabilityReport.csv");
        assertThat(imported(masks).getUnitCost()).isEqualByComparingTo("0");
        assertThat(imported(caps).getUnitCost()).isEqualByComparingTo("0.50");
        StockMovement out = imported(gloves);
        assertThat(out.getType()).isEqualTo(MovementType.ADJUSTMENT_OUT);
        assertThat(out.getQuantity()).isEqualByComparingTo("5");
        assertThat(out.getUnitCost()).isNull();

        // The same file again changes nothing
        StockImportService.ImportResult again = importService.importCsv(in(csv), "AvailabilityReport.csv", main.getId(), true);
        assertThat(again.changed()).isZero();
        assertThat(again.unchanged()).isEqualTo(5);
    }

    @Test
    void aFileWithSeveralLocationsOrWithoutStockColumnsIsRefused() {
        product("HELMET", null);
        String twoLocations = HEADER
                + row("HELMET", "Expert Warehouse", "5", "1")
                + row("HELMET", "Second Site", "5", "1");
        assertThatThrownBy(() -> importService.importCsv(in(twoLocations), "f.csv", main.getId(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("several locations");
        assertThatThrownBy(() -> importService.importCsv(in("ProductCode,Name\nA,B\n"), "f.csv", main.getId(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OnHand");
    }

    @Test
    void anotherTenantsProductWithTheSameSkuIsNeverChanged() throws IOException {
        Product mine = product("HELMET", null);
        String sku = mine.getSku();
        Long myTenant = TenantContext.get();

        useNewTenant();
        Warehouse theirWarehouse = warehouse("Theirs");
        Product theirs = new Product();
        theirs.setSku(sku);
        theirs.setName("Their helmet");
        theirs.setUnit("pcs");
        theirs = productRepository.save(theirs);
        add(theirs, theirWarehouse, 2);

        TenantContext.set(myTenant);
        importService.importCsv(in(HEADER + row("HELMET", "Expert Warehouse", "30", "3")), "f.csv", main.getId(), true);
        assertThat(stock(mine, main)).isEqualByComparingTo("3");

        TenantContext.set(theirs.getTenantId());
        assertThat(stock(theirs, theirWarehouse)).isEqualByComparingTo("2");
    }

    @Test
    void receivingAPurchaseOrderRecordsTheLineCost() {
        Product helmet = product("HELMET", null);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierName("JSP");
        req.setWarehouseId(main.getId());
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("4"));
        l.setUnitCost(new BigDecimal("3.75"));
        req.setLines(List.of(l));
        purchaseOrderService.receive(purchaseOrderService.create(req).getId());

        StockMovement received = movementRepository.findAll().stream()
                .filter(m -> m.getProduct().getId().equals(helmet.getId()) && m.getType() == MovementType.PURCHASE_IN)
                .findFirst().orElseThrow();
        assertThat(received.getUnitCost()).isEqualByComparingTo("3.75");
    }

    private String row(String sku, String location, String value, String onHand) {
        return "PPE," + prefix + sku + ",Name," + location + ",," + value + "," + onHand + "," + onHand + ",ACTIVE\n";
    }

    private static ByteArrayInputStream in(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private void useNewTenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Stock import " + id);
        t.setSlug("stock-import-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
    }

    private Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name + "-" + UUID.randomUUID());
        return warehouseRepository.save(w);
    }

    private Product product(String sku, String cost) {
        Product p = new Product();
        p.setSku(prefix + sku);
        p.setName(sku);
        p.setUnit("pcs");
        p.setCostPrice(cost == null ? null : new BigDecimal(cost));
        return productRepository.save(p);
    }

    private void add(Product p, Warehouse w, int qty) {
        movementService.createMovement(p, w, BigDecimal.valueOf(qty), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "TEST", null);
    }

    private BigDecimal stock(Product p, Warehouse w) {
        return movementRepository.calculateStock(p.getId(), w.getId());
    }

    private StockMovement imported(Product p) {
        return movementRepository.findAll().stream()
                .filter(m -> m.getProduct().getId().equals(p.getId()) && m.getReferenceType() == ReferenceType.STOCK_IMPORT)
                .findFirst().orElseThrow();
    }
}
