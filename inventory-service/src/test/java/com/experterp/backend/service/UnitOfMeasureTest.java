package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase P3: units of measure — buy by the carton, sell by the box, stock and FIFO in the base unit. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UnitOfMeasureTest {

    @Autowired ProductUnitService units;
    @Autowired SaleOrderService saleOrderService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired AllocationService allocation;
    @Autowired PickPackService pickPack;
    @Autowired ProductService productService;
    @Autowired BinService bins;
    @Autowired StockMovementService movements;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Warehouse main;
    Product gloves;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Units " + id);
        t.setSlug("units-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        gloves = new Product();
        gloves.setSku("GLOVES-" + id);
        gloves.setName("Nitrile gloves");
        gloves.setUnit("Each");
        gloves.setSellPrice(new BigDecimal("0.50"));
        gloves.setCostPrice(new BigDecimal("0.20"));
        gloves = productRepository.save(gloves);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void boughtByTheCartonSoldByTheBoxStockAndCostInTheBaseUnit() {
        assertThatThrownBy(() -> units.save(gloves.getId(), List.of(unit("Each", "10", null)))).hasMessageContaining("own unit");
        assertThatThrownBy(() -> units.save(gloves.getId(), List.of(unit("Single", "1", null)))).hasMessageContaining("own unit");
        assertThatThrownBy(() -> units.save(gloves.getId(), List.of(unit("Box", "10", null), unit("box", "100", null)))).hasMessageContaining("twice");
        units.save(gloves.getId(), List.of(unit("Box of 100", "100", "45.00"), unit("Carton", "1000", null)));

        // 2 cartons at £150 each are on order as 2,000; received: 2,000 in stock at £0.15 each
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierName("Supplier " + id);
        po.setWarehouseId(main.getId());
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(gloves.getId());
        pl.setQuantity(new BigDecimal("2"));
        pl.setUnitName("carton");
        pl.setUnitCost(new BigDecimal("150"));
        po.setLines(List.of(pl));
        PurchaseOrderResponse ordered = purchaseOrderService.markOrdered(purchaseOrderService.create(po).getId());
        assertThat(ordered.getLines().get(0).getUnit()).isEqualTo("Carton");
        assertThat(productService.stock(gloves.getId()).onOrderTotal()).isEqualByComparingTo("2000");
        purchaseOrderService.receive(ordered.getId());
        assertThat(stock()).isEqualByComparingTo("2000");
        assertThat(layerCost()).isEqualByComparingTo("0.15");

        // The supplier bills £160 a carton: the layer is corrected to £0.16 each
        BillRequest bill = new BillRequest();
        bill.setSupplierInvoiceNumber("INV-" + id);
        BillRequest.Line bl = new BillRequest.Line();
        bl.setPurchaseOrderLineId(ordered.getLines().get(0).getId());
        bl.setQuantity(new BigDecimal("2"));
        bl.setUnitPrice(new BigDecimal("160"));
        bill.setLines(List.of(bl));
        InvoiceResponse b = invoiceService.billPurchaseOrder(ordered.getId(), bill);
        assertThat(b.getItems().get(0).getUnitName()).isEqualTo("Carton");
        assertThat(layerCost()).isEqualByComparingTo("0.16");

        // Sold: 3 boxes at the box's own price (£45, not 100 × £0.50); they reserve 300
        SaleOrderResponse so = saleOrderService.confirm(sell("Box of 100", 3, null).getId());
        assertThat(so.getLines().get(0).getUnitPrice()).isEqualByComparingTo("45.00");
        assertThat(so.getLines().get(0).getUnitName()).isEqualTo("Box of 100");
        assertThat(allocation.reservedByProduct(List.of(gloves.getId())).get(gloves.getId())).isEqualByComparingTo("300");
        // A carton without its own price: 1,000 × £0.50
        assertThat(sell("Carton", 1, null).getLines().get(0).getUnitPrice()).isEqualByComparingTo("500.00");

        // Shipped: 300 leave at £0.16 — £48 cost of goods for 3 boxes
        saleOrderService.fulfill(so.getId(), null);
        assertThat(stock()).isEqualByComparingTo("1700");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(c.quantity * c.unit_cost) FROM cost_consumptions c JOIN stock_movements m ON m.id = c.movement_id
                 WHERE m.type = 'SALE_OUT' AND m.source_line_id = ?""", BigDecimal.class, so.getLines().get(0).getId())).isEqualByComparingTo("48.00");

        // One box comes back: 100 return to stock
        SaleReturnRequest ret = new SaleReturnRequest();
        SaleReturnRequest.Line rl = new SaleReturnRequest.Line();
        rl.setSaleOrderLineId(so.getLines().get(0).getId());
        rl.setQuantity(BigDecimal.ONE);
        rl.setRestock(true);
        ret.setLines(List.of(rl));
        saleOrderService.createReturn(so.getId(), ret);
        assertThat(stock()).isEqualByComparingTo("1800");

        // A unit on an open order cannot be changed
        assertThatThrownBy(() -> units.save(gloves.getId(), List.of(unit("Box of 100", "100", "45.00")))).hasMessageContaining("Carton is on open orders");
        assertThatThrownBy(() -> sell("Bag", 1, null)).hasMessageContaining("no unit \"Bag\"");
    }

    @Test
    void boxesAreAllocatedAndPickedWhole() {
        units.save(gloves.getId(), List.of(unit("Box of 100", "100", null)));
        movements.createMovement(gloves, main, new BigDecimal("250"), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal("0.20"));
        SaleOrderResponse so = saleOrderService.confirm(sell("Box of 100", 3, null).getId());
        // 250 make 2 whole boxes: 2 allocated, 1 backordered
        assertThat(allocation.forOrders(List.of(so.getId())).get(so.getLines().get(0).getId()).allocated()).isEqualByComparingTo("2");

        PickPackService.PickList pl = pickPack.create(List.of(so.getId()));
        assertThat(pl.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("200"));    // picked in Each
        pickPack.confirm(pl.id(), List.of(new PickPackService.Picked(pl.lines().get(0).id(), new BigDecimal("150"))));
        assertThat(pickPack.order(so.getId()).lines().get(0).picked()).isEqualByComparingTo("1");                       // 1 whole box
        pickPack.pack(so.getId(), null);
        pickPack.ship(so.getId(), null, null);
        assertThat(stock()).isEqualByComparingTo("150");
    }

    private SaleOrderResponse sell(String unit, int qty, String price) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(gloves.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitName(unit);
        if (price != null) l.setUnitPrice(new BigDecimal(price));
        so.setLines(List.of(l));
        return saleOrderService.create(so);
    }

    private BigDecimal stock() {
        return bins.productBins(gloves.getId(), main.getId()).warehouseStock();
    }

    private BigDecimal layerCost() {
        return jdbc.queryForObject("SELECT unit_cost FROM cost_layers WHERE product_id = ? ORDER BY id LIMIT 1", BigDecimal.class, gloves.getId());
    }

    private static ProductUnitService.UnitRequest unit(String name, String factor, String sell) {
        return new ProductUnitService.UnitRequest(name, new BigDecimal(factor), null, sell == null ? null : new BigDecimal(sell), null);
    }
}
