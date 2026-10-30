package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.StockLevelResponse;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.PurchaseReceiptRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.PurchaseOrder.Status;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase E2: goods receiving — part, over and short deliveries (docs/phase-e-purchasing.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseReceivingTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier orn;
    Product trouser;
    Product hoodie;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Receiving " + id);
        t.setSlug("receiving-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        orn = new Supplier();
        orn.setName("Orn Clothing");
        orn = supplierRepository.save(orn);
        trouser = product("WWTS-2200R-BK-36R");
        hoodie = product("WWSH-1282-BK-S");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void partOfTheOrderArrivesThenTheRestLikeCin7sPo19069() {
        // 18 ordered, 13 received on the first receipt
        PurchaseOrderResponse po = ordered(line(trouser, 10, "13.00"), line(hoodie, 8, "11.31"));
        PurchaseOrderResponse after = purchaseOrderService.receive(po.getId(),
                receipt(LocalDate.of(2026, 10, 30), qty(po, 0, 10), qty(po, 1, 3)));

        assertThat(after.getStatus()).isEqualTo(Status.ORDERED);
        assertThat(after.getReceivingStatus()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(after.getLines()).extracting(l -> l.getOutstandingQuantity().intValue()).containsExactly(0, 5);
        assertThat(stock(trouser)).isEqualByComparingTo("10");
        assertThat(stock(hoodie)).isEqualByComparingTo("3");
        assertThat(after.getReceipts()).singleElement().satisfies(r -> assertThat(r.getReceivedOn()).isEqualTo(LocalDate.of(2026, 10, 30)));
        // The 5 still to come count as on order
        assertThat(onOrder("WWSH-1282-BK-S")).isEqualByComparingTo("5");

        // The rest arrives: received in full
        PurchaseOrderResponse done = purchaseOrderService.receive(po.getId());
        assertThat(done.getStatus()).isEqualTo(Status.RECEIVED);
        assertThat(done.getReceivingStatus()).isEqualTo("RECEIVED");
        assertThat(done.getReceipts()).hasSize(2);
        assertThat(stock(hoodie)).isEqualByComparingTo("8");
        assertThat(onOrder("WWSH-1282-BK-S")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> purchaseOrderService.receive(po.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void moreThanOrderedIsAcceptedAndFlagged() {
        PurchaseOrderResponse po = ordered(line(trouser, 10, "13.00"));
        PurchaseOrderResponse after = purchaseOrderService.receive(po.getId(), receipt(null, qty(po, 0, 12)));

        assertThat(after.getStatus()).isEqualTo(Status.RECEIVED);
        assertThat(after.isOverDelivered()).isTrue();
        assertThat(after.getLines().get(0).getOverQuantity()).isEqualByComparingTo("2");
        assertThat(after.getReceipts().get(0).getLines().get(0).getOverQuantity()).isEqualByComparingTo("2");
        assertThat(stock(trouser)).isEqualByComparingTo("12");
    }

    @Test
    void theRestCanBeClosedShortButAnOrderWithStockInCannotBeCancelled() {
        PurchaseOrderResponse po = ordered(line(trouser, 10, "13.00"));
        assertThatThrownBy(() -> purchaseOrderService.closeShort(po.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancel it instead");
        purchaseOrderService.receive(po.getId(), receipt(null, qty(po, 0, 6)));
        assertThatThrownBy(() -> purchaseOrderService.cancel(po.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("close the rest short");

        PurchaseOrderResponse closed = purchaseOrderService.closeShort(po.getId());
        assertThat(closed.getStatus()).isEqualTo(Status.RECEIVED);
        assertThat(closed.isClosedShort()).isTrue();
        assertThat(closed.getLines().get(0).getClosedShortQuantity()).isEqualByComparingTo("4");
        assertThat(closed.getLines().get(0).getOutstandingQuantity()).isEqualByComparingTo("0");
        assertThat(stock(trouser)).isEqualByComparingTo("6");
    }

    @Test
    void aRetriedReceiptIsAppliedOnce() {
        PurchaseOrderResponse po = ordered(line(trouser, 10, "13.00"));
        PurchaseReceiptRequest req = receipt(null, qty(po, 0, 4));
        req.setIdempotencyKey("rcv-" + UUID.randomUUID());
        purchaseOrderService.receive(po.getId(), req);
        PurchaseOrderResponse again = purchaseOrderService.receive(po.getId(), req);
        assertThat(again.getReceipts()).hasSize(1);
        assertThat(stock(trouser)).isEqualByComparingTo("4");
    }

    @Test
    void stockOverviewShowsOnOrderNextDeliveryAndStockValueLikeCin7sAvailability() {
        trouser.setCostPrice(new BigDecimal("13.00"));
        productRepository.save(trouser);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        req.setRequiredBy(LocalDate.of(2026, 11, 20));
        req.setLines(List.of(line(trouser, 10, "13.00")));
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        purchaseOrderService.receive(po.getId(), receipt(LocalDate.now(), qty(po, 0, 4)));
        // A draft PO is not on order yet
        purchaseOrderService.create(withDraft(line(trouser, 50, "13.00")));

        // The hoodie came in and went out again: zero on hand
        movementService.createMovement(hoodie, warehouse, new BigDecimal("2"), StockMovement.MovementType.ADJUSTMENT_IN, StockMovement.ReferenceType.MANUAL, "T", null);
        movementService.createMovement(hoodie, warehouse, new BigDecimal("2"), StockMovement.MovementType.ADJUSTMENT_OUT, StockMovement.ReferenceType.MANUAL, "T", null);

        var all = movementService.getStockOverviewPage(0, 20, null, null, false, false).getContent();
        assertThat(all).hasSize(2);
        var row = all.stream().filter(l -> l.getProductId().equals(trouser.getId())).findFirst().orElseThrow();
        assertThat(row.getStock()).isEqualByComparingTo("4");
        assertThat(row.getOnOrder()).isEqualByComparingTo("6");
        assertThat(row.getNextDelivery()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(row.getStockValue()).isEqualByComparingTo("52.00");

        assertThat(movementService.getStockOverviewPage(0, 20, null, null, false, true).getContent())
                .extracting(StockLevelResponse::getProductId).containsExactly(trouser.getId());
        hoodie.setStatus(Product.Status.DEPRECATED);
        productRepository.save(hoodie);
        assertThat(movementService.getStockOverviewPage(0, 20, null, null, true, false).getTotalElements()).isEqualTo(1);
    }

    private PurchaseOrderRequest withDraft(PurchaseOrderRequest.Line... lines) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(lines));
        return req;
    }

    private PurchaseOrderResponse ordered(PurchaseOrderRequest.Line... lines) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        req.setLines(List.of(lines));
        return purchaseOrderService.create(req);
    }

    private Product product(String sku) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setUnit("Each");
        // High minimum: the product stays on the reorder list, which shows what is on order
        p.setMinStock(new BigDecimal("100"));
        p.setDefaultSupplier(orn);
        return productRepository.save(p);
    }

    private static PurchaseOrderRequest.Line line(Product p, int qty, String cost) {
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(new BigDecimal(cost));
        return l;
    }

    private static PurchaseReceiptRequest.Line qty(PurchaseOrderResponse po, int lineIndex, int qty) {
        PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(lineIndex).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private static PurchaseReceiptRequest receipt(LocalDate on, PurchaseReceiptRequest.Line... lines) {
        PurchaseReceiptRequest r = new PurchaseReceiptRequest();
        r.setReceivedOn(on);
        r.setLines(List.of(lines));
        return r;
    }

    private BigDecimal stock(Product p) {
        return movementService.getStockLevel(p.getId(), warehouse.getId()).getStock();
    }

    // "On order" as the reorder list counts it (0 when the product is not on the list)
    private BigDecimal onOrder(String sku) {
        return dashboardService.reorder(sku, 0, 20).getContent().stream()
                .filter(r -> r.sku().equals(sku)).map(com.experterp.backend.dto.DashboardResponse.ReorderLine::onOrder).findFirst().orElse(BigDecimal.ZERO);
    }
}
