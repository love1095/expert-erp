package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.ProductRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.SaleReturnRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase P2: kits — components, stock the components make, reserving, shipping at FIFO per component, returns, picking. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KitTest {

    @Autowired KitService kits;
    @Autowired SaleOrderService saleOrderService;
    @Autowired AllocationService allocation;
    @Autowired PickPackService pickPack;
    @Autowired ProductService productService;
    @Autowired StockMovementService movements;
    @Autowired BinService bins;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Warehouse main;
    Product gloves, glasses, service, starter;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Kit " + id);
        t.setSlug("kit-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        gloves = product("GLOVES", Product.Type.STOCK);
        glasses = product("GLASSES", Product.Type.STOCK);
        service = product("FITTING", Product.Type.SERVICE);
        starter = product("STARTER", Product.Type.KIT);
        in(gloves, 10, "2.00");
        in(glasses, 2, "5.00");
        in(glasses, 1, "7.00");      // FIFO: the £5 ones leave first
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aKitIsMadeOfComponentsItsStockIsWhatTheyMakeAndSellingItShipsThemAtTheirFifoCost() {
        // Components: stocked products only, each once, not the kit itself; only a kit has components
        assertThatThrownBy(() -> kits.setComponents(starter.getId(), List.of(c(starter, "1")))).hasMessageContaining("cannot contain itself");
        assertThatThrownBy(() -> kits.setComponents(starter.getId(), List.of(c(service, "1")))).hasMessageContaining("only stocked products");
        assertThatThrownBy(() -> kits.setComponents(starter.getId(), List.of(c(gloves, "1"), c(gloves, "2")))).hasMessageContaining("twice");
        assertThatThrownBy(() -> kits.setComponents(gloves.getId(), List.of(c(glasses, "1")))).hasMessageContaining("not a kit");
        kits.setComponents(starter.getId(), List.of(c(gloves, "2"), c(glasses, "1")));
        assertThat(kits.components(starter.getId())).extracting(KitService.Component::sku).containsExactly("GLASSES-" + id, "GLOVES-" + id);

        // It holds no stock of its own; it makes min(10 / 2, 3 / 1) = 3
        assertThatThrownBy(() -> in(starter, 1, "1.00")).hasMessageContaining("is a kit");
        assertThat(kits.buildable(starter.getId())).singleElement().satisfies(b -> {
            assertThat(b.onHand()).isEqualByComparingTo("3");
            assertThat(b.available()).isEqualByComparingTo("3");
        });
        assertThat(productService.stock(starter.getId()).available()).isEqualByComparingTo("3");

        // Two kits ordered reserve 4 gloves and 2 glasses: 1 kit left to sell
        SaleOrderResponse first = order(starter, 2);
        Map<Long, BigDecimal> reserved = allocation.reservedByProduct(List.of(gloves.getId(), glasses.getId()));
        assertThat(reserved.get(gloves.getId())).isEqualByComparingTo("4");
        assertThat(reserved.get(glasses.getId())).isEqualByComparingTo("2");
        assertThat(kits.buildable(starter.getId()).get(0).available()).isEqualByComparingTo("1");
        assertThat(kits.availableKits(List.of(starter.getId(), gloves.getId()))).containsOnlyKeys(starter.getId()).containsEntry(starter.getId(), 1);

        // A second order for 2 gets the 1 kit the glasses still make, 1 backordered
        SaleOrderResponse second = order(starter, 2);
        Map<Long, AllocationService.LineAllocation> a = allocation.forOrders(List.of(first.getId(), second.getId()));
        assertThat(a.get(first.getLines().get(0).getId()).allocated()).isEqualByComparingTo("2");
        assertThat(a.get(second.getLines().get(0).getId()).allocated()).isEqualByComparingTo("1");

        // Shipped: the components leave, each at its own FIFO cost — the kit's cost is 4 × £2 + 2 × £5 = £18
        saleOrderService.fulfill(first.getId(), null);
        assertThat(stock(gloves)).isEqualByComparingTo("6");
        assertThat(stock(glasses)).isEqualByComparingTo("1");
        Long line = first.getLines().get(0).getId();
        assertThat(cost(line)).isEqualByComparingTo("18.00");

        // One kit comes back to stock: its components return at the cost they left at
        SaleReturnRequest ret = new SaleReturnRequest();
        SaleReturnRequest.Line rl = new SaleReturnRequest.Line();
        rl.setSaleOrderLineId(line);
        rl.setQuantity(BigDecimal.ONE);
        rl.setRestock(true);
        ret.setLines(List.of(rl));
        saleOrderService.createReturn(first.getId(), ret);
        assertThat(stock(gloves)).isEqualByComparingTo("8");
        assertThat(stock(glasses)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
                SELECT l.original_unit_cost FROM cost_layers l JOIN stock_movements m ON m.id = l.movement_id
                 WHERE m.type = 'SALE_RETURN_IN' AND m.product_id = ?""", BigDecimal.class, glasses.getId())).isEqualByComparingTo("5");
    }

    @Test
    void aKitIsPickedAsItsComponentsFromTheirBinsThenPackedAndShipped() {
        kits.setComponents(starter.getId(), List.of(c(gloves, "2"), c(glasses, "1")));
        BinService.Bin a = bins.saveBin(null, new BinService.BinRequest(main.getId(), "A-01", 10, null));
        bins.move(gloves.getId(), main.getId(), null, a.id(), new BigDecimal("10"), "test");
        SaleOrderResponse so = order(starter, 2);

        PickPackService.PickList pl = pickPack.create(List.of(so.getId()));
        assertThat(pl.lines()).extracting(PickPackService.PickLine::sku).containsExactly("GLOVES-" + id, "GLASSES-" + id);   // A-01 first, then no bin
        assertThat(pl.lines()).extracting(PickPackService.PickLine::quantity).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("4"), new BigDecimal("2"));
        assertThat(pickPack.order(so.getId()).lines().get(0).picked()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> pickPack.create(List.of(so.getId()))).hasMessageContaining("Nothing to pick");

        // 3 gloves picked (not enough for 2 kits): 1 kit picked; the rest goes on a new list
        Long glovesLine = pl.lines().get(0).id(), glassesLine = pl.lines().get(1).id();
        pickPack.confirm(pl.id(), List.of(new PickPackService.Picked(glovesLine, new BigDecimal("3")), new PickPackService.Picked(glassesLine, new BigDecimal("2"))));
        assertThat(pickPack.order(so.getId()).lines().get(0).picked()).isEqualByComparingTo("1");
        assertThat(bins.productBins(gloves.getId(), main.getId()).bins().get(0).quantity()).isEqualByComparingTo("7");
        PickPackService.PickList rest = pickPack.create(List.of(so.getId()));
        assertThat(rest.lines()).extracting(PickPackService.PickLine::quantity).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2"), new BigDecimal("1"));
        pickPack.confirm(rest.id(), null);
        assertThat(pickPack.order(so.getId()).lines().get(0).picked()).isEqualByComparingTo("2");

        pickPack.pack(so.getId(), null);
        PickPackService.OrderWarehouse w = pickPack.ship(so.getId(), "DPD", "T1");
        assertThat(w.stage()).isEqualTo("SHIPPED");
        assertThat(stock(gloves)).isEqualByComparingTo("6");
        assertThat(stock(glasses)).isEqualByComparingTo("1");
    }

    @Test
    void stockedProductsCannotBecomeKitsAndOtherOrganisationsSeeNothing() {
        ProductRequest r = request(gloves, Product.Type.KIT);
        assertThatThrownBy(() -> productService.update(gloves.getId(), r)).hasMessageContaining("cannot become a kit");
        kits.setComponents(starter.getId(), List.of(c(gloves, "1")));
        productService.update(starter.getId(), request(starter, Product.Type.STOCK));     // no longer a kit: no components
        assertThat(kits.recipe(starter.getId())).isEmpty();

        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-kit-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThatThrownBy(() -> kits.components(starter.getId())).hasMessageContaining("not found");
        assertThat(kits.recipe(starter.getId())).isEmpty();
    }

    // ---- helpers ----

    private BigDecimal stock(Product p) {
        return bins.productBins(p.getId(), main.getId()).warehouseStock();
    }

    private BigDecimal cost(Long lineId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.quantity * c.unit_cost), 0) FROM cost_consumptions c JOIN stock_movements m ON m.id = c.movement_id
                 WHERE m.type = 'SALE_OUT' AND m.source_line_id = ?""", BigDecimal.class, lineId);
    }

    private SaleOrderResponse order(Product p, int qty) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal("30"));
        so.setLines(List.of(l));
        return saleOrderService.confirm(saleOrderService.create(so).getId());
    }

    private static KitService.ComponentRequest c(Product p, String qty) {
        return new KitService.ComponentRequest(p.getId(), new BigDecimal(qty));
    }

    private ProductRequest request(Product p, Product.Type type) {
        ProductRequest r = new ProductRequest();
        r.setSku(p.getSku());
        r.setName(p.getName());
        r.setUnit("Each");
        r.setType(type);
        return r;
    }

    private Product product(String sku, Product.Type type) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        p.setType(type);
        return productRepository.save(p);
    }

    private void in(Product p, int n, String cost) {
        movements.createMovement(p, main, BigDecimal.valueOf(n), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal(cost));
    }
}
