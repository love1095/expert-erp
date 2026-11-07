package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
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

/** Phase P5: freight and duty on a bill spread over the stock it priced (by value or quantity), off by default. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LandedCostTest {

    @Autowired StockAdjustmentService settings;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired AccountingExportService exports;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    String id;
    Warehouse main;
    Product helmet, gloves;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Landed " + id);
        t.setSlug("landed-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        as("ROLE_ADMIN");
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        helmet = product("HELMET");
        gloves = product("GLOVES");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void offByDefaultTheFreightStaysAnExpense() {
        assertThat(settings.landedCost()).isEqualTo("OFF");
        PurchaseOrderResponse po = receivedOrder();
        InvoiceResponse bill = bill(po, "30");
        assertThat(cost(helmet)).isEqualByComparingTo("10");
        assertThat(exports.documentLines(bill.getId())).filteredOn(l -> l.sku() == null).singleElement().satisfies(l -> assertThat(l.stock()).isFalse());
    }

    @Test
    void byValueTheFreightIsSpreadOverTheStockItPricedAndTheSoldPartGoesToCostOfGoods() {
        settings.setLandedCost("value");
        PurchaseOrderResponse po = receivedOrder();
        sell(helmet, 5);                                   // 5 of the 10 helmets already gone

        // £30 freight over £100 of helmets and £100 of gloves: £15 each → helmets +£1.50, gloves +£0.75 a unit
        InvoiceResponse bill = bill(po, "30");
        assertThat(cost(helmet)).isEqualByComparingTo("11.50");
        assertThat(cost(gloves)).isEqualByComparingTo("5.75");
        // The 5 helmets already sold carry their £7.50 into cost of goods
        assertThat(jdbc.queryForObject("""
                SELECT SUM(a.amount) FROM cost_adjustments a JOIN cost_layers l ON l.id = a.layer_id
                 WHERE l.product_id = ? AND a.movement_id IS NOT NULL""", BigDecimal.class, helmet.getId())).isEqualByComparingTo("7.50");
        // The charge is posted to inventory, not to purchase charges
        assertThat(exports.documentLines(bill.getId())).filteredOn(l -> l.sku() == null).singleElement().satisfies(l -> assertThat(l.stock()).isTrue());

        // Cancelled: the costs go back
        invoiceService.cancel(bill.getId());
        assertThat(cost(helmet)).isEqualByComparingTo("10");
        assertThat(cost(gloves)).isEqualByComparingTo("5");
    }

    @Test
    void byQuantityEachUnitTakesTheSameShareAndOnlyAManagerChangesTheSetting() {
        settings.setLandedCost("QUANTITY");
        PurchaseOrderResponse po = receivedOrder();
        bill(po, "30");                                    // 10 + 20 units: £1 each
        assertThat(cost(helmet)).isEqualByComparingTo("11");
        assertThat(cost(gloves)).isEqualByComparingTo("6");
        assertThatThrownBy(() -> settings.setLandedCost("SOMETIMES")).hasMessageContaining("OFF, VALUE or QUANTITY");
        as("ROLE_USER");
        assertThatThrownBy(() -> settings.setLandedCost("OFF")).hasMessageContaining("manager");
    }

    // ---- helpers ----

    private PurchaseOrderResponse receivedOrder() {
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierName("Supplier " + id);
        po.setWarehouseId(main.getId());
        po.setLines(List.of(poLine(helmet, 10, "10"), poLine(gloves, 20, "5")));
        PurchaseOrderResponse ordered = purchaseOrderService.markOrdered(purchaseOrderService.create(po).getId());
        return purchaseOrderService.receive(ordered.getId());
    }

    private InvoiceResponse bill(PurchaseOrderResponse po, String freight) {
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("INV-" + id);
        BillRequest.Charge c = new BillRequest.Charge();
        c.setDescription("Freight");
        c.setUnitPrice(new BigDecimal(freight));
        b.setCharges(List.of(c));
        return invoiceService.billPurchaseOrder(po.getId(), b);
    }

    private void sell(Product p, int qty) {
        SaleOrderRequest so = new SaleOrderRequest();
        so.setCustomerName("Abbey " + id);
        so.setWarehouseId(main.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal("20"));
        so.setLines(List.of(l));
        Long id = saleOrderService.confirm(saleOrderService.create(so).getId()).getId();
        saleOrderService.fulfill(id, null);
    }

    private BigDecimal cost(Product p) {
        return jdbc.queryForObject("SELECT unit_cost FROM cost_layers WHERE product_id = ? ORDER BY id LIMIT 1", BigDecimal.class, p.getId());
    }

    private static PurchaseOrderRequest.Line poLine(Product p, int qty, String cost) {
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(new BigDecimal(cost));
        return l;
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority(role))));
    }

    private Product product(String sku) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        return productRepository.save(p);
    }
}
