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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase I3 (spec §8): Cin7's everyday sale, purchase and inventory reports. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CoreReportTest {

    @Autowired CoreReportService reports;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;
    String id;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("Reports " + id);
        t.setSlug("reports-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        signIn("ROLE_MANAGER");
        warehouse = new Warehouse();
        warehouse.setName("Main " + id);
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELMET-" + id);
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * 3 helmets counted in at £6; 10 bought from Portwest at £5, received, not billed. Abbey: 2 at £10 shipped and
     * invoiced (£24 with VAT), £10 paid. AE Yates: 15 confirmed with 11 left in stock: 4 backordered.
     */
    @Test
    void cin7sEverydayReportsAddUp() {
        movementService.createMovement(helmet, warehouse, new BigDecimal("3"), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL,
                "IN", null, null, new BigDecimal("6.00"));
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        portwest = supplierRepository.save(portwest);
        PurchaseOrderRequest po = new PurchaseOrderRequest();
        po.setSupplierId(portwest.getId());
        po.setWarehouseId(warehouse.getId());
        po.setOrdered(true);
        PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
        pl.setProductId(helmet.getId());
        pl.setQuantity(BigDecimal.TEN);
        pl.setUnitCost(new BigDecimal("5.00"));
        po.setLines(List.of(pl));
        PurchaseOrderResponse purchase = purchaseOrderService.create(po);
        purchaseOrderService.receive(purchase.getId());

        SaleOrderResponse abbey = order("Abbey " + id, 2);
        saleOrderService.fulfill(abbey.getId(), null);
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse invoice = invoiceService.invoiceOrder(abbey.getId(), all);
        PaymentRequest pay = new PaymentRequest();
        pay.setAmount(BigDecimal.TEN);
        pay.setMethod(Payment.PaymentMethod.BANK);
        invoiceService.addPayment(invoice.getId(), pay);
        SaleOrderResponse yates = order("AE Yates " + id, 15);

        // Sales Overview: both orders; Abbey invoiced 24, paid 10, due 14, COGS 2 × 6, profit 8 (40 %)
        CoreReportService.Report overview = reports.run("sales-overview", today, today);
        assertThat(overview.rows()).hasSize(2);
        Map<String, Object> a = row(overview, "order", abbey.getOrderNumber());
        assertThat((BigDecimal) a.get("invoiced")).isEqualByComparingTo("24.00");
        assertThat((BigDecimal) a.get("paid")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) a.get("due")).isEqualByComparingTo("14.00");
        assertThat((BigDecimal) a.get("cogs")).isEqualByComparingTo("12.00");
        assertThat((BigDecimal) a.get("profit")).isEqualByComparingTo("8.00");
        assertThat((BigDecimal) a.get("margin")).isEqualByComparingTo("40.00");
        assertThat(a.get("orderId")).isEqualTo(abbey.getId());
        assertThat((BigDecimal) row(overview, "order", yates.getOrderNumber()).get("ordered")).isEqualByComparingTo("180.00");

        // Sale Invoices & Credit Notes
        CoreReportService.Report invoices = reports.run("sale-invoices", today, today);
        assertThat(invoices.rows()).singleElement().satisfies(r -> {
            assertThat(r.get("number")).isEqualTo(invoice.getInvoiceNumber());
            assertThat((BigDecimal) r.get("due")).isEqualByComparingTo("14.00");
        });
        assertThat(invoices.totals().get("total")).isEqualByComparingTo("24.00");

        // Sales Backorder Details: AE Yates is 4 short, 11 in stock
        CoreReportService.Report backorders = reports.run("sales-backorders", null, null);
        assertThat(backorders.rows()).singleElement().satisfies(r -> {
            assertThat(r.get("order")).isEqualTo(yates.getOrderNumber());
            assertThat((BigDecimal) r.get("backordered")).isEqualByComparingTo("4");
            assertThat((BigDecimal) r.get("available")).isEqualByComparingTo("11");
        });

        // Pending Purchase Orders and Stock Received Not Invoiced: 10 received at £5, no bill yet
        assertThat(reports.run("pending-purchase-orders", null, null).rows()).singleElement().satisfies(r -> {
            assertThat((BigDecimal) r.get("toReceive")).isEqualByComparingTo("0");
            assertThat((BigDecimal) r.get("toInvoice")).isEqualByComparingTo("10");
            assertThat((BigDecimal) r.get("total")).isEqualByComparingTo("50.00");
        });
        assertThat(reports.run("stock-received-not-invoiced", null, null).totals().get("value")).isEqualByComparingTo("50.00");

        // Stock Value Inventory Aging: 1 left at £6 and 10 at £5, all in 0–30 days
        CoreReportService.Report aging = reports.run("stock-value-aging", null, null);
        assertThat(aging.totals().get("value")).isEqualByComparingTo("56.00");
        assertThat(aging.totals().get("b0")).isEqualByComparingTo("56.00");
        assertThat(aging.totals().get("b4")).isEqualByComparingTo("0");

        // Staff: sale and purchase reports, no profit columns, no stock value report
        signIn("ROLE_STAFF");
        assertThat(reports.catalogue()).extracting(CoreReportService.Info::key).doesNotContain("stock-value-aging").contains("sales-overview");
        assertThat(reports.run("sales-overview", today, today).columns()).extracting(CoreReportService.Column::key).doesNotContain("cogs", "profit");
        assertThatThrownBy(() -> reports.run("stock-value-aging", null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reports.run("sales-overview", null, null)).hasMessageContaining("Give the period");
    }

    private SaleOrderResponse order(String customer, int qty) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerName(customer);
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(BigDecimal.TEN);
        req.setLines(List.of(l));
        return saleOrderService.confirm(saleOrderService.create(req).getId());
    }

    private static Map<String, Object> row(CoreReportService.Report r, String key, Object value) {
        return r.rows().stream().filter(x -> value.equals(x.get(key))).findFirst().orElseThrow();
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
