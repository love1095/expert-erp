package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase N2 / N3: pick lists in bin walk order, pack into cartons, ship; part stock; scanning; Auto Pick + Pack on import. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PickPackTest {

    @Autowired PickPackService pickPack;
    @Autowired BinService bins;
    @Autowired SaleOrderService saleOrderService;
    @Autowired StockMovementService movements;
    @Autowired TaskImportService taskImport;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Warehouse main;
    Product gloves, boots, vest;
    BinService.Bin a, b;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Pick " + id);
        t.setSlug("pick-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        gloves = product("GLOVES", "5012345678900");
        boots = product("BOOTS", null);
        vest = product("VEST", null);
        a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        b = bins.saveBin(null, new BinService.BinRequest(main.getId(), "B-01", 20, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), a.id());
        bins.setDefaultBin(boots.getId(), main.getId(), b.id());
        in(gloves, 10);      // into A-01
        in(boots, 5);        // into B-01
        in(vest, 4);         // no bin
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aWaveIsPickedInWalkOrderPackedIntoCartonsAndShipped() {
        SaleOrderResponse o1 = order("Abbey", gloves, 3, boots, 2);
        SaleOrderResponse o2 = order("Bolton", gloves, 2, vest, 1);

        // One pick list for both orders, in the order the picker walks: A-01, B-01, then what is in no bin
        PickPackService.PickList pl = pickPack.create(List.of(o2.getId(), o1.getId()));
        assertThat(pl.orders()).containsExactlyInAnyOrder(o1.getOrderNumber(), o2.getOrderNumber());
        assertThat(pl.lines()).extracting(PickPackService.PickLine::bin).containsExactly("A-01", "A-01", "B-01", null);
        assertThat(pickPack.order(o1.getId()).stage()).isEqualTo("PICKING");
        assertThatThrownBy(() -> pickPack.create(List.of(o1.getId()))).hasMessageContaining("Nothing to pick");   // all on the open list
        assertThat(new String(pickPack.pickListPdf(pl.id()).bytes(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        // Picked in full: the stock leaves its bins (into the packing area)
        pickPack.confirm(pl.id(), null);
        assertThat(bins.productBins(gloves.getId(), main.getId()).bins().get(0).quantity()).isEqualByComparingTo("5");
        assertThat(bins.productBins(boots.getId(), main.getId()).bins().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(pickPack.order(o1.getId()).stage()).isEqualTo("PICKED");

        // Packed into two cartons; not more than picked
        PickPackService.OrderWarehouse w = pickPack.order(o1.getId());
        Long glovesLine = line(w, "GLOVES"), bootsLine = line(w, "BOOTS");
        assertThatThrownBy(() -> pickPack.pack(o1.getId(), List.of(new PickPackService.Carton(null, null, null, null,
                List.of(new PickPackService.PackLine(glovesLine, new BigDecimal("4"))))))).hasMessageContaining("Only 3 picked");
        pickPack.pack(o1.getId(), List.of(
                new PickPackService.Carton(new BigDecimal("2.5"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("20"), List.of(new PickPackService.PackLine(glovesLine, new BigDecimal("3")))),
                new PickPackService.Carton(new BigDecimal("4"), null, null, null, List.of(new PickPackService.PackLine(bootsLine, new BigDecimal("2"))))));
        assertThat(pickPack.order(o1.getId()).stage()).isEqualTo("PACKED");
        assertThat(new String(pickPack.despatchNote(o1.getId()).bytes(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        // Shipped: one shipment of both cartons with its tracking; the warehouse stock goes down, the bins already did
        w = pickPack.ship(o1.getId(), "DPD", "TRK123");
        assertThat(w.stage()).isEqualTo("SHIPPED");
        assertThat(w.packages()).allSatisfy(p -> assertThat(p.status()).isEqualTo("SHIPPED"));
        BinService.ProductBins g = bins.productBins(gloves.getId(), main.getId());
        assertThat(g.warehouseStock()).isEqualByComparingTo("7");                          // 10 − 3 shipped
        assertThat(g.unbinned()).isEqualByComparingTo("2");                                // Bolton's 2, picked, not shipped yet
        assertThatThrownBy(() -> pickPack.ship(o1.getId(), null, null)).hasMessageContaining("No packed cartons");
    }

    @Test
    void partStockIsPickedPackedAndShippedTheRestBackordered() {
        SaleOrderResponse o = order("Crewe", boots, 8, null, 0);                         // 5 in stock: 3 backordered
        PickPackService.PickList pl = pickPack.create(List.of(o.getId()));
        assertThat(pl.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("5"));
        pickPack.confirm(pl.id(), null);
        pickPack.pack(o.getId(), null);                                                     // everything picked, one carton
        PickPackService.OrderWarehouse w = pickPack.ship(o.getId(), "Royal Mail", null);
        assertThat(w.lines()).singleElement().satisfies(l -> {
            assertThat(l.shipped()).isEqualByComparingTo("5");
            assertThat(l.ordered()).isEqualByComparingTo("8");
        });
        assertThat(w.stage()).isEqualTo("AUTHORISED");                                      // 3 left, waiting for stock
        assertThat(bins.productBins(boots.getId(), main.getId()).warehouseStock()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> pickPack.create(List.of(o.getId()))).hasMessageContaining("Nothing to pick");
    }

    @Test
    void scanningAndAutoPickPackOnImport() throws Exception {
        SaleOrderResponse o = order("Derby", gloves, 2, null, 0);
        PickPackService.PickList pl = pickPack.create(List.of(o.getId()));
        assertThatThrownBy(() -> pickPack.scan(pl.id(), "VEST-" + id, null)).hasMessageContaining("wrong product");
        pickPack.scan(pl.id(), "5012345678900", null);                                    // the barcode
        pickPack.scan(pl.id(), "gloves-" + id, null);                                     // the SKU, any case
        assertThatThrownBy(() -> pickPack.scan(pl.id(), "GLOVES-" + id, null)).hasMessageContaining("picked in full");
        pickPack.confirm(pl.id(), null);
        assertThat(pickPack.pickList(pl.id()).lines().get(0).picked()).isEqualByComparingTo("2");

        // Cin7's Auto Pick + Pack on a sale task import: the order arrives packed, ready to ship
        Customer c = new Customer();
        c.setName("Import " + id);
        customerRepository.save(c);
        String csv = """
                RecordType,CustomerName,InvoiceNumber,Product,Quantity,Price/Amount
                Invoice,Import %1$s,SO-IMP-%1$s,,,
                InvoiceLines,Import %1$s,SO-IMP-%1$s,VEST-%1$s,2,10
                """.formatted(id);
        TaskImportService.Result r = taskImport.importCsv(TaskImportService.Kind.SALES, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "t.csv", new TaskImportService.Options(TaskImportService.InvoiceStatus.NONE, TaskImportService.Fulfilment.AUTO_PICK_PACK, false, false, false, true, main.getId()), true);
        assertThat(r.errors()).isEmpty();
        Long imported = saleOrderService.findAll(null, null, "SO-IMP-" + id, 0, 5).getContent().get(0).getId();
        assertThat(pickPack.order(imported).stage()).isEqualTo("PACKED");

        // Another organisation sees none of it
        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-pick-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThat(pickPack.pickLists(null)).isEmpty();
        assertThatThrownBy(() -> pickPack.pickList(pl.id())).hasMessageContaining("not found");
    }

    // ---- helpers ----

    private SaleOrderResponse order(String customer, Product p1, int q1, Product p2, int q2) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName(customer + " " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line l1 = new SaleOrderRequest.Line();
        l1.setProductId(p1.getId());
        l1.setQuantity(BigDecimal.valueOf(q1));
        l1.setUnitPrice(BigDecimal.TEN);
        if (p2 == null) so.setLines(List.of(l1));
        else {
            SaleOrderRequest.Line l2 = new SaleOrderRequest.Line();
            l2.setProductId(p2.getId());
            l2.setQuantity(BigDecimal.valueOf(q2));
            l2.setUnitPrice(BigDecimal.TEN);
            so.setLines(List.of(l1, l2));
        }
        return saleOrderService.confirm(saleOrderService.create(so).getId());
    }

    private static Long line(PickPackService.OrderWarehouse w, String sku) {
        return w.lines().stream().filter(l -> l.sku().startsWith(sku)).findFirst().orElseThrow().saleOrderLineId();
    }

    private Product product(String sku, String barcode) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setBarcode(barcode);
        return productRepository.save(p);
    }

    private void in(Product p, int n) {
        movements.createMovement(p, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal("5.00"));
    }
}
