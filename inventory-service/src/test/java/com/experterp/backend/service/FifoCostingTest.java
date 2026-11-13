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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase E5: FIFO cost layers, COGS per shipment, bill prices correcting the cost (docs/phase-e-purchasing.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FifoCostingTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired StockMovementService movementService;
    @Autowired ProductService productService;
    @Autowired CostLayerRebuildService rebuildService;
    @Autowired DashboardService dashboardService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Warehouse shop;
    Supplier portwest;
    Product boot;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("FIFO " + id);
        t.setSlug("fifo-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = warehouse("Expert Warehouse");
        shop = warehouse("Shop");
        portwest = new Supplier();
        portwest.setName("Portwest");
        portwest = supplierRepository.save(portwest);
        boot = new Product();
        boot.setSku("FW01-BK-42");
        boot.setName("Steelite boot");
        boot.setUnit("Pair");
        boot.setSellPrice(new BigDecimal("10.00"));
        boot.setCostPrice(new BigDecimal("4.00"));
        boot = productRepository.save(boot);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shippingTakesTheOldestLayersFirst() {
        // Done when (E5): 10 at £5 then 10 at £6, shipping 15 costs £80
        receivedPo(10, "5.00");
        receivedPo(10, "6.00");

        SaleOrderResponse so = ship(15);

        assertThat(so.getLines().get(0).getCostOfGoods()).isEqualByComparingTo("80.00");
        assertThat(so.getCostOfGoods()).isEqualByComparingTo("80.00");
        // £150 sold for £80: 46.67 %
        assertThat(so.getLines().get(0).getMarginPercent()).isEqualByComparingTo("46.67");
        // 5 left, all from the £6 layer
        ProductStockResponse stock = productService.stock(boot.getId());
        assertThat(stock.costLayers()).singleElement().satisfies(l -> {
            assertThat(l.remaining()).isEqualByComparingTo("5");
            assertThat(l.unitCost()).isEqualByComparingTo("6");
            assertThat(l.source()).startsWith("PO-");
        });
        assertThat(stock.stockValue()).isEqualByComparingTo("30.00");
        assertThat(overviewValue(warehouse)).isEqualByComparingTo("30.00");
    }

    @Test
    void aBillAtAnotherPriceCorrectsTheStockLeftAndTheCostAlreadyShipped() {
        PurchaseOrderResponse po = receivedPo(10, "5.00");
        SaleOrderResponse so = ship(4);
        assertThat(so.getCostOfGoods()).isEqualByComparingTo("20.00");

        // The supplier bills £5.50: +£5 in all, £2 on the 4 shipped, £3 on the 6 still in stock
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), bill("INV-55", po, 10, "5.50"));
        assertThat(bill.getCostAdjustment()).isEqualByComparingTo("5.00");
        assertThat(bill.getItems().get(0).getCostMatchedQuantity()).isEqualByComparingTo("10");
        assertThat(saleOrderService.findById(so.getId()).getCostOfGoods()).isEqualByComparingTo("22.00");
        ProductStockResponse stock = productService.stock(boot.getId());
        assertThat(stock.stockValue()).isEqualByComparingTo("33.00");
        assertThat(stock.costLayers().get(0).unitCost()).isEqualByComparingTo("5.50");
        assertThat(stock.costLayers().get(0).originalUnitCost()).isEqualByComparingTo("5.00");

        // What ships next costs the bill's price
        SaleOrderResponse next = ship(2);
        assertThat(next.getCostOfGoods()).isEqualByComparingTo("11.00");

        // Cancelling the bill takes the corrections back (by new adjustments, nothing edited)
        invoiceService.cancel(bill.getId());
        assertThat(saleOrderService.findById(so.getId()).getCostOfGoods()).isEqualByComparingTo("20.00");
        assertThat(saleOrderService.findById(next.getId()).getCostOfGoods()).isEqualByComparingTo("10.00");
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("20.00");
    }

    @Test
    void aBillAheadOfTheGoodsPricesThemWhenTheyArrive() {
        // Invoice first: the bill comes before the goods, which then come in at the bill's price
        PurchaseOrderResponse po = orderedPo(10, "5.00", com.experterp.backend.entity.PurchaseOrder.Approach.INVOICE_FIRST);
        InvoiceResponse bill = invoiceService.billPurchaseOrder(po.getId(), bill("INV-60", po, 10, "6.00"));
        assertThat(bill.getCostAdjustment()).isEqualByComparingTo("0.00");
        purchaseOrderService.receive(po.getId(), receipt(po, 4));
        assertThat(invoiceService.findById(bill.getId()).getCostAdjustment()).isEqualByComparingTo("4.00");

        purchaseOrderService.receive(po.getId(), receipt(po, 6));
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("60.00");
        assertThat(invoiceService.findById(bill.getId()).getCostAdjustment()).isEqualByComparingTo("10.00");
    }

    @Test
    void aDraftBillChangesNothingUntilItIsIssued() {
        PurchaseOrderResponse po = receivedPo(10, "5.00");
        BillRequest req = bill("INV-D", po, 10, "6.00");
        req.setDraft(true);
        InvoiceResponse draft = invoiceService.billPurchaseOrder(po.getId(), req);
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("50.00");
        invoiceService.issue(draft.getId());
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("60.00");
    }

    @Test
    void aTransferMovesLayersAtTheirCostAndALaterBillFollowsThem() {
        PurchaseOrderResponse po = receivedPo(10, "5.00");
        StockTransferRequest t = new StockTransferRequest();
        t.setProductId(boot.getId());
        t.setFromWarehouseId(warehouse.getId());
        t.setToWarehouseId(shop.getId());
        t.setQuantity(new BigDecimal("4"));
        movementService.transfer(t);
        assertThat(overviewValue(shop)).isEqualByComparingTo("20.00");
        assertThat(productService.stock(boot.getId()).costLayers()).extracting(ProductStockResponse.CostLayer::source)
                .anyMatch(s -> s.startsWith("Transfer TRF-"));

        invoiceService.billPurchaseOrder(po.getId(), bill("INV-T", po, 10, "6.00"));
        assertThat(overviewValue(warehouse)).isEqualByComparingTo("36.00");
        assertThat(overviewValue(shop)).isEqualByComparingTo("24.00");
    }

    @Test
    void aRestockedReturnComesBackAtTheCostItLeftAt() {
        receivedPo(10, "5.00");
        receivedPo(10, "6.00");
        SaleOrderResponse so = ship(15);

        SaleReturnRequest req = new SaleReturnRequest();
        SaleReturnRequest.Line l = new SaleReturnRequest.Line();
        l.setSaleOrderLineId(so.getLines().get(0).getId());
        l.setQuantity(new BigDecimal("3"));
        l.setRestock(true);
        req.setLines(List.of(l));
        SaleOrderResponse after = saleOrderService.createReturn(so.getId(), req);

        // £80 / 15 = £5.333 a pair; 3 back: £16 less cost of goods
        assertThat(after.getCostOfGoods()).isEqualByComparingTo("64.00");
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("46.00");
    }

    @Test
    void adjustmentsComeInAtTheCostGivenElseTheCostPrice() {
        StockMovementRequest in = new StockMovementRequest();
        in.setProductId(boot.getId());
        in.setWarehouseId(warehouse.getId());
        in.setQuantity(new BigDecimal("2"));
        in.setType(MovementType.ADJUSTMENT_IN);
        in.setReferenceType(ReferenceType.ADJUSTMENT);
        in.setUnitCost(new BigDecimal("7.25"));
        assertThat(movementService.create(in).getUnitCost()).isEqualByComparingTo("7.25");
        movementService.createMovement(boot, warehouse, new BigDecimal("3"), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "Count", null);

        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo("26.50"); // 2 × 7.25 + 3 × 4
        assertThat(dashboardService.get(java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now()).kpis().stockValue())
                .isEqualByComparingTo("26.50");
    }

    @Test
    void rebuildingFromTheHistoryGivesTheSameCosts() {
        PurchaseOrderResponse first = receivedPo(10, "5.00");
        receivedPo(10, "6.00");
        SaleOrderResponse so = ship(12);
        invoiceService.billPurchaseOrder(first.getId(), bill("INV-R", first, 10, "5.20"));
        StockTransferRequest t = new StockTransferRequest();
        t.setProductId(boot.getId());
        t.setFromWarehouseId(warehouse.getId());
        t.setToWarehouseId(shop.getId());
        t.setQuantity(new BigDecimal("3"));
        movementService.transfer(t);
        SaleOrderResponse later = ship(2);

        BigDecimal cogs = saleOrderService.findById(so.getId()).getCostOfGoods();
        BigDecimal laterCogs = saleOrderService.findById(later.getId()).getCostOfGoods();
        BigDecimal value = productService.stock(boot.getId()).stockValue();
        assertThat(cogs).isEqualByComparingTo("64.00"); // 10 × 5.20 + 2 × 6

        CostLayerRebuildService.Result r = rebuildService.rebuild();
        assertThat(r.bills()).isEqualTo(1);
        assertThat(saleOrderService.findById(so.getId()).getCostOfGoods()).isEqualByComparingTo(cogs);
        assertThat(saleOrderService.findById(later.getId()).getCostOfGoods()).isEqualByComparingTo(laterCogs);
        assertThat(productService.stock(boot.getId()).stockValue()).isEqualByComparingTo(value);
        assertThat(r.stockValue()).isEqualByComparingTo(value);
    }

    // ---- helpers ----

    private Warehouse warehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name + " " + UUID.randomUUID());
        return warehouseRepository.save(w);
    }

    private PurchaseOrderResponse orderedPo(int qty, String cost) {
        return orderedPo(qty, cost, null);
    }

    private PurchaseOrderResponse orderedPo(int qty, String cost, com.experterp.backend.entity.PurchaseOrder.Approach approach) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setApproach(approach);
        req.setSupplierId(portwest.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(boot.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(new BigDecimal(cost));
        req.setLines(List.of(l));
        return purchaseOrderService.create(req);
    }

    private PurchaseOrderResponse receivedPo(int qty, String cost) {
        PurchaseOrderResponse po = orderedPo(qty, cost);
        purchaseOrderService.receive(po.getId());
        return po;
    }

    private static PurchaseReceiptRequest receipt(PurchaseOrderResponse po, int qty) {
        PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(0).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        PurchaseReceiptRequest r = new PurchaseReceiptRequest();
        r.setLines(List.of(l));
        return r;
    }

    private static BillRequest bill(String number, PurchaseOrderResponse po, int qty, String price) {
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber(number);
        BillRequest.Line l = new BillRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(0).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        b.setLines(List.of(l));
        return b;
    }

    private SaleOrderResponse ship(int qty) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName("ACME");
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(boot.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal("10.00"));
        req.setLines(List.of(l));
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(req).getId());
        return saleOrderService.fulfill(so.getId(), null);
    }

    private BigDecimal overviewValue(Warehouse w) {
        return movementService.getStockOverviewPage(0, 50, null, null, false, false).getContent().stream()
                .filter(l -> l.getWarehouseId().equals(w.getId())).findFirst().orElseThrow().getStockValue();
    }
}
