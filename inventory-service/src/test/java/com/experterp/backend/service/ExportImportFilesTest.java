package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import com.experterp.backend.service.ProductDataFileService.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Export / Import menus like Cin7 (X1–X4): each file exported then imported back changes nothing; wrong files are refused. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ExportImportFilesTest {

    @Autowired ProductDataFileService files;
    @Autowired SupplierFileService supplierFiles;
    @Autowired OrderListExportService listExport;
    @Autowired ProductUnitService units;
    @Autowired KitService kits;
    @Autowired StockMovementService movements;
    @Autowired StockMovementRepository movementRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Warehouse main, second;
    Product gloves, glasses, kit;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        tenant("Files " + id);
        as("ROLE_ADMIN");
        main = warehouse("Main " + id);
        second = warehouse("Second " + id);
        gloves = product("GLOVES", Product.Type.STOCK, "4.50", "3.75");
        gloves.getDetails().setPriceTier3(new BigDecimal("3.10"));
        gloves = productRepository.save(gloves);
        glasses = product("GLASSES", Product.Type.STOCK, "9.99", null);
        kit = product("STARTER", Product.Type.KIT, "15.00", null);
        in(gloves, main, 10, "2.00");
        in(gloves, main, 5, "3.00");
        in(glasses, second, 4, "1.50");
        units.save(gloves.getId(), List.of(new ProductUnitService.UnitRequest("Box", new BigDecimal("12"), "BOX-BC", null, null)));
        kits.setComponents(kit.getId(), List.of(new KitService.ComponentRequest(gloves.getId(), new BigDecimal("2")),
                new KitService.ComponentRequest(glasses.getId(), BigDecimal.ONE)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void eachProductFileExportedThenImportedBackChangesNothing() throws Exception {
        String inventory = export(Kind.INVENTORY_LIST);
        assertThat(inventory).contains("ProductCode,Name,Category").contains("Description");
        assertThat(export(Kind.INVENTORY_LIST_NO_DESCRIPTIONS)).doesNotContain("Description").contains("GLOVES-" + id);
        ProductImportService.ImportResult inv = (ProductImportService.ImportResult) importFile(Kind.INVENTORY_LIST, inventory, false);
        assertThat(inv.created()).isZero();
        assertThat(inv.updated()).isZero();

        String stock = export(Kind.STOCK_ON_HAND);
        assertThat(stock).startsWith("﻿ProductCode,Quantity,AverageCost,Location,Bin");
        // One row per batch left: gloves at £2 and £3 in Main, glasses in Second
        assertThat(stock).contains("GLOVES-" + id + ",10.0000,2.000000,Main " + id)
                .contains("GLOVES-" + id + ",5.0000,3.000000,Main " + id)
                .contains("GLASSES-" + id + ",4.0000,1.500000,Second " + id);
        StockImportService.ImportResult sameStock = (StockImportService.ImportResult) importFile(Kind.STOCK_ON_HAND, stock, true);
        assertThat(sameStock.changed()).isZero();
        assertThat(sameStock.warehouseName()).contains("Main " + id).contains("Second " + id);

        String uom = export(Kind.UNITS_OF_MEASURE);
        assertThat(uom).contains("GLOVES-" + id + ",GLOVES,Each,,GLOVES (Box),Box,12,True");
        ProductDataFileService.ImportResult u = (ProductDataFileService.ImportResult) importFile(Kind.UNITS_OF_MEASURE, uom, false);
        assertThat(u.changed()).isZero();
        assertThat(u.unchanged()).isEqualTo(1);

        String bom = export(Kind.ASSEMBLY_BOM);
        assertThat(bom).contains("STARTER-" + id + ",STARTER,GLOVES-" + id + ",GLOVES,2.0000");
        ProductDataFileService.ImportResult b = (ProductDataFileService.ImportResult) importFile(Kind.ASSEMBLY_BOM, bom, false);
        assertThat(b.changed()).isZero();
        assertThat(b.unchanged()).isEqualTo(1);

        String prices = export(Kind.SIMPLE_PRICES);
        assertThat(prices).contains("GLOVES-" + id + ",GLOVES,4.5000,3.7500,3.1000,,");
        ProductDataFileService.ImportResult p = (ProductDataFileService.ImportResult) importFile(Kind.SIMPLE_PRICES, prices, false);
        assertThat(p.changed()).isZero();
    }

    @Test
    void importedFilesChangeWhatTheySayAndKeepTheRest() throws Exception {
        // Stock on hand: the file's quantities per warehouse, the preview writes nothing
        String stock = "ProductCode,Quantity,AverageCost,Location,Bin,BatchOrSerialNumber,ExpiryDate_YYYYMMDD,ReceivedDate_YYYYMMDD\n"
                + "GLOVES-" + id + ",3,2.00,main " + id + ",,,,20261101\n"
                + "GLOVES-" + id + ",4,4.00,Main " + id + ",,,,20261102\n";
        StockImportService.ImportResult preview = (StockImportService.ImportResult) importFile(Kind.STOCK_ON_HAND, stock, false);
        assertThat(preview.applied()).isFalse();
        assertThat(preview.changed()).isEqualTo(1);
        assertThat(movementRepository.calculateStock(gloves.getId(), main.getId())).isEqualByComparingTo("15");
        importFile(Kind.STOCK_ON_HAND, stock, true);
        assertThat(movementRepository.calculateStock(gloves.getId(), main.getId())).isEqualByComparingTo("7");
        // Second is not in the file: left alone
        assertThat(movementRepository.calculateStock(glasses.getId(), second.getId())).isEqualByComparingTo("4");
        assertThatThrownBy(() -> importFile(Kind.STOCK_ON_HAND, stock.replace("Main " + id, "Nowhere"), true))
                .hasMessageContaining("No warehouse called Nowhere");

        // Units: a new unit added, the existing one keeps its barcode
        importFile(Kind.UNITS_OF_MEASURE, "Action,BaseSKU,AdditionalUnitsOfMeasureName,NumberOfBaseUnitsInAdditionalUnit\n"
                + ",GLOVES-" + id + ",Box,12\n,GLOVES-" + id + ",Carton,120\n", false);
        assertThat(units.units(gloves.getId())).extracting(ProductUnitService.Unit::name).containsExactly("Box", "Carton");
        assertThat(units.units(gloves.getId()).get(0).barcode()).isEqualTo("BOX-BC");
        importFile(Kind.UNITS_OF_MEASURE, "Action,BaseSKU,AdditionalUnitsOfMeasureName,NumberOfBaseUnitsInAdditionalUnit\n"
                + "Delete,GLOVES-" + id + ",Carton,120\n", false);
        assertThat(units.units(gloves.getId())).extracting(ProductUnitService.Unit::name).containsExactly("Box");

        // Assembly BOM: the kit's components become the file's; a product that is not a kit is reported
        ProductDataFileService.ImportResult bom = (ProductDataFileService.ImportResult) importFile(Kind.ASSEMBLY_BOM,
                "Action,ProductSKU,ComponentSKU,Quantity\n,STARTER-" + id + ",GLOVES-" + id + ",3\n,GLOVES-" + id + ",GLASSES-" + id + ",1\n", false);
        assertThat(bom.changed()).isEqualTo(1);
        assertThat(bom.warnings()).anyMatch(w -> w.contains("not a kit"));
        assertThat(kits.recipe(kit.getId())).hasSize(1).containsValue(new BigDecimal("3.0000"));

        // Simple prices: tiers of products that exist; no product is created; 9999.99 = no price
        ProductDataFileService.ImportResult prices = (ProductDataFileService.ImportResult) importFile(Kind.SIMPLE_PRICES,
                "ProductCode,Name,PriceTier1,PriceTier2,PriceTier3,PriceTier4,PriceTier5\n"
                        + "GLOVES-" + id + ",x,5.25,9999.99,0,2.50,7\nNEW-" + id + ",New,1,1,1,1,0\n", false);
        assertThat(prices.changed()).isEqualTo(1);
        assertThat(prices.warnings()).anyMatch(w -> w.contains("tiers 5 to 10")).anyMatch(w -> w.contains("NEW-" + id));
        Product g = productRepository.findById(gloves.getId()).orElseThrow();
        assertThat(g.getSellPrice()).isEqualByComparingTo("5.25");
        assertThat(g.getTradePrice()).isNull();
        assertThat(g.getDetails().getPriceTier3()).isNull();
        assertThat(g.getDetails().getPriceTier4()).isEqualByComparingTo("2.50");
        assertThat(g.getName()).isEqualTo("GLOVES");
        assertThat(productRepository.findBySkuIn(List.of("NEW-" + id))).isEmpty();
    }

    @Test
    void aFileOfAnotherTypeIsRefusedAndOtherOrganisationsNeverAppear() throws Exception {
        String prices = export(Kind.SIMPLE_PRICES);
        assertThatThrownBy(() -> importFile(Kind.UNITS_OF_MEASURE, prices, false))
                .hasMessageContaining("simple prices file, not the units of measure");
        assertThatThrownBy(() -> importFile(Kind.INVENTORY_LIST, "Name,Price\nX,1\n", false))
                .hasMessageContaining("not Cin7's inventory list file");
        assertThatThrownBy(() -> importFile(Kind.INVENTORY_LIST_NO_DESCRIPTIONS, prices, false))
                .hasMessageContaining("Import → Inventory list");

        Long mine = TenantContext.get();
        tenant("Other " + id);
        Warehouse w = warehouse("Other " + id);
        Product other = product("OTHER", Product.Type.STOCK, "1", null);
        in(other, w, 2, "1.00");
        TenantContext.set(mine);
        for (Kind k : Kind.values()) assertThat(export(k)).doesNotContain("OTHER-" + id);
    }

    @Test
    void withoutThePermissionToSeeCostsTheCostColumnsAreLeftOut() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("staff", null,
                List.of(new SimpleGrantedAuthority("PERM_PRODUCT_VIEW"), new SimpleGrantedAuthority("PERM_DATA_IMPORT"))));
        String inventory = export(Kind.INVENTORY_LIST);
        assertThat(inventory.lines().findFirst().orElseThrow()).doesNotContain("AverageCost").doesNotContain("SupplierFixedPrice");
        assertThat(export(Kind.STOCK_ON_HAND)).contains("GLOVES-" + id + ",10.0000,,Main " + id);
        // Imported back, the costs stay
        importFile(Kind.INVENTORY_LIST, inventory, false);
        assertThat(productRepository.findById(gloves.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("2.00");
    }

    @Test
    void suppliersExportAndImportInCin7Columns() throws Exception {
        Supplier s = new Supplier();
        s.setName("Acme " + id);
        s.setPaymentTerm("30 Day EOM");
        s.setTaxRule("20% (VAT on Expenses)");
        s.setContactName("Ann");
        s.setEmail("ann@acme.test");
        supplierRepository.save(s);
        StringWriter out = new StringWriter();
        supplierFiles.export(SupplierFileService.Kind.SUPPLIERS, out);
        String csv = out.toString();
        assertThat(csv).startsWith("﻿Name,Status,Currency,PaymentTerm,TaxRule,AccountPayable");
        assertThat(csv).contains("Acme " + id + ",Active,,30 Day EOM,20% (VAT on Expenses)");
        SupplierFileService.ImportResult same = supplierFiles.importCsv(SupplierFileService.Kind.SUPPLIERS, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(same.created()).isZero();
        assertThat(same.updated()).isZero();

        // Cin7 writes one row per contact: the default contact's row gives the contact; a new name is created
        String cin7 = "Name,Status,Currency,PaymentTerm,ContactName,Phone,Email,ContactDefault\n"
                + "Bolt " + id + ",Active,GBP,7 days,.Sales,0111,sales@bolt.test,False\n"
                + "Bolt " + id + ",Active,GBP,7 days,.Accounts,0222,acc@bolt.test,True\n"
                + "acme " + id + ",Deprecated,GBP,30 Day EOM,Ann,,ann@acme.test,True\n";
        SupplierFileService.ImportResult r = supplierFiles.importCsv(SupplierFileService.Kind.SUPPLIERS, cin7.getBytes(StandardCharsets.UTF_8));
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.updated()).isEqualTo(1);
        Supplier bolt = supplierRepository.findFirstByNameIgnoreCaseOrderByIdAsc("Bolt " + id).orElseThrow();
        assertThat(bolt.getContactName()).isEqualTo(".Accounts");
        assertThat(bolt.getPhone()).isEqualTo("0222");
        Supplier acme = supplierRepository.findFirstByNameIgnoreCaseOrderByIdAsc("Acme " + id).orElseThrow();
        assertThat(acme.getStatus()).isEqualTo("Deprecated");
        assertThat(acme.getTaxRule()).isEqualTo("20% (VAT on Expenses)");

        assertThatThrownBy(() -> supplierFiles.importCsv(SupplierFileService.Kind.SUPPLIERS,
                "Action,Name,AddressType,AddressLine1\n,Bolt,Billing,1 Road\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("addresses or contacts file");
    }

    @Test
    void listExportsWriteTheirHeadersEvenWhenEmpty() throws Exception {
        StringWriter sales = new StringWriter();
        listExport.sales(null, null, "nothing-matches-" + id, sales);
        assertThat(sales.toString().trim()).isEqualTo("﻿" + String.join(",", OrderListExportService.SALES));
        StringWriter payable = new StringWriter();
        listExport.accountsPayable(payable);
        assertThat(payable.toString()).startsWith("﻿Supplier,Type,Number,Date,Due date,Days overdue,Total,Outstanding");
    }

    // ---- helpers ----

    private String export(Kind kind) throws Exception {
        StringWriter out = new StringWriter();
        files.export(kind, out);
        return out.toString();
    }

    private Object importFile(Kind kind, String csv, boolean apply) throws Exception {
        return files.importFile(kind, csv.getBytes(StandardCharsets.UTF_8), "test.csv", false, apply);
    }

    private void tenant(String name) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setSlug(name.toLowerCase().replace(' ', '-'));
        TenantContext.set(tenantRepository.save(t).getId());
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name);
        return warehouseRepository.save(w);
    }

    private Product product(String sku, Product.Type type, String sell, String trade) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setType(type);
        p.setSellPrice(new BigDecimal(sell));
        if (trade != null) p.setTradePrice(new BigDecimal(trade));
        p.setCostPrice(new BigDecimal("2.00"));
        return productRepository.save(p);
    }

    private void in(Product p, Warehouse w, int n, String cost) {
        movements.createMovement(p, w, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal(cost));
    }
}
