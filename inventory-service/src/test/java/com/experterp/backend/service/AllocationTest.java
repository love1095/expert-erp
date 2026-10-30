package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.StockLevelResponse;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase D2: stock reserved by orders, available stock, backorders (docs/business-rules.md §1). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AllocationTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired ProductService productService;
    @Autowired StockMovementService movementService;
    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SaleOrderRepository saleOrderRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse main;
    Warehouse other;
    Product helmet;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Alloc " + id);
        t.setSlug("alloc-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        main = warehouse("Main");
        other = warehouse("Other");
        helmet = product("HELMET", null);
        receive(helmet, main, 5);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstConfirmedFirstServedAndTheRestIsBackordered() {
        SaleOrderResponse a = confirmed(order(helmet, main, 3));
        SaleOrderResponse b = confirmed(order(helmet, main, 4));

        assertThat(a.isBackordered()).isFalse();
        assertThat(a.getLines().get(0).getAllocatedQuantity()).isEqualByComparingTo("3");
        assertThat(b.isBackordered()).isTrue();
        assertThat(b.getLines().get(0).getAllocatedQuantity()).isEqualByComparingTo("2");
        assertThat(b.getLines().get(0).getBackorderedQuantity()).isEqualByComparingTo("2");

        // 5 on hand, 7 promised
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("7");
        assertThat(productService.findById(helmet.getId()).getAvailableStock()).isEqualByComparingTo("-2");
        StockLevelResponse level = level(helmet, main);
        assertThat(level.getReserved()).isEqualByComparingTo("7");
        assertThat(level.getAvailable()).isEqualByComparingTo("-2");
        assertThat(dashboardService.get(7).sales().backordered()).isEqualTo(1);

        // Shipping A ends its reservation; B still waits for 2
        saleOrderService.fulfill(a.getId(), null);
        SaleOrderResponse bNow = saleOrderService.findById(b.getId());
        assertThat(bNow.getLines().get(0).getAllocatedQuantity()).isEqualByComparingTo("2");
        assertThat(bNow.isBackordered()).isTrue();

        // Cancelling B releases everything
        saleOrderService.cancel(b.getId());
        assertThat(productService.findById(helmet.getId()).getAvailableStock()).isEqualByComparingTo("2");
        assertThat(dashboardService.get(7).sales().backordered()).isZero();
    }

    @Test
    void shippingIsLimitedToWhatIsAllocatedLikeCin7() {
        SaleOrderResponse a = confirmed(order(helmet, main, 3));
        SaleOrderResponse b = confirmed(order(helmet, main, 4));
        Long bLine = b.getLines().get(0).getId();

        // 5 on hand: 3 promised to A, so B may ship 2 at most
        assertThatThrownBy(() -> saleOrderService.fulfill(b.getId(), ship(bLine, "3")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 2 allocated");

        // Shipping without quantities ships what is allocated
        SaleOrderResponse bShipped = saleOrderService.fulfill(b.getId(), null);
        assertThat(bShipped.getLines().get(0).getFulfilledQuantity()).isEqualByComparingTo("2");
        assertThat(bShipped.isBackordered()).isTrue();

        // A's 3 are still there for it
        assertThat(saleOrderService.fulfill(a.getId(), null).getLines().get(0).getFulfilledQuantity()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> saleOrderService.fulfill(b.getId(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Nothing is allocated");
    }

    @Test
    void theOrderConfirmedFirstWinsEvenIfItWasCreatedLater() {
        SaleOrderResponse createdFirst = order(helmet, main, 5);
        SaleOrderResponse createdSecond = order(helmet, main, 5);
        confirmed(createdSecond);
        confirmed(createdFirst);

        assertThat(saleOrderService.findById(createdSecond.getId()).isBackordered()).isFalse();
        assertThat(saleOrderService.findById(createdFirst.getId()).getLines().get(0).getAllocatedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void draftsReserveNothingButImportedShopifyOrdersDoAndWarehousesAreSeparate() {
        SaleOrderResponse draft = order(helmet, main, 4);
        assertThat(saleOrderService.findById(draft.getId()).getLines().get(0).getAllocatedQuantity()).isNull();
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("0");

        // An imported Shopify order still in review reserves (the customer has paid)
        SaleOrder shopify = new SaleOrder();
        shopify.setSource(SaleOrder.Source.SHOPIFY);
        shopify.setOrderNumber("SO-SHOP-" + UUID.randomUUID().toString().substring(0, 6));
        shopify.setCustomer(customer());
        shopify.setCustomerName("Web customer");
        shopify.setWarehouse(main);
        SaleOrderLine line = new SaleOrderLine();
        line.setSaleOrder(shopify);
        line.setProduct(helmet);
        line.setQuantity(new BigDecimal("2"));
        shopify.getLines().add(line);
        saleOrderRepository.save(shopify);
        assertThat(productService.findById(helmet.getId()).getReservedStock()).isEqualByComparingTo("2");

        // An order shipping from another warehouse, where there is no stock, is backordered there only
        SaleOrderResponse elsewhere = confirmed(order(helmet, other, 1));
        assertThat(elsewhere.isBackordered()).isTrue();
        assertThat(level(helmet, main).getAvailable()).isEqualByComparingTo("3");
    }

    @Test
    void theReorderListUsesAvailableStock() {
        Product gloves = product("GLOVES", "5");
        receive(gloves, main, 6);
        assertThat(dashboardService.reorder(gloves.getSku(), 0, 10).getTotalElements()).isZero();

        // 6 on hand − 2 reserved = 4, at or below the minimum of 5
        confirmed(order(gloves, main, 2));
        DashboardResponse.ReorderLine line = dashboardService.reorder(gloves.getSku(), 0, 10).getContent().get(0);
        assertThat(line.stock()).isEqualByComparingTo("4");
        assertThat(line.suggestedQuantity()).isEqualByComparingTo("1");
    }

    private SaleOrderResponse order(Product p, Warehouse w, int qty) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("Allocation customer");
        req.setWarehouseId(w.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(BigDecimal.TEN);
        req.setLines(List.of(l));
        return saleOrderService.create(req);
    }

    private static FulfilmentRequest ship(Long lineId, String qty) {
        FulfilmentRequest req = new FulfilmentRequest();
        FulfilmentRequest.Line l = new FulfilmentRequest.Line();
        l.setSaleOrderLineId(lineId);
        l.setQuantity(new BigDecimal(qty));
        req.setLines(List.of(l));
        return req;
    }

    private SaleOrderResponse confirmed(SaleOrderResponse so) {
        return saleOrderService.confirm(so.getId());
    }

    private Customer customer() {
        Customer c = new Customer();
        c.setName("Web customer " + UUID.randomUUID());
        return customerRepository.save(c);
    }

    private StockLevelResponse level(Product p, Warehouse w) {
        return movementService.getStockOverviewPage(0, 100, null).getContent().stream()
                .filter(l -> l.getProductId().equals(p.getId()) && l.getWarehouseId().equals(w.getId()))
                .findFirst().orElseThrow();
    }

    private Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name + "-" + UUID.randomUUID());
        return warehouseRepository.save(w);
    }

    private Product product(String sku, String min) {
        Product p = new Product();
        p.setSku(sku + "-" + UUID.randomUUID());
        p.setName(sku);
        p.setUnit("pcs");
        p.setMinStock(min == null ? null : new BigDecimal(min));
        return productRepository.save(p);
    }

    private void receive(Product p, Warehouse w, int qty) {
        movementService.createMovement(p, w, BigDecimal.valueOf(qty), MovementType.PURCHASE_IN, ReferenceType.MANUAL, "TEST", null);
    }
}
