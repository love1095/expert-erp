package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DashboardServiceTest {

    @Autowired DashboardService dashboardService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void figuresCoverOnlyThisTenant() {
        // Another tenant with its own activity that must not show up
        useNewTenant();
        Warehouse otherWh = warehouse();
        Product otherProduct = product("OTHER", "1.00", null);
        receive(otherProduct, otherWh, 999);
        shippedOrder(otherProduct, otherWh, 5, "100");

        useNewTenant();
        Warehouse a = warehouse();
        Warehouse b = warehouse();
        Product helmet = product("HELMET", "4.00", "50");    // ends at 100 - 30 = 70 → fine
        Product gloves = product("GLOVES", "2.50", "20");    // ends at 10 → low
        Product boots = product("BOOTS", null, "5");         // no stock at all → low, no value

        receive(helmet, a, 100);
        receive(gloves, a, 10);
        // A transfer moves stock between warehouses: not counted as units in/out
        movementService.createMovement(helmet, a, BigDecimal.TEN, MovementType.TRANSFER_OUT, ReferenceType.MANUAL, "T", null);
        movementService.createMovement(helmet, b, BigDecimal.TEN, MovementType.TRANSFER_IN, ReferenceType.MANUAL, "T", null);

        SaleOrderResponse shipped = shippedOrder(helmet, a, 30, "12.50");  // 375.00, shipped
        SaleOrderResponse draft = order(gloves, a, 4, "5");                 // 20.00, draft
        SaleOrderResponse cancelled = order(gloves, a, 1, "1000");          // cancelled: excluded
        saleOrderService.cancel(cancelled.getId());
        SaleOrderResponse confirmed = saleOrderService.confirm(order(helmet, a, 2, "10").getId());  // 20.00, to ship

        DashboardResponse d = dashboardService.get(7);

        DashboardResponse.Kpis k = d.kpis();
        assertThat(k.products()).isEqualTo(3);
        assertThat(k.warehouses()).isEqualTo(2);
        // helmet 70 × 4.00 + gloves 10 × 2.50
        assertThat(k.stockValue()).isEqualByComparingTo("305.00");
        assertThat(k.lowStockProducts()).isEqualTo(2);
        assertThat(d.lowStock()).extracting(DashboardResponse.LowStock::name).containsExactly("BOOTS", "GLOVES");
        assertThat(k.ordersToShip()).isEqualTo(1);
        assertThat(k.draftOrders()).isEqualTo(1);
        assertThat(k.ordersInPeriod()).isEqualTo(3);
        assertThat(k.salesValueInPeriod()).isEqualByComparingTo("415.00");
        assertThat(k.unitsShippedInPeriod()).isEqualByComparingTo("30");
        assertThat(d.ordersByStatus()).containsEntry("DRAFT", 1L).containsEntry("CONFIRMED", 1L)
                .containsEntry("COMPLETED", 1L).containsEntry("CANCELLED", 1L);

        assertThat(d.daily()).hasSize(7);
        assertThat(d.daily().get(0).date()).isEqualTo(LocalDate.now().minusDays(6));
        DashboardResponse.Day today = d.daily().get(6);
        assertThat(today.date()).isEqualTo(LocalDate.now());
        assertThat(today.orders()).isEqualTo(3);
        assertThat(today.unitsIn()).isEqualByComparingTo("110");
        assertThat(today.unitsOut()).isEqualByComparingTo("30");

        assertThat(d.topProducts()).singleElement()
                .satisfies(t -> assertThat(t.sku()).startsWith("HELMET"))
                .satisfies(t -> assertThat(t.unitsShipped()).isEqualByComparingTo("30"));
        assertThat(d.recentOrders()).extracting(DashboardResponse.RecentOrder::id)
                .containsExactly(confirmed.getId(), cancelled.getId(), draft.getId(), shipped.getId());
    }

    @Test
    void anEmptyTenantGetsZeroesForEveryDay() {
        useNewTenant();
        DashboardResponse d = dashboardService.get(30);

        assertThat(d.daily()).hasSize(30).allSatisfy(day -> assertThat(day.orders()).isZero());
        assertThat(d.kpis().stockValue()).isEqualByComparingTo("0");
        assertThat(d.kpis().receivablesOutstanding()).isEqualByComparingTo("0");
        assertThat(d.topProducts()).isEmpty();
        assertThat(d.lowStock()).isEmpty();
    }

    private void useNewTenant() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Dash " + id);
        t.setSlug("dash-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
    }

    private Warehouse warehouse() {
        Warehouse w = new Warehouse();
        w.setName("WH-" + UUID.randomUUID());
        return warehouseRepository.save(w);
    }

    private Product product(String name, String cost, String min) {
        Product p = new Product();
        p.setSku(name + "-" + UUID.randomUUID());
        p.setName(name);
        p.setUnit("pcs");
        p.setCostPrice(cost == null ? null : new BigDecimal(cost));
        p.setMinStock(min == null ? null : new BigDecimal(min));
        return productRepository.save(p);
    }

    private void receive(Product p, Warehouse w, int qty) {
        movementService.createMovement(p, w, BigDecimal.valueOf(qty), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "TEST", null);
    }

    private SaleOrderResponse order(Product p, Warehouse w, int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Dashboard Customer");
        req.setWarehouseId(w.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        return saleOrderService.create(req);
    }

    private SaleOrderResponse shippedOrder(Product p, Warehouse w, int qty, String price) {
        SaleOrderResponse so = saleOrderService.confirm(order(p, w, qty, price).getId());
        return saleOrderService.fulfill(so.getId(), null);
    }
}
