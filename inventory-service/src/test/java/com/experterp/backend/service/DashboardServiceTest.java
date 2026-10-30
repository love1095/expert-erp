package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.dto.InvoiceRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.Payment;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired SupplierRepository supplierRepository;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;

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
        assertThat(k.productsToReorder()).isEqualTo(2);
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

        // Helmet 30 × 12.50 + 2 × 10 = 395.00, gloves 4 × 5 = 20.00 (the cancelled order is left out)
        assertThat(d.topSellers()).extracting(DashboardResponse.Seller::name).containsExactly("HELMET", "GLOVES");
        assertThat(d.topSellers().get(0).quantity()).isEqualByComparingTo("32");
        assertThat(d.topSellers().get(0).value()).isEqualByComparingTo("395.00");
        assertThat(d.worstSellers()).extracting(DashboardResponse.Seller::name).containsExactly("GLOVES", "HELMET");
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
        assertThat(d.topSellers()).isEmpty();
        assertThat(d.worstSellers()).isEmpty();
        assertThat(d.kpis().productsToReorder()).isZero();
        assertThat(dashboardService.reorder(null, 0, 10).getTotalElements()).isZero();
    }

    @Test
    void reorderListCountsStockOnOrderAndSkipsInactiveProducts() {
        useNewTenant();
        Warehouse wh = warehouse();
        Supplier jsp = supplier("JSP Limited");
        Product helmet = product("HELMET", "4.00", "10");   // 3 in stock + 4 on order = 7 < 10 → reorder
        helmet.setDefaultSupplier(jsp);
        helmet.setReorderQuantity(new BigDecimal("24"));
        productRepository.save(helmet);
        Product gloves = product("GLOVES", "1.00", "10");   // 3 in stock + 8 on order = 11 → fine
        Product masks = product("MASKS", "1.00", "10");     // 2 in stock + 8 on order = 10, at the minimum → reorder (Cin7)
        Product caps = product("CAPS", "1.00", null);       // no minimum but a reorder quantity, none in stock → reorder
        caps.setReorderQuantity(new BigDecimal("6"));
        productRepository.save(caps);
        Product vests = product("VESTS", "1.00", null);     // no minimum, no reorder quantity → never listed
        Product boots = product("BOOTS", "1.00", "5");      // nothing → reorder, suggests 5
        Product old = product("OLD", "1.00", "5");          // deprecated → never listed
        old.setStatus(Product.Status.DEPRECATED);
        productRepository.save(old);
        Product fitting = product("FITTING", "1.00", "5");  // a service has no stock → never listed
        fitting.setType(Product.Type.SERVICE);
        productRepository.save(fitting);

        receive(helmet, wh, 3);
        receive(gloves, wh, 3);
        receive(masks, wh, 2);
        purchaseOrder(wh, helmet, 4);
        purchaseOrder(wh, gloves, 8);
        purchaseOrder(wh, masks, 8);
        // A received order is stock, no longer on order; a cancelled one counts for nothing
        purchaseOrderService.receive(purchaseOrder(wh, boots, 1).getId());
        purchaseOrderService.cancel(purchaseOrder(wh, boots, 50).getId());

        Page<DashboardResponse.ReorderLine> page = dashboardService.reorder(null, 0, 10);
        // Lowest stock + on order first: caps 0, boots 1, helmet 7, masks 10
        assertThat(page.getContent()).extracting(DashboardResponse.ReorderLine::name).containsExactly("CAPS", "BOOTS", "HELMET", "MASKS");
        assertThat(page.getContent().get(0).suggestedQuantity()).isEqualByComparingTo("6");
        assertThat(page.getContent().get(3).suggestedQuantity()).isEqualByComparingTo("0");
        DashboardResponse.ReorderLine h = page.getContent().get(2);
        assertThat(h.stock()).isEqualByComparingTo("3");
        assertThat(h.onOrder()).isEqualByComparingTo("4");
        assertThat(h.supplierName()).isEqualTo("JSP Limited");
        assertThat(h.suggestedQuantity()).isEqualByComparingTo("24");
        assertThat(page.getContent().get(1).suggestedQuantity()).isEqualByComparingTo("4");
        assertThat(dashboardService.get(30).kpis().productsToReorder()).isEqualTo(4);

        // Search matches SKU, name or supplier, and pages
        assertThat(dashboardService.reorder("jsp", 0, 10).getContent()).extracting(DashboardResponse.ReorderLine::name).containsExactly("HELMET");
        assertThat(dashboardService.reorder("boots", 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(dashboardService.reorder("100%", 0, 10).getTotalElements()).isZero();
        Page<DashboardResponse.ReorderLine> second = dashboardService.reorder(null, 1, 1);
        assertThat(second.getTotalElements()).isEqualTo(4);
        assertThat(second.getContent()).extracting(DashboardResponse.ReorderLine::name).containsExactly("BOOTS");
    }

    @Test
    void statusCountsShowWorkWaiting() {
        useNewTenant();
        Warehouse wh = warehouse();
        Product p = product("ITEM", "1.00", null);
        receive(p, wh, 100);
        order(p, wh, 1, "5");                                          // draft
        saleOrderService.confirm(order(p, wh, 1, "5").getId());        // to ship
        purchaseOrder(wh, p, 5);                                       // awaiting delivery

        invoice(Invoice.InvoiceType.SALES, null, false);               // draft invoice
        invoice(Invoice.InvoiceType.SALES, LocalDate.now().plusDays(10), true);   // awaiting payment
        invoice(Invoice.InvoiceType.SALES, LocalDate.now().minusDays(1), true);   // awaiting payment, overdue
        InvoiceResponse paid = invoice(Invoice.InvoiceType.SALES, LocalDate.now().minusDays(5), true);
        pay(paid, "100");                                              // fully paid: not waiting
        invoice(Invoice.InvoiceType.PURCHASE, LocalDate.now().minusDays(3), true); // bill overdue

        DashboardResponse d = dashboardService.get(30);
        assertThat(d.sales()).isEqualTo(new DashboardResponse.SalesStatus(0, 1, 1, 0, 1, 2, 1));
        assertThat(d.purchases()).isEqualTo(new DashboardResponse.PurchaseStatus(1, 0, 1, 1));
        assertThat(d.kpis().receivablesOutstanding()).isEqualByComparingTo("200");
    }

    @Test
    void aDateRangeCoversExactlyItsDays() {
        useNewTenant();
        LocalDate today = LocalDate.now();
        DashboardResponse d = dashboardService.get(today.minusDays(9), today.minusDays(5));
        assertThat(d.from()).isEqualTo(today.minusDays(9));
        assertThat(d.to()).isEqualTo(today.minusDays(5));
        assertThat(d.days()).isEqualTo(5);
        assertThat(d.daily()).extracting(DashboardResponse.Day::date).first().isEqualTo(today.minusDays(9));
        assertThat(d.daily()).extracting(DashboardResponse.Day::date).last().isEqualTo(today.minusDays(5));

        // Today's order is outside a period that ended yesterday
        Warehouse wh = warehouse();
        Product p = product("ITEM", "1.00", null);
        order(p, wh, 2, "5");
        assertThat(dashboardService.get(today.minusDays(3), today.minusDays(1)).kpis().ordersInPeriod()).isZero();
        assertThat(dashboardService.get(today, today).kpis().salesValueInPeriod()).isEqualByComparingTo("10");

        assertThatThrownBy(() -> dashboardService.get(today, today.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dashboardService.get(today.minusDays(366), today)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dashboardService.get(today.minusDays(365), today).daily()).hasSize(366);
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

    private Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setName(name);
        return supplierRepository.save(s);
    }

    private PurchaseOrderResponse purchaseOrder(Warehouse w, Product p, int qty) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierName("Supplier");
        req.setWarehouseId(w.getId());
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitCost(BigDecimal.ONE);
        req.setLines(List.of(l));
        return purchaseOrderService.create(req);
    }

    // One line of £100
    private InvoiceResponse invoice(Invoice.InvoiceType type, LocalDate due, boolean issue) {
        InvoiceRequest req = new InvoiceRequest();
        req.setType(type);
        req.setPartyName("Party");
        req.setDueDate(due);
        req.setCurrency("GBP");
        InvoiceRequest.ItemRequest item = new InvoiceRequest.ItemRequest();
        item.setDescription("Goods");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("100"));
        req.setItems(List.of(item));
        InvoiceResponse inv = invoiceService.create(req);
        return issue ? invoiceService.issue(inv.getId()) : inv;
    }

    private void pay(InvoiceResponse inv, String amount) {
        PaymentRequest req = new PaymentRequest();
        req.setAmount(new BigDecimal(amount));
        req.setMethod(Payment.PaymentMethod.BANK);
        invoiceService.addPayment(inv.getId(), req);
    }

    private SaleOrderResponse shippedOrder(Product p, Warehouse w, int qty, String price) {
        SaleOrderResponse so = saleOrderService.confirm(order(p, w, qty, price).getId());
        return saleOrderService.fulfill(so.getId(), null);
    }
}
