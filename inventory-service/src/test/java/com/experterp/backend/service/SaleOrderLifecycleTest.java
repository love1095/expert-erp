package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.SaleReturnRequest;
import com.experterp.backend.entity.AuditLog;
import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.SaleOrder.FulfilmentStatus;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.exception.InsufficientStockException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SaleOrderLifecycleTest {

    @Autowired SaleOrderService saleOrderService;
    @Autowired StockMovementService movementService;
    @Autowired StockMovementRepository movementRepository;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired AuditLogRepository auditLogRepository;

    Long tenantId;
    Warehouse warehouse;
    Product helmet;
    Product gloves;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("expert-safety").orElseThrow().getId();
        TenantContext.set(tenantId);

        warehouse = new Warehouse();
        warehouse.setName("WH-" + UUID.randomUUID());
        warehouse = warehouseRepository.save(warehouse);

        helmet = product("Helmet");
        gloves = product("Gloves");
        receive(helmet, 100);
        receive(gloves, 100);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- lifecycle ----

    @Test
    void draftMustBeConfirmedBeforeFulfilment() {
        SaleOrderResponse so = createOrder(10, 20);

        assertThatThrownBy(() -> saleOrderService.fulfill(so.getId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only CONFIRMED");

        SaleOrderResponse confirmed = saleOrderService.confirm(so.getId());
        assertThat(confirmed.getStatus()).isEqualTo(Status.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThatThrownBy(() -> saleOrderService.confirm(so.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialFulfilmentsMoveExactlyTheShippedQuantities() {
        SaleOrderResponse so = confirmedOrder(10, 20);

        // First shipment: 10 helmets, 5 gloves
        SaleOrderResponse afterFirst = saleOrderService.fulfill(so.getId(),
                fulfilment(null, line(so, 0, 10), line(so, 1, 5)));

        assertThat(afterFirst.getStatus()).isEqualTo(Status.CONFIRMED);
        assertThat(afterFirst.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_FULFILLED);
        assertThat(afterFirst.getLines().get(1).getRemainingQuantity()).isEqualByComparingTo("15");
        assertThat(stock(helmet)).isEqualByComparingTo("90");
        assertThat(stock(gloves)).isEqualByComparingTo("95");

        // Second shipment: everything remaining (15 gloves)
        SaleOrderResponse afterSecond = saleOrderService.fulfill(so.getId(), null);

        assertThat(afterSecond.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(afterSecond.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.FULFILLED);
        assertThat(afterSecond.getFulfilledAt()).isNotNull();
        assertThat(afterSecond.getFulfilments()).hasSize(2);
        assertThat(stock(helmet)).isEqualByComparingTo("90");
        assertThat(stock(gloves)).isEqualByComparingTo("80");
        assertThat(saleOutMovements(so.getId())).hasSize(3);
    }

    @Test
    void cannotFulfilMoreThanRemaining() {
        SaleOrderResponse so = confirmedOrder(10, 20);

        assertThatThrownBy(() -> saleOrderService.fulfill(so.getId(), fulfilment(null, line(so, 0, 11))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only 10 remaining");

        assertThat(stock(helmet)).isEqualByComparingTo("100");
        assertThat(saleOrderService.findById(so.getId()).getFulfilments()).isEmpty();
    }

    @Test
    void retryWithSameIdempotencyKeyShipsOnlyOnce() {
        SaleOrderResponse so = confirmedOrder(10, 20);
        FulfilmentRequest req = fulfilment("ship-1", line(so, 0, 4));

        saleOrderService.fulfill(so.getId(), req);
        SaleOrderResponse retried = saleOrderService.fulfill(so.getId(), req);

        assertThat(retried.getFulfilments()).hasSize(1);
        assertThat(retried.getLines().get(0).getFulfilledQuantity()).isEqualByComparingTo("4");
        assertThat(stock(helmet)).isEqualByComparingTo("96");
    }

    @Test
    void idempotencyKeyCannotBeReusedOnAnotherOrder() {
        SaleOrderResponse first = confirmedOrder(10, 20);
        SaleOrderResponse second = confirmedOrder(10, 20);
        saleOrderService.fulfill(first.getId(), fulfilment("shared-key", line(first, 0, 1)));

        assertThatThrownBy(() -> saleOrderService.fulfill(second.getId(), fulfilment("shared-key", line(second, 0, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void insufficientStockRejectsTheWholeShipment() {
        SaleOrderResponse so = confirmedOrder(10, 150); // only 100 gloves in stock

        assertThatThrownBy(() -> saleOrderService.fulfill(so.getId(), null))
                .isInstanceOf(InsufficientStockException.class);

        SaleOrderResponse after = saleOrderService.findById(so.getId());
        assertThat(after.getFulfilments()).isEmpty();
        assertThat(after.getLines()).allSatisfy(l -> assertThat(l.getFulfilledQuantity()).isZero());
        assertThat(stock(helmet)).isEqualByComparingTo("100");
        assertThat(stock(gloves)).isEqualByComparingTo("100");
    }

    @Test
    void sameProductOnTwoLinesIsCheckedAgainstCombinedQuantity() {
        SaleOrderRequest req = orderRequest();
        req.getLines().add(orderLine(helmet, 60));
        req.getLines().add(orderLine(helmet, 60)); // 120 total, 100 in stock
        SaleOrderResponse so = saleOrderService.create(req);
        saleOrderService.confirm(so.getId());

        assertThatThrownBy(() -> saleOrderService.fulfill(so.getId(), null))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stock(helmet)).isEqualByComparingTo("100");
    }

    @Test
    void concurrentFulfilmentsCannotShipTheSameQuantityTwice() throws Exception {
        SaleOrderResponse so = confirmedOrder(10, 20);
        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            String key = "concurrent-" + i;
            results.add(pool.submit(() -> {
                TenantContext.set(tenantId);
                try {
                    start.await();
                    saleOrderService.fulfill(so.getId(), fulfilment(key, line(so, 0, 10)));
                    return true;
                } catch (IllegalStateException alreadyShipped) {
                    return false;
                } finally {
                    TenantContext.clear();
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) if (r.get(30, TimeUnit.SECONDS)) succeeded++;
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(stock(helmet)).isEqualByComparingTo("90");
    }

    // ---- cancellation ----

    @Test
    void cancellingBeforeShippingCancelsEverything() {
        SaleOrderResponse so = confirmedOrder(10, 20);

        SaleOrderResponse cancelled = saleOrderService.cancel(so.getId());

        assertThat(cancelled.getStatus()).isEqualTo(Status.CANCELLED);
        assertThat(cancelled.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        assertThat(cancelled.getLines()).allSatisfy(l -> {
            assertThat(l.getCancelledQuantity()).isEqualByComparingTo(l.getQuantity());
            assertThat(l.getRemainingQuantity()).isZero();
        });
        assertThatThrownBy(() -> saleOrderService.fulfill(so.getId(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stock(helmet)).isEqualByComparingTo("100");
    }

    @Test
    void cancellingAfterPartialShipmentKeepsShippedQuantities() {
        SaleOrderResponse so = confirmedOrder(10, 20);
        saleOrderService.fulfill(so.getId(), fulfilment(null, line(so, 0, 4)));

        SaleOrderResponse after = saleOrderService.cancel(so.getId());

        assertThat(after.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(after.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_FULFILLED);
        assertThat(after.getLines().get(0).getFulfilledQuantity()).isEqualByComparingTo("4");
        assertThat(after.getLines().get(0).getCancelledQuantity()).isEqualByComparingTo("6");
        assertThat(after.getLines().get(1).getCancelledQuantity()).isEqualByComparingTo("20");
        assertThat(stock(helmet)).isEqualByComparingTo("96"); // no stock comes back on cancel
        assertThatThrownBy(() -> saleOrderService.cancel(so.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- returns ----

    @Test
    void restockedReturnsAddStockAndDamagedReturnsDoNot() {
        SaleOrderResponse so = confirmedOrder(10, 20);
        saleOrderService.fulfill(so.getId(), null);

        SaleReturnRequest req = new SaleReturnRequest();
        req.setReason("Customer changed mind");
        req.setLines(List.of(returnLine(so, 0, 3, true), returnLine(so, 1, 2, false)));
        SaleOrderResponse after = saleOrderService.createReturn(so.getId(), req);

        assertThat(after.getReturns()).hasSize(1);
        assertThat(after.getLines().get(0).getReturnedQuantity()).isEqualByComparingTo("3");
        assertThat(after.getLines().get(1).getReturnedQuantity()).isEqualByComparingTo("2");
        assertThat(stock(helmet)).isEqualByComparingTo("93"); // 100 - 10 + 3
        assertThat(stock(gloves)).isEqualByComparingTo("80"); // damaged: not restocked

        List<StockMovement> returnMovements = movementRepository.findAll().stream()
                .filter(m -> m.getType() == MovementType.SALE_RETURN_IN
                        && m.getReferenceType() == ReferenceType.SALE_RETURN)
                .filter(m -> m.getProduct().getId().equals(helmet.getId()))
                .toList();
        assertThat(returnMovements).hasSize(1);
    }

    @Test
    void cannotReturnMoreThanShippedAndNotYetReturned() {
        SaleOrderResponse so = confirmedOrder(10, 20);
        saleOrderService.fulfill(so.getId(), fulfilment(null, line(so, 0, 5)));

        SaleReturnRequest first = new SaleReturnRequest();
        first.setLines(List.of(returnLine(so, 0, 4, true)));
        saleOrderService.createReturn(so.getId(), first);

        SaleReturnRequest second = new SaleReturnRequest();
        second.setLines(List.of(returnLine(so, 0, 2, true)));
        assertThatThrownBy(() -> saleOrderService.createReturn(so.getId(), second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only 1 shipped and not yet returned");
    }

    @Test
    void returnRetryWithSameKeyIsAppliedOnce() {
        SaleOrderResponse so = confirmedOrder(10, 20);
        saleOrderService.fulfill(so.getId(), null);

        SaleReturnRequest req = new SaleReturnRequest();
        req.setIdempotencyKey("ret-1");
        req.setLines(List.of(returnLine(so, 0, 2, true)));
        saleOrderService.createReturn(so.getId(), req);
        SaleOrderResponse retried = saleOrderService.createReturn(so.getId(), req);

        assertThat(retried.getReturns()).hasSize(1);
        assertThat(stock(helmet)).isEqualByComparingTo("92");
    }

    // ---- audit actor ----

    @Test
    void auditRecordsSystemOrIntegrationActor() {
        SaleOrderResponse so = createOrder(1, 1);
        AuditLog created = lastAudit(so.getId());
        assertThat(created.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(created.getActorId()).isEqualTo("system");

        AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> saleOrderService.confirm(so.getId()));
        AuditLog confirmed = lastAudit(so.getId());
        assertThat(confirmed.getAction()).isEqualTo("CONFIRM");
        assertThat(confirmed.getActorType()).isEqualTo(ActorType.INTEGRATION);
        assertThat(confirmed.getActorId()).isEqualTo("shopify");
    }

    // ---- helpers ----

    private Product product(String name) {
        Product p = new Product();
        p.setSku(name.toUpperCase() + "-" + UUID.randomUUID());
        p.setName(name);
        p.setUnit("pcs");
        return productRepository.save(p);
    }

    private void receive(Product p, int qty) {
        movementService.createMovement(p, warehouse, BigDecimal.valueOf(qty),
                MovementType.PURCHASE_IN, ReferenceType.MANUAL, "TEST", "opening stock");
    }

    private BigDecimal stock(Product p) {
        return movementRepository.calculateStock(p.getId(), warehouse.getId());
    }

    private List<StockMovement> saleOutMovements(Long soId) {
        return movementRepository.findAll().stream()
                .filter(m -> m.getType() == MovementType.SALE_OUT && ("SO-" + soId).equals(m.getReferenceId()))
                .toList();
    }

    private AuditLog lastAudit(Long soId) {
        return auditLogRepository.findAll().stream()
                .filter(a -> "SALE_ORDER".equals(a.getEntityType()) && soId.equals(a.getEntityId()))
                .max(Comparator.comparing(AuditLog::getId))
                .orElseThrow();
    }

    private SaleOrderRequest orderRequest() {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("ACME");
        req.setWarehouseId(warehouse.getId());
        req.setLines(new ArrayList<>());
        return req;
    }

    private SaleOrderRequest.Line orderLine(Product p, int qty) {
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private SaleOrderResponse createOrder(int helmets, int glovePairs) {
        SaleOrderRequest req = orderRequest();
        req.getLines().add(orderLine(helmet, helmets));
        req.getLines().add(orderLine(gloves, glovePairs));
        return saleOrderService.create(req);
    }

    private SaleOrderResponse confirmedOrder(int helmets, int glovePairs) {
        return saleOrderService.confirm(createOrder(helmets, glovePairs).getId());
    }

    private FulfilmentRequest.Line line(SaleOrderResponse so, int index, int qty) {
        FulfilmentRequest.Line l = new FulfilmentRequest.Line();
        l.setSaleOrderLineId(so.getLines().get(index).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private FulfilmentRequest fulfilment(String key, FulfilmentRequest.Line... lines) {
        FulfilmentRequest req = new FulfilmentRequest();
        req.setIdempotencyKey(key);
        req.setLines(List.of(lines));
        return req;
    }

    private SaleReturnRequest.Line returnLine(SaleOrderResponse so, int index, int qty, boolean restock) {
        SaleReturnRequest.Line l = new SaleReturnRequest.Line();
        l.setSaleOrderLineId(so.getLines().get(index).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setRestock(restock);
        return l;
    }
}
